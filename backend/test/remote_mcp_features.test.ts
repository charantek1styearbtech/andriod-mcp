import { describe, it, before } from 'node:test';
import assert from 'node:assert';
import { WebSocket } from 'ws';
import { authManager } from '../src/auth/AuthManager.js';
import { deviceRegistry } from '../src/devices/DeviceRegistry.js';
import { jobManager } from '../src/jobs/JobManager.js';
import { mcpServer, MCP_TOOLS } from '../src/mcp/McpServer.js';
import { sessionManager } from '../src/mcp/SessionManager.js';

describe('Remote MCP Features & LLM Tool Suite Tests', () => {
  const userEmail = 'dev.tester@gmail.com';
  const device1 = 'pixel_9_pro';
  const device2 = 'oneplus_12';
  const sessionId = 'session_llm_test_999';

  before(() => {
    // 1. Mock device 1 (Pixel 9 Pro)
    const socket1 = {
      readyState: 1,
      send: (data: string) => {
        const msg = JSON.parse(data);
        if (msg.type === 'EXECUTE_ACTION') {
          setTimeout(() => {
            if (msg.action === 'take_screenshot') {
              jobManager.handleActionResult(msg.requestId, true, {
                screenshotBase64: 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
                width: 1080,
                height: 2400,
                timestamp: Date.now(),
              });
            } else {
              jobManager.handleActionResult(msg.requestId, true, {
                status: 'OK',
                executedAction: msg.action,
                params: msg.params,
                device: device1,
              });
            }
          }, 15);
        }
      },
      close: () => {},
      terminate: () => {},
    } as unknown as WebSocket;

    const authDev1 = authManager.authenticateDevice(device1, 'token_dev1_123', userEmail);
    deviceRegistry.register(device1, authDev1!.userId, socket1, { model: 'Pixel 9 Pro', manufacturer: 'Google' }, userEmail);

    // 2. Mock device 2 (OnePlus 12)
    const socket2 = {
      readyState: 1,
      send: (data: string) => {
        const msg = JSON.parse(data);
        if (msg.type === 'EXECUTE_ACTION') {
          setTimeout(() => {
            jobManager.handleActionResult(msg.requestId, true, {
              status: 'OK',
              executedAction: msg.action,
              params: msg.params,
              device: device2,
            });
          }, 15);
        }
      },
      close: () => {},
      terminate: () => {},
    } as unknown as WebSocket;

    const authDev2 = authManager.authenticateDevice(device2, 'token_dev2_456', userEmail);
    deviceRegistry.register(device2, authDev2!.userId, socket2, { model: 'OnePlus 12', manufacturer: 'OnePlus' }, userEmail);
  });

  it('exposes all tools in tools/list', async () => {
    assert.strictEqual(MCP_TOOLS.length, 16);
    const toolNames = MCP_TOOLS.map((t) => t.name);
    assert.ok(toolNames.includes('android_authenticate'));
    assert.ok(toolNames.includes('android_list_devices'));
    assert.ok(toolNames.includes('android_select_device'));
    assert.ok(toolNames.includes('android_reconnect_device'));
    assert.ok(toolNames.includes('android_get_device_status'));
    assert.ok(toolNames.includes('android_get_screen'));
    assert.ok(toolNames.includes('android_take_screenshot'));
    assert.ok(toolNames.includes('android_open_app'));
    assert.ok(toolNames.includes('android_execute_action'));
    assert.ok(toolNames.includes('android_press_key'));
    assert.ok(toolNames.includes('android_run_goal'));
  });

  it('authenticates session via android_authenticate tool', async () => {
    const res = await mcpServer.handleJsonRpc('user_anonymous', {
      jsonrpc: '2.0',
      id: 'auth-1',
      method: 'tools/call',
      params: {
        name: 'android_authenticate',
        arguments: {
          email: userEmail,
        },
      },
    }, undefined, sessionId);

    assert.strictEqual(res.error, undefined);
    const resultObj = JSON.parse((res.result as any).content[0].text);
    assert.strictEqual(resultObj.success, true);
    assert.strictEqual(resultObj.email, userEmail);
    assert.strictEqual(resultObj.availableDevicesCount, 2);
  });

  it('lists devices and marks active device', async () => {
    const res = await mcpServer.handleJsonRpc('user_anonymous', {
      jsonrpc: '2.0',
      id: 'list-1',
      method: 'tools/call',
      params: {
        name: 'android_list_devices',
        arguments: {},
      },
    }, undefined, sessionId);

    assert.strictEqual(res.error, undefined);
    const resultObj = JSON.parse((res.result as any).content[0].text);
    assert.strictEqual(resultObj.success, true);
    assert.strictEqual(resultObj.count, 2);
    assert.ok(resultObj.devices.length === 2);
  });

  it('selects active device with android_select_device', async () => {
    const res = await mcpServer.handleJsonRpc('user_anonymous', {
      jsonrpc: '2.0',
      id: 'sel-1',
      method: 'tools/call',
      params: {
        name: 'android_select_device',
        arguments: {
          device_id: device2,
        },
      },
    }, undefined, sessionId);

    assert.strictEqual(res.error, undefined);
    const resultObj = JSON.parse((res.result as any).content[0].text);
    assert.strictEqual(resultObj.success, true);
    assert.strictEqual(resultObj.deviceId, device2);
    assert.strictEqual(sessionManager.getActiveDevice(sessionId), device2);
  });

  it('checks device status without specifying device_id (uses active device)', async () => {
    const res = await mcpServer.handleJsonRpc('user_anonymous', {
      jsonrpc: '2.0',
      id: 'status-1',
      method: 'tools/call',
      params: {
        name: 'android_get_device_status',
        arguments: {}, // No device_id specified!
      },
    }, undefined, sessionId);

    assert.strictEqual(res.error, undefined);
    const resultObj = JSON.parse((res.result as any).content[0].text);
    assert.strictEqual(resultObj.success, true);
    assert.strictEqual(resultObj.deviceId, device2); // Target was device2!
    assert.strictEqual(resultObj.isSessionActiveDevice, true);
  });

  it('launches an app by common name (e.g. YouTube) without device_id', async () => {
    const res = await mcpServer.handleJsonRpc('user_anonymous', {
      jsonrpc: '2.0',
      id: 'app-1',
      method: 'tools/call',
      params: {
        name: 'android_open_app',
        arguments: {
          app_name: 'YouTube',
        },
      },
    }, undefined, sessionId);

    assert.strictEqual(res.error, undefined);
    const resultObj = JSON.parse((res.result as any).content[0].text);
    assert.strictEqual(resultObj.success, true);
    assert.strictEqual(resultObj.deviceId, device2);
    assert.strictEqual(resultObj.packageName, 'com.google.android.youtube');
  });

  it('sends key event with android_press_key without device_id', async () => {
    const res = await mcpServer.handleJsonRpc('user_anonymous', {
      jsonrpc: '2.0',
      id: 'key-1',
      method: 'tools/call',
      params: {
        name: 'android_press_key',
        arguments: {
          key: 'BACK',
        },
      },
    }, undefined, sessionId);

    assert.strictEqual(res.error, undefined);
    const resultObj = JSON.parse((res.result as any).content[0].text);
    assert.strictEqual(resultObj.success, true);
    assert.strictEqual(resultObj.deviceId, device2);
    assert.strictEqual(resultObj.key, 'BACK');
  });

  it('switches active device to device1 and takes multimodal screenshot', async () => {
    // 1. Switch active device to device1
    await mcpServer.handleJsonRpc('user_anonymous', {
      jsonrpc: '2.0',
      id: 'sel-2',
      method: 'tools/call',
      params: {
        name: 'android_select_device',
        arguments: {
          device_id: device1,
        },
      },
    }, undefined, sessionId);

    // 2. Take screenshot (no device_id passed)
    const res = await mcpServer.handleJsonRpc('user_anonymous', {
      jsonrpc: '2.0',
      id: 'shot-1',
      method: 'tools/call',
      params: {
        name: 'android_take_screenshot',
        arguments: {},
      },
    }, undefined, sessionId);

    assert.strictEqual(res.error, undefined);
    const contents = (res.result as any).content;
    assert.strictEqual(contents.length, 2);
    // Multimodal image block
    assert.strictEqual(contents[0].type, 'image');
    assert.strictEqual(contents[0].mimeType, 'image/jpeg');
    assert.ok(contents[0].data.length > 0);
    // Metadata block
    const meta = JSON.parse(contents[1].text);
    assert.strictEqual(meta.deviceId, device1);
    assert.strictEqual(meta.width, 1080);
    assert.strictEqual(meta.height, 2400);
  });
});
