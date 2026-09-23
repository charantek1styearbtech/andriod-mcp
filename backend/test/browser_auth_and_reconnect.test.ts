import test, { describe } from 'node:test';
import assert from 'node:assert';
import type { WebSocket } from 'ws';
import { mcpServer } from '../src/mcp/McpServer.js';
import { sessionManager } from '../src/mcp/SessionManager.js';
import { deviceRegistry } from '../src/devices/DeviceRegistry.js';
import { authManager } from '../src/auth/AuthManager.js';
import { jobManager } from '../src/jobs/JobManager.js';

describe('Browser Google OAuth & Reconnect Device Tests', () => {
  const testSessionId = 'test_session_oauth_01';
  const testUserEmail = 'charlie.brown@gmail.com';
  const testDeviceId = 'phone_charlie_pixel';

  test('android_authenticate without arguments returns browser login URL', async () => {
    const res = await mcpServer.handleJsonRpc(
      'user_anonymous',
      {
        jsonrpc: '2.0',
        id: 'auth_req_1',
        method: 'tools/call',
        params: {
          name: 'android_authenticate',
          arguments: {},
        },
      },
      undefined,
      testSessionId
    );

    assert.strictEqual(res.id, 'auth_req_1');
    assert.strictEqual(res.error, undefined);
    const content = (res.result as any).content[0].text;
    const parsed = JSON.parse(content);
    assert.strictEqual(parsed.actionRequired, 'BROWSER_LOGIN');
    assert.ok(parsed.loginUrl.includes('/auth/google/login'));
    assert.ok(parsed.loginUrl.includes(`sessionId=${encodeURIComponent(testSessionId)}`));
    assert.ok(parsed.instructions.length > 0);
  });

  test('android_authenticate with direct email binds session immediately', async () => {
    const res = await mcpServer.handleJsonRpc(
      'user_anonymous',
      {
        jsonrpc: '2.0',
        id: 'auth_req_2',
        method: 'tools/call',
        params: {
          name: 'android_authenticate',
          arguments: {
            email: testUserEmail,
          },
        },
      },
      undefined,
      testSessionId
    );

    assert.strictEqual(res.id, 'auth_req_2');
    assert.strictEqual(res.error, undefined);
    const content = (res.result as any).content[0].text;
    const parsed = JSON.parse(content);
    assert.strictEqual(parsed.success, true);
    assert.strictEqual(parsed.email, testUserEmail);
    assert.strictEqual(sessionManager.getSession(testSessionId)?.email, testUserEmail);
  });

  test('android_reconnect_device reports diagnostics and recovery when device is offline', async () => {
    const res = await mcpServer.handleJsonRpc(
      'user_charlie_brown_gmail_com',
      {
        jsonrpc: '2.0',
        id: 'recon_req_1',
        method: 'tools/call',
        params: {
          name: 'android_reconnect_device',
          arguments: {
            device_id: 'non_existent_or_offline_device',
          },
        },
      },
      testUserEmail,
      testSessionId
    );

    assert.strictEqual(res.id, 'recon_req_1');
    assert.strictEqual(res.error, undefined);
    const content = (res.result as any).content[0].text;
    const parsed = JSON.parse(content);
    assert.strictEqual(parsed.success, false);
    assert.strictEqual(parsed.status, 'OFFLINE');
    assert.ok(parsed.recoveryInstructions.length >= 4);
  });

  test('android_reconnect_device probes and measures latency when device is online', async () => {
    // Register mock online phone
    const mockSocket = {
      readyState: 1, // OPEN
      send: (data: string) => {
        const msg = JSON.parse(data);
        if (msg.type === 'EXECUTE_ACTION' && msg.action === 'ping') {
          setTimeout(() => {
            jobManager.handleActionResult(msg.requestId, true, { pong: true });
          }, 20);
        }
      },
      close: () => {},
      terminate: () => {},
    } as unknown as WebSocket;

    authManager.registerDevice(testDeviceId, 'token_123', 'user_charlie_brown_gmail_com', testUserEmail);
    deviceRegistry.register(
      testDeviceId,
      'user_charlie_brown_gmail_com',
      mockSocket,
      {
        model: 'Pixel 8 Pro',
        manufacturer: 'Google',
      },
      testUserEmail
    );

    const res = await mcpServer.handleJsonRpc(
      'user_charlie_brown_gmail_com',
      {
        jsonrpc: '2.0',
        id: 'recon_req_2',
        method: 'tools/call',
        params: {
          name: 'android_reconnect_device',
          arguments: {
            device_id: testDeviceId,
            probe: true,
          },
        },
      },
      testUserEmail,
      testSessionId
    );

    assert.strictEqual(res.id, 'recon_req_2');
    assert.strictEqual(res.error, undefined);
    const content = (res.result as any).content[0].text;
    const parsed = JSON.parse(content);
    assert.strictEqual(parsed.success, true);
    assert.strictEqual(parsed.status, 'ONLINE');
    assert.ok(parsed.latencyMs >= 15);
    assert.strictEqual(parsed.isActiveSessionDevice, true);
    assert.strictEqual(sessionManager.getActiveDevice(testSessionId), testDeviceId);
  });
});
