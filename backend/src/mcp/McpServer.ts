import { authManager } from '../auth/AuthManager.js';
import { deviceRegistry } from '../devices/DeviceRegistry.js';
import { jobManager } from '../jobs/JobManager.js';
import { sessionManager } from './SessionManager.js';

export interface JsonRpcRequest {
  jsonrpc: '2.0';
  id?: string | number | null;
  method: string;
  params?: Record<string, unknown>;
}

export interface JsonRpcResponse {
  jsonrpc: '2.0';
  id: string | number | null;
  result?: unknown;
  error?: {
    code: number;
    message: string;
    data?: unknown;
  };
}

// Common Android package mappings for natural language app launching
const COMMON_APP_PACKAGES: Record<string, string> = {
  youtube: 'com.google.android.youtube',
  settings: 'com.android.settings',
  chrome: 'com.android.chrome',
  browser: 'com.android.chrome',
  camera: 'com.android.camera',
  maps: 'com.google.android.apps.maps',
  googlemaps: 'com.google.android.apps.maps',
  whatsapp: 'com.whatsapp',
  photos: 'com.google.android.apps.photos',
  gallery: 'com.google.android.apps.photos',
  playstore: 'com.android.vending',
  store: 'com.android.vending',
  gmail: 'com.google.android.gm',
  mail: 'com.google.android.gm',
  clock: 'com.google.android.deskclock',
  calculator: 'com.google.android.calculator',
  messages: 'com.google.android.apps.messaging',
  agent: 'com.agent.androidmcp',
  androidagent: 'com.agent.androidmcp',
};

