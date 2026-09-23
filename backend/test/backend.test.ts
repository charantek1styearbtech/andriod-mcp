import { describe, it, before, after } from 'node:test';
import assert from 'node:assert';
import { WebSocket } from 'ws';
import { authManager } from '../src/auth/AuthManager.js';
import { deviceRegistry } from '../src/devices/DeviceRegistry.js';
import { deviceCommandQueue } from '../src/queue/DeviceCommandQueue.js';
import { jobManager } from '../src/jobs/JobManager.js';
import { mcpServer } from '../src/mcp/McpServer.js';

describe('Multi-Tenant Android MCP Backend Core Tests', () => {
  const testUserId = 'user_test_101';
  const otherUserId = 'user_test_202';
  const testDeviceId = 'phone_alpha';
  const otherDeviceId = 'phone_beta';
  const testToken = 'token_alpha_secret';

  before(() => {
    // Setup users & devices
    authManager.registerUser({
      userId: testUserId,
      name: 'Test User Alpha',
      apiKey: 'sk-test-user-101',
    });
    authManager.registerUser({
      userId: otherUserId,
      name: 'Test User Beta',
      apiKey: 'sk-test-user-202',
    });

    authManager.registerDevice(testDeviceId, testToken, testUserId);
    authManager.registerDevice(otherDeviceId, 'token_beta_secret', otherUserId);
  });

  it('enforces multi-tenant device ownership validation', () => {
    assert.strictEqual(authManager.verifyDeviceOwnership(testUserId, testDeviceId), true);
    assert.strictEqual(authManager.verifyDeviceOwnership(testUserId, otherDeviceId), false);
    assert.strictEqual(authManager.verifyDeviceOwnership(otherUserId, testDeviceId), false);
  });

  it('rejects MCP tool calls targeted to unauthorized devices', async () => {
    const response = await mcpServer.handleJsonRpc(otherUserId, {
      jsonrpc: '2.0',
      id: 'req-1',
      method: 'tools/call',
      params: {
        name: 'android_get_screen',
        arguments: {
          device_id: testDeviceId, // user_202 tries to access user_101's device!
        },
      },
    });

    assert.ok(response.error);
    assert.match(response.error.message, /Access denied/);
  });

  it('serializes commands on the same device in FIFO order', async () => {
    // Simulate mock WebSocket for testDeviceId
    const mockSocket = {
      readyState: 1,
      send: (data: string) => {
        const msg = JSON.parse(data);
        if (msg.type === 'EXECUTE_ACTION') {
          // Send back result after tiny delay
          setTimeout(() => {
            jobManager.handleActionResult(msg.requestId, true, {
              status: 'SUCCESS',
              action: msg.action,
              order: msg.params.order,
            });
          }, 30);
        }
      },
      close: () => {},
      terminate: () => {},
    } as unknown as WebSocket;

    deviceRegistry.register(testDeviceId, testUserId, mockSocket);

    // Dispatch two commands concurrently to the same phone
    const promise1 = jobManager.submitJob(testUserId, testDeviceId, 'tap', { order: 1 });
    const promise2 = jobManager.submitJob(testUserId, testDeviceId, 'tap', { order: 2 });

    // Allow async instance lookup to resolve and queue job
    await new Promise((r) => setTimeout(r, 5));

    // Job 1 should be RUNNING, Job 2 should be QUEUED
    assert.strictEqual(deviceCommandQueue.getQueueLength(testDeviceId), 1);

    const [res1, res2] = await Promise.all([promise1, promise2]);
    assert.strictEqual((res1 as any).order, 1);
    assert.strictEqual((res2 as any).order, 2);
    // Queue should be completely empty and device back to ONLINE
    assert.strictEqual(deviceCommandQueue.getQueueLength(testDeviceId), 0);
    assert.strictEqual(deviceRegistry.getDevice(testDeviceId)?.state, 'ONLINE');
  });
});
