import test, { describe } from 'node:test';
import assert from 'node:assert';
import type { WebSocket } from 'ws';
import { mcpServer, MCP_TOOLS } from '../src/mcp/McpServer.js';
import { sessionManager } from '../src/mcp/SessionManager.js';
import { deviceRegistry } from '../src/devices/DeviceRegistry.js';
import { authManager } from '../src/auth/AuthManager.js';
import { jobManager } from '../src/jobs/JobManager.js';

describe('Network Monitoring & QA Test Correlation Tool Tests', () => {
  const testSessionId = 'test_session_net_01';
  const testUserEmail = 'qa.engineer@company.com';
  const testDeviceId = 'phone_qa_pixel';

  test('exposes all 16 tools in tools/list including network monitoring suite', async () => {
    assert.strictEqual(MCP_TOOLS.length, 16);
    const names = MCP_TOOLS.map((t) => t.name);
    assert.ok(names.includes('network_start_monitor'));
    assert.ok(names.includes('network_stop_monitor'));
    assert.ok(names.includes('network_get_events'));
    assert.ok(names.includes('network_get_connections'));
    assert.ok(names.includes('network_assert_traffic'));
  });

  test('starts network monitor, captures events, asserts traffic, and stops monitor', async () => {
    // Setup mock phone with network monitor response handling
    const mockSocket = {
      readyState: 1, // OPEN
      send: (data: string) => {
        const msg = JSON.parse(data);
        if (msg.type === 'EXECUTE_ACTION') {
          setTimeout(() => {
            if (msg.action === 'network_start_monitor') {
              jobManager.handleActionResult(msg.requestId, true, {
                success: true,
                status: 'MONITORING',
                testId: msg.params?.test_id || 'checkout_test_01',
                message: 'Network observation active.',
              });
            } else if (msg.action === 'network_get_events') {
              jobManager.handleActionResult(msg.requestId, true, {
                count: 2,
                testId: 'checkout_test_01',
                events: [
                  {
                    eventId: 'pkt_101',
                    protocol: 'HTTPS',
                    host: 'api.shop.com',
                    port: 443,
                    durationMs: 120,
                    bytesSent: 450,
                    bytesReceived: 1890,
                    status: 'COMPLETED',
                  },
                  {
                    eventId: 'pkt_102',
                    protocol: 'HTTPS',
                    host: 'analytics.shop.com',
                    port: 443,
                    durationMs: 65,
                    bytesSent: 210,
                    bytesReceived: 350,
                    status: 'COMPLETED',
                  },
                ],
              });
            } else if (msg.action === 'network_assert_traffic') {
              jobManager.handleActionResult(msg.requestId, true, {
                passed: true,
                testId: 'checkout_test_01',
                matchedRequests: 1,
                failedRequests: 0,
                expectedHost: 'api.shop.com',
                expectedHostSeen: true,
                failureReason: 'All network assertions passed.',
              });
            } else if (msg.action === 'network_stop_monitor') {
              jobManager.handleActionResult(msg.requestId, true, {
                success: true,
                status: 'STOPPED',
                totalRequests: 2,
                failedRequests: 0,
                http5xxErrors: 0,
                totalBytesSent: 660,
                totalBytesReceived: 2240,
                avgDurationMs: 92,
              });
            }
          }, 15);
        }
      },
      close: () => {},
      terminate: () => {},
    } as unknown as WebSocket;

    authManager.registerDevice(testDeviceId, 'token_qa', 'user_qa_engineer_company_com', testUserEmail);
    deviceRegistry.register(
      testDeviceId,
      'user_qa_engineer_company_com',
      mockSocket,
      { model: 'Pixel 8', manufacturer: 'Google' },
      testUserEmail
    );
    sessionManager.setAuthenticatedUser(testSessionId, 'user_qa_engineer_company_com', testUserEmail);
    sessionManager.setActiveDevice(testSessionId, testDeviceId);

    // 1. Start Network Monitor
    const startRes = await mcpServer.handleJsonRpc(
      'user_qa_engineer_company_com',
      {
        jsonrpc: '2.0',
        id: 'net_test_1',
        method: 'tools/call',
        params: {
          name: 'network_start_monitor',
          arguments: {
            test_id: 'checkout_test_01',
          },
        },
      },
      testUserEmail,
      testSessionId
    );
    assert.strictEqual(startRes.error, undefined);
    const startText = JSON.parse((startRes.result as any).content[0].text);
    assert.strictEqual(startText.status, 'MONITORING');

    // 2. Get Events
    const eventsRes = await mcpServer.handleJsonRpc(
      'user_qa_engineer_company_com',
      {
        jsonrpc: '2.0',
        id: 'net_test_2',
        method: 'tools/call',
        params: {
          name: 'network_get_events',
          arguments: {
            test_id: 'checkout_test_01',
            host: 'api.shop.com',
          },
        },
      },
      testUserEmail,
      testSessionId
    );
    assert.strictEqual(eventsRes.error, undefined);
    const eventsText = JSON.parse((eventsRes.result as any).content[0].text);
    assert.strictEqual(eventsText.count, 2);
    assert.strictEqual(eventsText.events[0].host, 'api.shop.com');

    // 3. Assert Traffic
    const assertRes = await mcpServer.handleJsonRpc(
      'user_qa_engineer_company_com',
      {
        jsonrpc: '2.0',
        id: 'net_test_3',
        method: 'tools/call',
        params: {
          name: 'network_assert_traffic',
          arguments: {
            test_id: 'checkout_test_01',
            expected_host: 'api.shop.com',
            max_failed_requests: 0,
          },
        },
      },
      testUserEmail,
      testSessionId
    );
    assert.strictEqual(assertRes.error, undefined);
    const assertText = JSON.parse((assertRes.result as any).content[0].text);
    assert.strictEqual(assertText.passed, true);
    assert.strictEqual(assertText.expectedHostSeen, true);

    // 4. Stop Network Monitor
    const stopRes = await mcpServer.handleJsonRpc(
      'user_qa_engineer_company_com',
      {
        jsonrpc: '2.0',
        id: 'net_test_4',
        method: 'tools/call',
        params: {
          name: 'network_stop_monitor',
          arguments: {},
        },
      },
      testUserEmail,
      testSessionId
    );
    assert.strictEqual(stopRes.error, undefined);
    const stopText = JSON.parse((stopRes.result as any).content[0].text);
    assert.strictEqual(stopText.status, 'STOPPED');
    assert.strictEqual(stopText.totalRequests, 2);
    assert.strictEqual(stopText.totalBytesSent, 660);
  });
});