export const MCP_TOOLS = [
  {
    name: 'android_authenticate',
    description:
      'Authenticate your LLM session with a Google / Gmail account or API key. If email is omitted, returns a secure 1-click browser login link for official Google Sign-In. Once authenticated, all your registered Android devices become accessible.',
    inputSchema: {
      type: 'object',
      properties: {
        email: {
          type: 'string',
          description: 'Optional Google / Gmail address linked on the Android phone (e.g. rishikareddy050@gmail.com). If omitted, generates a 1-click browser login link.',
        },
        api_key: {
          type: 'string',
          description: 'Optional API key for tenant authentication.',
        },
      },
    },
  },
  {
    name: 'android_reconnect_device',
    description:
      'Diagnose and test connection health with an Android device across the distributed cluster. Probes device responsiveness with a live round-trip ping, checks Redis presence and MongoDB downtime, and provides recovery steps if offline.',
    inputSchema: {
      type: 'object',
      properties: {
        device_id: {
          type: 'string',
          description: 'Target device ID. If omitted, checks the currently selected active device or primary device for this session.',
        },
        probe: {
          type: 'boolean',
          description: 'Whether to send an active ping probe to measure latency and test device responsiveness (default: true).',
        },
      },
    },
  },
  {
    name: 'android_list_devices',
    description:
      'List all registered and online Android devices available for the authenticated user/email. Returns device IDs, models, connection states, and which device is currently active.',
    inputSchema: {
      type: 'object',
      properties: {
        email: {
          type: 'string',
          description: 'Optional Gmail address to query devices for. Defaults to session authenticated email.',
        },
      },
    },
  },
  {
    name: 'android_select_device',
    description:
      'Select and set the active Android device for this conversation session. Subsequent tool calls (screen, action, screenshot, app launch) will automatically target this device without needing device_id specified each time.',
    inputSchema: {
      type: 'object',
      properties: {
        device_id: {
          type: 'string',
          description: 'The device ID to select as active (e.g. oneplus_nord_4). Call android_list_devices first if unsure.',
        },
      },
      required: ['device_id'],
    },
  },
  {
    name: 'android_get_device_status',
    description:
      'Get detailed real-time health, connection state, hardware metadata, and accessibility status of the target or active device.',
    inputSchema: {
      type: 'object',
      properties: {
        device_id: {
          type: 'string',
          description: 'Target device ID. If omitted, uses the currently selected active device for this session.',
        },
      },
    },
  },
  {
    name: 'android_get_screen',
    description:
      'Inspect the current physical screen of the Android device. Returns foreground application, activity name, and structured UI hierarchy with element text, bounds, and IDs.',
    inputSchema: {
      type: 'object',
      properties: {
        device_id: {
          type: 'string',
          description: 'Target device ID. If omitted, uses the currently selected active device.',
        },
      },
    },
  },
  {
    name: 'android_take_screenshot',
    description:
      'Capture a high-resolution screenshot of the physical Android device. Returns an image content block (image/jpeg) viewable directly by multimodal vision models, plus screen dimension metadata.',
    inputSchema: {
      type: 'object',
      properties: {
        device_id: {
          type: 'string',
          description: 'Target device ID. If omitted, uses the currently selected active device.',
        },
      },
    },
  },
  {
    name: 'android_open_app',
    description:
      'Launch an application on the Android device by common name (e.g. "YouTube", "Settings", "Chrome", "Camera", "Maps", "WhatsApp") or Android package name.',
    inputSchema: {
      type: 'object',
      properties: {
        app_name: {
          type: 'string',
          description: 'Common name of the app (e.g. "YouTube", "Settings", "Chrome") or full package name.',
        },
        package_name: {
          type: 'string',
          description: 'Optional explicit Android package name (e.g. "com.google.android.youtube").',
        },
        device_id: {
          type: 'string',
          description: 'Target device ID. If omitted, uses the currently selected active device.',
        },
      },
      required: ['app_name'],
    },
  },
  {
    name: 'android_execute_action',
    description:
      'Execute an atomic UI action on the Android device (tap, double_tap, long_press, type, swipe, scroll_forward, scroll_backward, wait).',
    inputSchema: {
      type: 'object',
      properties: {
        action: {
          type: 'string',
          description: 'Action type: tap, click, double_tap, long_press, type, swipe, scroll_forward, scroll_backward, wait.',
        },
        device_id: {
          type: 'string',
          description: 'Target device ID. If omitted, uses the currently selected active device.',
        },
        element_id: {
          type: 'string',
          description: 'Optional element ID or resource-id from android_get_screen to target.',
        },
        coordinates: {
          type: 'object',
          properties: {
            x: { type: 'number' },
            y: { type: 'number' },
          },
          description: 'Optional screen coordinates [x, y] to tap/click.',
        },
        text: {
          type: 'string',
          description: 'Text to input for "type" action or element text to click.',
        },
        duration_ms: {
          type: 'number',
          description: 'Duration in milliseconds for wait or press actions.',
        },
        swipe_direction: {
          type: 'string',
          description: 'Direction for swipe: UP, DOWN, LEFT, RIGHT.',
        },
      },
      required: ['action'],
    },
  },
  {
    name: 'android_press_key',
    description:
      'Send a physical system navigation key event to the device (BACK, HOME, RECENTS, ENTER, VOLUME_UP, VOLUME_DOWN, POWER).',
    inputSchema: {
      type: 'object',
      properties: {
        key: {
          type: 'string',
          description: 'Key to press: BACK, HOME, RECENTS, ENTER, VOLUME_UP, VOLUME_DOWN, POWER.',
        },
        device_id: {
          type: 'string',
          description: 'Target device ID. If omitted, uses the currently selected active device.',
        },
      },
      required: ['key'],
    },
  },
  {
    name: 'android_run_goal',
    description:
      'Execute a high-level autonomous multi-step goal on the Android device using the on-device ReAct planning agent (e.g. "Open YouTube and play jazz music").',
    inputSchema: {
      type: 'object',
      properties: {
        goal: {
          type: 'string',
          description: 'The natural language goal to accomplish on the device.',
        },
        max_steps: {
          type: 'number',
          description: 'Maximum planning iterations (default: 10).',
        },
        device_id: {
          type: 'string',
          description: 'Target device ID. If omitted, uses the currently selected active device.',
        },
      },
      required: ['goal'],
    },
  },
  {
    name: 'network_start_monitor',
    description:
      'Start network flow observation on the Android device using VpnService. Captures request/response metadata, bytes, latency, and hostnames for mobile testing without exposing sensitive raw payloads.',
    inputSchema: {
      type: 'object',
      properties: {
        device_id: {
          type: 'string',
          description: 'Target device ID. If omitted, uses the currently selected active device.',
        },
        test_id: {
          type: 'string',
          description: 'Optional test run identifier to scope and correlate network events with automated UI actions.',
        },
      },
    },
  },
  {
    name: 'network_stop_monitor',
    description:
      'Stop network observation on the Android device and retrieve aggregated flow summary statistics (total requests, failed requests, 4xx/5xx counts, bytes sent/received, average latency).',
    inputSchema: {
      type: 'object',
      properties: {
        device_id: {
          type: 'string',
          description: 'Target device ID. If omitted, uses the currently selected active device.',
        },
      },
    },
  },
  {
    name: 'network_get_events',
    description:
      'Retrieve captured network flow events observed during testing. Filterable by target host and test ID.',
    inputSchema: {
      type: 'object',
      properties: {
        device_id: {
          type: 'string',
          description: 'Target device ID. If omitted, uses the currently selected active device.',
        },
        test_id: {
          type: 'string',
          description: 'Filter events by specific test ID.',
        },
        host: {
          type: 'string',
          description: 'Filter events by destination host (e.g. "api.example.com").',
        },
        limit: {
          type: 'number',
          description: 'Maximum events to return (default: 50).',
        },
      },
    },
  },
  {
    name: 'network_get_connections',
    description:
      'List currently active TCP/TLS connections observed on the Android device.',
    inputSchema: {
      type: 'object',
      properties: {
        device_id: {
          type: 'string',
          description: 'Target device ID. If omitted, uses the currently selected active device.',
        },
      },
    },
  },
  {
    name: 'network_assert_traffic',
    description:
      'Assert network test expectations (e.g. verify expected API endpoints were called, assert that 4xx/5xx errors did not exceed threshold).',
    inputSchema: {
      type: 'object',
      properties: {
        device_id: {
          type: 'string',
          description: 'Target device ID. If omitted, uses the currently selected active device.',
        },
        test_id: {
          type: 'string',
          description: 'Test ID to evaluate assertions against.',
        },
        expected_host: {
          type: 'string',
          description: 'Expected API host that must have been contacted during the test.',
        },
        max_failed_requests: {
          type: 'number',
          description: 'Maximum allowable failed/error requests (default: 0).',
        },
      },
    },
  },
];

