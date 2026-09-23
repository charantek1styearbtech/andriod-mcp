import { describe, it, before } from 'node:test';
import assert from 'node:assert';
import { WebSocket } from 'ws';
import { authManager } from '../src/auth/AuthManager.js';
import { deviceRegistry } from '../src/devices/DeviceRegistry.js';
import { jobManager } from '../src/jobs/JobManager.js';
import { mcpServer } from '../src/mcp/McpServer.js';

describe('Gmail-Scoped WebSocket Instruction Routing Tests', () => {
  const userEmail = 'alice.smith@gmail.com';
  const otherEmail = 'bob.jones@gmail.com';
  const aliceDeviceId = 'phone_alice_pixel';
  const bobDeviceId = 'phone_bob_samsung';

  before(() => {
    // 1. Simulate Alice's device connecting over WebSocket with Alice's Gmail
    const aliceSocket = {
      readyState: 1,
      send: (data: string) => {
        const msg = JSON.parse(data);
        if (msg.type === 'EXECUTE_ACTION') {
          setTimeout(() => {
            jobManager.handleActionResult(msg.requestId, true, {
              status: 'SUCCESS',
              action: msg.action,
              echo: msg.params,
              sourceDevice: aliceDeviceId,
            });
          }, 20);
        }
      },
      close: () => {},
      terminate: () => {},
    } as unknown as WebSocket;

    const authAlice = authManager.authenticateDevice(aliceDeviceId, 'token_alice_123', userEmail);
    deviceRegistry.register(aliceDeviceId, authAlice!.userId, aliceSocket, { model: 'Pixel 8' }, userEmail);

    // 2. Simulate Bob's device connecting over WebSocket with Bob's Gmail
    const bobSocket = {
      readyState: 1,
      send: (data: string) => {
        const msg = JSON.parse(data);
        if (msg.type === 'EXECUTE_ACTION') {
          setTimeout(() => {
            jobManager.handleActionResult(msg.requestId, true, {
              status: 'SUCCESS',
              action: msg.action,
              echo: msg.params,
              sourceDevice: bobDeviceId,
            });
          }, 20);
        }
      },
      close: () => {},
      terminate: () => {},
    } as unknown as WebSocket;

    const authBob = authManager.authenticateDevice(bobDeviceId, 'token_bob_123', otherEmail);
    deviceRegistry.register(bobDeviceId, authBob!.userId, bobSocket, { model: 'Galaxy S24' }, otherEmail);
  });

  it('correctly maps devices to Gmail in registry', () => {
    const aliceDevices = deviceRegistry.getDevicesForEmail(userEmail);
    assert.strictEqual(aliceDevices.length, 1);
    assert.strictEqual(aliceDevices[0].deviceId, aliceDeviceId);
    assert.strictEqual(aliceDevices[0].email, userEmail);

    const bobDevices = deviceRegistry.getDevicesForEmail(otherEmail);
    assert.strictEqual(bobDevices.length, 1);
    assert.strictEqual(bobDevices[0].deviceId, bobDeviceId);

    const primaryAlice = deviceRegistry.getPrimaryDeviceForEmail(userEmail);
    assert.strictEqual(primaryAlice?.deviceId, aliceDeviceId);
  });

  it('routes MCP tool call to target phone using only Gmail (no device_id passed)', async () => {
    // Request has no device_id, only userEmail
    const response = await mcpServer.handleJsonRpc(
      'user_alice',
      {
        jsonrpc: '2.0',
        id: 'test-req-1',
        method: 'tools/call',
        params: {
          name: 'android_get_screen',
          arguments: {
            email: userEmail, // Only passed email!
          },
        },
      },
      userEmail
    );

    assert.strictEqual(response.error, undefined);
    assert.ok(response.result);
    const content = (response.result as any).content[0].text;
    const parsed = JSON.parse(content);
    assert.strictEqual(parsed.sourceDevice, aliceDeviceId);
  });

  it('isolates users so Alice cannot route commands to Bob phone', async () => {
    const response = await mcpServer.handleJsonRpc(
      'user_alice',
      {
        jsonrpc: '2.0',
        id: 'test-req-2',
        method: 'tools/call',
        params: {
          name: 'android_execute_action',
          arguments: {
            device_id: bobDeviceId, // Alice tries to command Bob's device!
          },
        },
      },
      userEmail
    );

    assert.ok(response.error);
    assert.match(response.error.message, /Access denied/);
  });
});