export class McpServer {
  /**
   * Process an incoming MCP JSON-RPC 2.0 request scoped to an authenticated user and session.
   */
  public async handleJsonRpc(
    userId: string,
    body: JsonRpcRequest,
    userEmail?: string,
    sessionId?: string
  ): Promise<JsonRpcResponse> {
    const id = body.id !== undefined ? body.id : null;
    const session = sessionManager.getOrCreateSession(sessionId, userId, userEmail);

    try {
      switch (body.method) {
        case 'initialize': {
          return {
            jsonrpc: '2.0',
            id,
            result: {
              protocolVersion: '2024-11-05',
              capabilities: {
                tools: {
                  listChanged: false,
                },
              },
              serverInfo: {
                name: 'android-remote-mcp-server',
                version: '2.0.0',
              },
            },
          };
        }

        case 'notifications/initialized': {
          return {
            jsonrpc: '2.0',
            id,
            result: {},
          };
        }

        case 'ping': {
          return {
            jsonrpc: '2.0',
            id,
            result: {},
          };
        }

        case 'tools/list': {
          return {
            jsonrpc: '2.0',
            id,
            result: {
              tools: MCP_TOOLS,
            },
          };
        }

        case 'tools/call': {
          const params = body.params as { name?: string; arguments?: Record<string, unknown> } | undefined;
          if (!params || !params.name) {
            return {
              jsonrpc: '2.0',
              id,
              error: { code: -32602, message: 'Invalid params: missing tool name' },
            };
          }

          const toolName = params.name;
          const args = params.arguments || {};

          const toolResult = await this.executeTool(session.sessionId, toolName, args);
          return {
            jsonrpc: '2.0',
            id,
            result: toolResult,
          };
        }

        default: {
          return {
            jsonrpc: '2.0',
            id,
            error: {
              code: -32601,
              message: `Method not found: ${body.method}`,
            },
          };
        }
      }
    } catch (err: unknown) {
      const errorMessage = err instanceof Error ? err.message : String(err);
      return {
        jsonrpc: '2.0',
        id,
        error: {
          code: -32000,
          message: errorMessage,
        },
      };
    }
  }

  /**
   * Execute an MCP tool with session tracking, device selection, and auto-device fallback.
   */
  private async executeTool(
    sessionId: string,
    toolName: string,
    args: Record<string, unknown>
  ): Promise<{ content: Array<{ type: string; text?: string; data?: string; mimeType?: string }> }> {
    const session = sessionManager.getSession(sessionId)!;
    let currentUserId = session.userId;
    let currentEmail = session.email;

    // ----------------------------------------------------
    // Tool 1: Authenticate Session
    // ----------------------------------------------------
    if (toolName === 'android_authenticate') {
      const email = (args.email as string)?.toLowerCase().trim();
      const apiKey = args.api_key as string | undefined;

      if (!email && !apiKey) {
        const publicUrl = process.env.PUBLIC_URL || `http://localhost:${process.env.PORT || 3000}`;
        const loginUrl = `${publicUrl}/auth/google/login?sessionId=${encodeURIComponent(sessionId)}`;
        return {
          content: [
            {
              type: 'text',
              text: JSON.stringify(
                {
                  actionRequired: 'BROWSER_LOGIN',
                  message: 'Please complete Google authentication in your browser to link your Android devices.',
                  loginUrl,
                  markdownLink: `[Click here to Log In with Google](${loginUrl})`,
                  instructions: [
                    `1. Click or open this Google Login link in your browser: ${loginUrl}`,
                    '2. Sign in with the Google account linked to your Android phone.',
                    '3. Once authenticated in your browser, return here. Your session will be automatically authenticated with your devices.',
                  ],
                },
                null,
                2
              ),
            },
          ],
        };
      }

      let user = apiKey ? authManager.authenticateUser(apiKey) : null;
      if (!user && email) {
        user = authManager.getOrCreateUserForEmail(email);
      }

      if (!user) {
        throw new Error('Authentication failed: Invalid credentials.');
      }

      sessionManager.setAuthenticatedUser(sessionId, user.userId, email || user.email);

      // Auto-select primary device if available
      const devices = email
        ? await deviceRegistry.getDevicesForEmailAsync(email)
        : await deviceRegistry.listDevicesAsync(user.userId);
      let selectedMsg = '';
      if (devices.length > 0) {
        await sessionManager.setActiveDeviceAsync(sessionId, devices[0].deviceId, email || user.email);
        selectedMsg = ` Auto-selected active device: '${devices[0].deviceId}' (${devices[0].metadata?.model || 'Android'}).`;
      }

      return {
        content: [
          {
            type: 'text',
            text: JSON.stringify(
              {
                success: true,
                message: `Session authenticated for ${email || user.userId}.${selectedMsg}`,
                userId: user.userId,
                email: email || user.email,
                availableDevicesCount: devices.length,
                activeDeviceId: (await sessionManager.getActiveDeviceAsync(sessionId)) || null,
              },
              null,
              2
            ),
          },
        ],
      };
    }

    // ----------------------------------------------------
    // Tool 2: List Devices
    // ----------------------------------------------------
    if (toolName === 'android_list_devices') {
      const targetEmail = ((args.email as string) || currentEmail)?.toLowerCase().trim();
      const devices = targetEmail
        ? await deviceRegistry.getDevicesForEmailAsync(targetEmail)
        : await deviceRegistry.listDevicesAsync(currentUserId);

      const activeDeviceId = await sessionManager.getActiveDeviceAsync(sessionId);

      return {
        content: [
          {
            type: 'text',
            text: JSON.stringify(
              {
                success: true,
                count: devices.length,
                activeDeviceId: activeDeviceId || (devices.length === 1 ? devices[0].deviceId : null),
                devices: devices.map((d) => ({
                  deviceId: d.deviceId,
                  email: d.email,
                  status: d.state,
                  isActive: d.deviceId === activeDeviceId || (devices.length === 1 && !activeDeviceId),
                  connectedAt: new Date(d.connectedAt).toISOString(),
                  lastSeenAt: new Date(d.lastSeenAt).toISOString(),
                  metadata: d.metadata,
                })),
                instruction:
                  devices.length > 1 && !activeDeviceId
                    ? "Call 'android_select_device' with your chosen device_id to set it as active for this session."
                    : undefined,
              },
              null,
              2
            ),
          },
        ],
      };
    }

    // ----------------------------------------------------
    // Tool 3: Select Active Device
    // ----------------------------------------------------
    if (toolName === 'android_select_device') {
      const deviceId = (args.device_id as string)?.trim();
      if (!deviceId) {
        throw new Error("Missing required parameter: 'device_id'");
      }

      const isOnline = await deviceRegistry.isDeviceOnlineAsync(deviceId);
      if (!isOnline) {
        throw new Error(
          `Device '${deviceId}' is currently offline or not registered. Call 'android_list_devices' to see online devices.`
        );
      }

      // Verify ownership
      const isOwner =
        authManager.verifyDeviceOwnership(currentUserId, deviceId) ||
        (currentEmail && (await deviceRegistry.getDevicesForEmailAsync(currentEmail)).some(d => d.deviceId === deviceId));

      if (!isOwner && currentUserId !== 'user_anonymous' && currentUserId !== 'user_mcp_primary') {
        throw new Error(`Access denied: Device '${deviceId}' is not associated with your account.`);
      }

      await sessionManager.setActiveDeviceAsync(sessionId, deviceId, currentEmail);

      const device =
        deviceRegistry.getDevice(deviceId) ||
        (await deviceRegistry.getDevicesForEmailAsync(currentEmail || '')).find(d => d.deviceId === deviceId);

      return {
        content: [
          {
            type: 'text',
            text: JSON.stringify(
              {
                success: true,
                message: `Device '${deviceId}' is now active for this session. Subsequent commands will target this device by default.`,
                deviceId,
                model: device?.metadata?.model || 'Android Device',
                status: device?.state || 'ONLINE',
              },
              null,
              2
            ),
          },
        ],
      };
    }

    // ----------------------------------------------------
    // Tool 3b: Reconnect / Diagnose Device Connection
    // ----------------------------------------------------
    if (toolName === 'android_reconnect_device') {
      let targetDevId: string | undefined = (args.device_id as string)?.trim();
      if (!targetDevId) {
        targetDevId = await sessionManager.getActiveDeviceAsync(sessionId);
      }
      if (!targetDevId && currentEmail) {
        targetDevId = await sessionManager.getActiveDeviceForEmailAsync(currentEmail);
      }
      if (!targetDevId && currentEmail) {
        const emailDevs = await deviceRegistry.getDevicesForEmailAsync(currentEmail);
        if (emailDevs.length > 0) targetDevId = emailDevs[0].deviceId;
      }
      if (!targetDevId) {
        const userDevs = await deviceRegistry.listDevicesAsync(currentUserId);
        if (userDevs.length > 0) targetDevId = userDevs[0].deviceId;
      }

      if (!targetDevId) {
        return {
          content: [
            {
              type: 'text',
              text: JSON.stringify(
                {
                  success: false,
                  status: 'UNKNOWN',
                  message:
                    'No target device specified and no active device registered for this session. Please call android_authenticate or android_list_devices first.',
                },
                null,
                2
              ),
            },
          ],
        };
      }

      const isOnline = await deviceRegistry.isDeviceOnlineAsync(targetDevId);
      const hostInstance = await deviceRegistry.findDeviceInstance(targetDevId);
      const shouldProbe = args.probe !== false;

      if (isOnline) {
        let latencyMs = 0;
        let probeSuccess = true;
        if (shouldProbe) {
          const t0 = Date.now();
          try {
            await jobManager.submitJob(currentUserId, targetDevId, 'ping', {}, 5000);
            latencyMs = Date.now() - t0;
          } catch {
            probeSuccess = false;
          }
        }

        await sessionManager.setActiveDeviceAsync(sessionId, targetDevId, currentEmail);

        const dev =
          deviceRegistry.getDevice(targetDevId) ||
          (await deviceRegistry.getDevicesForEmailAsync(currentEmail || '')).find((d) => d.deviceId === targetDevId);

        return {
          content: [
            {
              type: 'text',
              text: JSON.stringify(
                {
                  success: probeSuccess,
                  deviceId: targetDevId,
                  status: 'ONLINE',
                  hostInstance: hostInstance || 'local',
                  model: dev?.metadata?.model || 'Android Device',
                  latencyMs: shouldProbe ? latencyMs : undefined,
                  message: `Device '${targetDevId}' is ONLINE and connected to ${hostInstance || 'local cluster'}.${shouldProbe ? ` Live round-trip probe latency: ${latencyMs}ms.` : ''}`,
                  isActiveSessionDevice: true,
                },
                null,
                2
              ),
            },
          ],
        };
      } else {
        const registeredDevices = currentEmail ? await deviceRegistry.getDevicesForEmailAsync(currentEmail) : [];
        const dev = registeredDevices.find((d) => d.deviceId === targetDevId);
        const lastSeen = dev?.lastSeenAt ? new Date(dev.lastSeenAt).toISOString() : 'Unknown';
        const offlineSeconds = dev?.lastSeenAt ? Math.round((Date.now() - dev.lastSeenAt) / 1000) : undefined;

        return {
          content: [
            {
              type: 'text',
              text: JSON.stringify(
                {
                  success: false,
                  deviceId: targetDevId,
                  status: 'OFFLINE',
                  lastSeenAt: lastSeen,
                  offlineDurationSeconds: offlineSeconds,
                  diagnostics: {
                    socketConnection: 'DISCONNECTED',
                    redisPresence: 'EXPIRED',
                  },
                  recoveryInstructions: [
                    '1. Wake the Android phone screen (press the power button or tap the screen).',
                    '2. Open the Android Agent app or confirm the background service is running.',
                    '3. Verify your phone is connected to Wi-Fi or mobile data.',
                    '4. In the app\'s "Remote Server" tab, ensure Gateway URL is set to your server (e.g. wss://<domain>/device/ws).',
                    '5. Tap the "Connect / Reconnect" button in the app to re-establish the connection.',
                  ],
                },
                null,
                2
              ),
            },
          ],
        };
      }
    }

    // ----------------------------------------------------
    // Resolve Target Device for Action / Inspection Tools
    // ----------------------------------------------------
    let targetDeviceId: string | undefined = (args.device_id as string)?.trim();

    // 1. If not explicitly specified, use session's active device
    if (!targetDeviceId) {
      targetDeviceId = await sessionManager.getActiveDeviceAsync(sessionId);
    }

    // 2. If still not found, check email's active device in cluster
    if (!targetDeviceId && currentEmail) {
      targetDeviceId = await sessionManager.getActiveDeviceForEmailAsync(currentEmail);
    }

    // 3. If still not found, check primary device for authenticated email
    if (!targetDeviceId && currentEmail) {
      const emailDevices = await deviceRegistry.getDevicesForEmailAsync(currentEmail);
      if (emailDevices.length > 0) {
        targetDeviceId = emailDevices[0].deviceId;
        await sessionManager.setActiveDeviceAsync(sessionId, targetDeviceId, currentEmail);
      }
    }

    // 4. If still not found and user has exactly one device, auto-select it
    if (!targetDeviceId) {
      const userDevices = await deviceRegistry.listDevicesAsync(currentUserId);
      if (userDevices.length === 1) {
        targetDeviceId = userDevices[0].deviceId;
        await sessionManager.setActiveDeviceAsync(sessionId, targetDeviceId, currentEmail);
      }
    }

    if (!targetDeviceId) {
      throw new Error(
        `No active Android device selected for tool '${toolName}'. Please call 'android_list_devices' to view available devices, or 'android_authenticate' to link your Gmail account.`
      );
    }

    // Verify multi-tenant device ownership
    const emailDevices = currentEmail ? await deviceRegistry.getDevicesForEmailAsync(currentEmail) : [];
    const isOwner =
      authManager.verifyDeviceOwnership(currentUserId, targetDeviceId) ||
      emailDevices.some(d => d.deviceId === targetDeviceId);

    if (!isOwner && currentUserId !== 'user_anonymous' && currentUserId !== 'user_mcp_primary') {
      throw new Error(`Access denied: Device '${targetDeviceId}' does not belong to user/email '${currentEmail || currentUserId}'`);
    }

    const isOnline = await deviceRegistry.isDeviceOnlineAsync(targetDeviceId);
    if (!isOnline) {
      throw new Error(
        `Device '${targetDeviceId}' is currently OFFLINE or disconnected. Please check that the Android Agent app is open and connected.`
      );
    }

    // ----------------------------------------------------
    // Tool 4: Get Device Status
    // ----------------------------------------------------
    if (toolName === 'android_get_device_status') {
      const device =
        deviceRegistry.getDevice(targetDeviceId) ||
        emailDevices.find(d => d.deviceId === targetDeviceId) || {
          deviceId: targetDeviceId,
          state: 'ONLINE',
          email: currentEmail,
          connectedAt: Date.now(),
          lastSeenAt: Date.now(),
          metadata: {},
        };
      return {
        content: [
          {
            type: 'text',
            text: JSON.stringify(
              {
                success: true,
                deviceId: device.deviceId,
                status: device.state,
                email: device.email,
                connectedAt: new Date(device.connectedAt).toISOString(),
                lastSeenAt: new Date(device.lastSeenAt).toISOString(),
                metadata: device.metadata,
                isSessionActiveDevice: (await sessionManager.getActiveDeviceAsync(sessionId)) === targetDeviceId,
              },
              null,
              2
            ),
          },
        ],
      };
    }

    // ----------------------------------------------------
    // Tool 5: Open App (by friendly name or package)
    // ----------------------------------------------------
    if (toolName === 'android_open_app') {
      const appName = ((args.app_name as string) || '').toLowerCase().trim();
      const explicitPackage = args.package_name as string | undefined;

      const packageName =
        explicitPackage ||
        COMMON_APP_PACKAGES[appName] ||
        (appName.includes('.') ? appName : null);

      if (!packageName) {
        throw new Error(
          `Unable to resolve package for '${appName}'. Please supply the full Android package_name (e.g. 'com.google.android.youtube').`
        );
      }

      const actionParams = {
        action: 'open_app',
        package_name: packageName,
      };

      const result = await jobManager.submitJob(
        currentUserId,
        targetDeviceId,
        'execute_action',
        actionParams,
        20000
      );

      return {
        content: [
          {
            type: 'text',
            text: JSON.stringify(
              {
                success: true,
                message: `Launched ${appName} (${packageName}) on device '${targetDeviceId}'`,
                deviceId: targetDeviceId,
                packageName,
                result,
              },
              null,
              2
            ),
          },
        ],
      };
    }

    // ----------------------------------------------------
    // Tool 6: Press Key (Back, Home, Recents, Enter, etc.)
    // ----------------------------------------------------
    if (toolName === 'android_press_key') {
      const key = ((args.key as string) || 'BACK').toUpperCase().trim();
      const actionParams = {
        action: 'press_key',
        key_code: key,
      };

      const result = await jobManager.submitJob(
        currentUserId,
        targetDeviceId,
        'execute_action',
        actionParams,
        15000
      );

      return {
        content: [
          {
            type: 'text',
            text: JSON.stringify(
              {
                success: true,
                message: `Pressed key '${key}' on device '${targetDeviceId}'`,
                deviceId: targetDeviceId,
                key,
                result,
              },
              null,
              2
            ),
          },
        ],
      };
    }

    // ----------------------------------------------------
    // Tool 7, 8, 9, 10: Screen, Screenshot, Action, Goal
    // ----------------------------------------------------
    let action = '';
    let timeoutMs = 30000;

    switch (toolName) {
      case 'android_get_screen':
        action = 'get_screen';
        timeoutMs = 15000;
        break;
      case 'android_take_screenshot':
        action = 'take_screenshot';
        timeoutMs = 20000;
        break;
      case 'android_execute_action':
        action = 'execute_action';
        timeoutMs = 30000;
        break;
      case 'android_run_goal':
        action = 'run_goal';
        timeoutMs = 180000;
        break;
      case 'network_start_monitor':
      case 'network_stop_monitor':
      case 'network_get_events':
      case 'network_get_connections':
      case 'network_assert_traffic':
        action = toolName;
        timeoutMs = 30000;
        break;
      default:
        throw new Error(`Unsupported tool: ${toolName}`);
    }

    const result = await jobManager.submitJob(currentUserId, targetDeviceId, action, args, timeoutMs);

    // Multimodal image content block formatting
    const resObj = result as Record<string, unknown> | null;
    if (resObj && typeof resObj.screenshotBase64 === 'string') {
      const base64Data = resObj.screenshotBase64;
      return {
        content: [
          {
            type: 'image',
            data: base64Data,
            mimeType: 'image/jpeg',
          },
          {
            type: 'text',
            text: JSON.stringify({
              success: true,
              deviceId: targetDeviceId,
              width: resObj.width,
              height: resObj.height,
              timestamp: resObj.timestamp,
            }),
          },
        ],
      };
    }

    return {
      content: [
        {
          type: 'text',
          text: typeof result === 'string' ? result : JSON.stringify(result, null, 2),
        },
      ],
    };
  }
}

export const mcpServer = new McpServer();
