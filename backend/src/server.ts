import Fastify from 'fastify';
import fastifyCors from '@fastify/cors';
import fastifyWebsocket from '@fastify/websocket';
import type { WebSocket } from 'ws';
import dotenv from 'dotenv';
import { SSEServerTransport } from '@modelcontextprotocol/sdk/server/sse.js';
import { authManager } from './auth/AuthManager.js';
import { deviceRegistry } from './devices/DeviceRegistry.js';
import { jobManager } from './jobs/JobManager.js';
import { mcpServer, JsonRpcRequest } from './mcp/McpServer.js';
import { sessionManager } from './mcp/SessionManager.js';
import { redisClusterManager } from './db/redis.js';
import { mongoDatabase } from './db/mongo.js';
import { DeviceIncomingMessage } from './types/protocol.js';

dotenv.config();

// Connect to distributed cluster services (Redis & MongoDB)
await redisClusterManager.connect();
await mongoDatabase.connect();

const PORT = parseInt(process.env.PORT || '3000', 10);
const HOST = process.env.HOST || '0.0.0.0';

const fastify = Fastify({
  logger: {
    level: process.env.LOG_LEVEL || 'info',
  },
});

// Active Server-Sent Events transports for remote MCP clients
const sseTransports = new Map<string, SSEServerTransport>();

// Register plugins
await fastify.register(fastifyCors, {
  origin: '*',
  methods: ['GET', 'POST', 'PUT', 'DELETE', 'OPTIONS'],
});

await fastify.register(fastifyWebsocket, {
  options: {
    maxPayload: 10 * 1024 * 1024, // 10MB payload limit (for high-res base64 screenshots)
  },
});

// Health check endpoint with cluster metadata
fastify.get('/health', async () => {
  const devices = deviceRegistry.listDevices();
  return {
    status: 'UP',
    instanceId: redisClusterManager.instanceId,
    uptimeSeconds: Math.floor(process.uptime()),
    connectedDevices: devices.length,
    redis: {
      connected: redisClusterManager.isAvailable(),
    },
    mongodb: {
      connected: mongoDatabase.isAvailable(),
    },
    timestamp: new Date().toISOString(),
  };
});

// Interactive Browser Google OAuth Login Endpoint
fastify.get('/auth/google/login', async (req, reply) => {
  const query = req.query as { sessionId?: string };
  const sessionId = query.sessionId || `session_${Math.random().toString(36).substring(2, 10)}`;

  const clientId = process.env.GOOGLE_CLIENT_ID;
  const publicUrl = process.env.PUBLIC_URL || `${req.protocol}://${req.hostname}`;

  // If real Google OAuth credentials are configured, redirect to Google consent screen
  if (clientId && process.env.GOOGLE_CLIENT_SECRET) {
    const callbackUrl = `${publicUrl}/auth/google/callback`;
    const googleAuthUrl = `https://accounts.google.com/o/oauth2/v2/auth?client_id=${encodeURIComponent(
      clientId
    )}&redirect_uri=${encodeURIComponent(callbackUrl)}&response_type=code&scope=openid%20email%20profile&state=${encodeURIComponent(
      sessionId
    )}&prompt=select_account`;
    return reply.redirect(googleAuthUrl);
  }

  // Otherwise, render modern Google Sign-In portal
  const html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Sign in with Google | Android MCP Gateway</title>
  <style>
    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background: #f8f9fa; display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; }
    .card { background: #ffffff; padding: 40px; border-radius: 16px; box-shadow: 0 4px 24px rgba(0,0,0,0.08); max-width: 420px; width: 100%; text-align: center; }
    .google-logo { width: 48px; height: 48px; margin-bottom: 20px; }
    h1 { font-size: 22px; color: #202124; margin-bottom: 8px; }
    p { color: #5f6368; font-size: 14px; margin-bottom: 24px; line-height: 1.5; }
    .input-group { margin-bottom: 20px; text-align: left; }
    label { display: block; font-size: 13px; font-weight: 500; color: #3c4043; margin-bottom: 6px; }
    input[type="email"] { width: 100%; padding: 12px 14px; border: 1px solid #dadce0; border-radius: 8px; font-size: 15px; box-sizing: border-box; outline: none; transition: border-color 0.2s; }
    input[type="email"]:focus { border-color: #1a73e8; }
    .btn-submit { width: 100%; padding: 12px; background: #1a73e8; color: white; border: none; border-radius: 8px; font-size: 15px; font-weight: 500; cursor: pointer; display: flex; justify-content: center; align-items: center; gap: 10px; transition: background 0.2s; }
    .btn-submit:hover { background: #1557b0; }
    .session-badge { display: inline-block; margin-top: 16px; padding: 4px 10px; background: #e8f0fe; color: #1a73e8; border-radius: 20px; font-size: 12px; font-family: monospace; }
  </style>
</head>
<body>
  <div class="card">
    <svg class="google-logo" viewBox="0 0 48 48">
      <path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"/>
      <path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z"/>
      <path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z"/>
      <path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"/>
    </svg>
    <h1>Sign in with Google</h1>
    <p>Authorize your Claude Code / LLM session to access your registered Android devices.</p>
    <form method="GET" action="/auth/google/callback">
      <input type="hidden" name="sessionId" value="${sessionId}">
      <div class="input-group">
        <label for="email">Google Account Email</label>
        <input type="email" id="email" name="email" placeholder="e.g. name@gmail.com" required autofocus>
      </div>
      <button type="submit" class="btn-submit">
        Verify & Authorize Session
      </button>
    </form>
    <div class="session-badge">Session: ${sessionId}</div>
  </div>
</body>
</html>`;

  reply.type('text/html').send(html);
});

// OAuth Callback & Verification Endpoint
fastify.get('/auth/google/callback', async (req, reply) => {
  const query = req.query as { code?: string; state?: string; email?: string; sessionId?: string };
  let email = query.email?.toLowerCase().trim();
  const sessionId = query.state || query.sessionId;

  // Handle Google OAuth authorization code exchange if present
  if (query.code && process.env.GOOGLE_CLIENT_ID && process.env.GOOGLE_CLIENT_SECRET) {
    try {
      const publicUrl = process.env.PUBLIC_URL || `${req.protocol}://${req.hostname}`;
      const tokenRes = await fetch('https://oauth2.googleapis.com/token', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({
          code: query.code,
          client_id: process.env.GOOGLE_CLIENT_ID,
          client_secret: process.env.GOOGLE_CLIENT_SECRET,
          redirect_uri: `${publicUrl}/auth/google/callback`,
          grant_type: 'authorization_code',
        }),
      });
      const tokenData = (await tokenRes.json()) as { access_token?: string; id_token?: string };
      if (tokenData.access_token) {
        const userinfoRes = await fetch('https://www.googleapis.com/oauth2/v2/userinfo', {
          headers: { Authorization: `Bearer ${tokenData.access_token}` },
        });
        const userinfo = (await userinfoRes.json()) as { email?: string };
        if (userinfo.email) {
          email = userinfo.email.toLowerCase().trim();
        }
      }
    } catch (err) {
      console.error('[Google OAuth] Error exchanging code for token:', err);
    }
  }

  if (!email || !sessionId) {
    return reply.status(400).type('text/html').send(`
      <!DOCTYPE html>
      <html><head><title>Authentication Error</title></head>
      <body style="font-family: sans-serif; text-align: center; padding-top: 50px;">
        <h2>Authentication Failed</h2>
        <p>Missing required email or sessionId parameter.</p>
      </body></html>
    `);
  }

  // Link user and session across cluster
  const user = authManager.getOrCreateUserForEmail(email);
  sessionManager.setAuthenticatedUser(sessionId, user.userId, email);

  // Auto-select primary device if available
  const devices = await deviceRegistry.getDevicesForEmailAsync(email);
  let activeDeviceMsg = 'No devices currently online.';
  if (devices.length > 0) {
    await sessionManager.setActiveDeviceAsync(sessionId, devices[0].deviceId, email);
    activeDeviceMsg = `Active device set to: <strong>${devices[0].deviceId}</strong> (${devices[0].metadata?.model || 'Android'}).`;
  }

  const successHtml = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Authentication Successful</title>
  <style>
    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background: #f8f9fa; display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; }
    .card { background: #ffffff; padding: 40px; border-radius: 16px; box-shadow: 0 4px 24px rgba(0,0,0,0.08); max-width: 440px; width: 100%; text-align: center; }
    .check-circle { width: 64px; height: 64px; border-radius: 50%; background: #e6f4ea; color: #137333; display: flex; align-items: center; justify-content: center; font-size: 32px; margin: 0 auto 20px; font-weight: bold; }
    h1 { font-size: 22px; color: #202124; margin-bottom: 8px; }
    p { color: #5f6368; font-size: 14px; line-height: 1.5; margin-bottom: 16px; }
    .user-pill { display: inline-block; background: #f1f3f4; padding: 8px 16px; border-radius: 20px; font-weight: 500; color: #202124; font-size: 14px; margin-bottom: 20px; }
    .device-info { font-size: 13px; color: #137333; margin-bottom: 24px; background: #f6fbf7; padding: 10px; border-radius: 8px; }
    .success-badge { background: #e8f0fe; color: #1a73e8; padding: 12px; border-radius: 8px; font-size: 14px; font-weight: 500; }
  </style>
</head>
<body>
  <div class="card">
    <div class="check-circle">✓</div>
    <h1>Authentication Successful!</h1>
    <p>Your Google account has been verified and linked to your active MCP session.</p>
    <div class="user-pill">${email}</div>
    <div class="device-info">${activeDeviceMsg}</div>
    <div class="success-badge">
      ✓ You can close this browser tab and return to Claude Code.
    </div>
  </div>
</body>
</html>`;

  reply.type('text/html').send(successHtml);
});

// Standard MCP Streamable HTTP endpoint for AI Clients (Claude, Cursor, ChatGPT)
fastify.post('/mcp', async (req, reply) => {
  // Extract Bearer token or API key or user email
  const authHeader = (req.headers['authorization'] as string) || '';
  const apiKeyHeader = (req.headers['x-api-key'] as string) || '';
  const emailHeader = (req.headers['x-user-email'] as string) || (req.query as any)?.email;

  let apiKey = '';
  if (authHeader.startsWith('Bearer ')) {
    apiKey = authHeader.substring(7).trim();
  } else if (apiKeyHeader) {
    apiKey = apiKeyHeader.trim();
  }

  let user = authManager.authenticateUser(apiKey);

  // If email is provided directly or in dev mode
  if (!user && emailHeader) {
    user = authManager.getOrCreateUserForEmail(emailHeader);
  }

  // Fallback to default user if no auth header in local development mode
  if (!user && process.env.ALLOW_ANONYMOUS_DEV === 'true') {
    apiKey = process.env.MCP_API_KEY || 'mcp-user-secret-key-101';
    user = authManager.authenticateUser(apiKey);
  }

  if (!user) {
    return reply.status(401).send({
      jsonrpc: '2.0',
      id: null,
      error: {
        code: -32000,
        message: 'Unauthorized: Provide Authorization: Bearer <API_KEY> or x-user-email: <GMAIL>',
      },
    });
  }

  const body = req.body as JsonRpcRequest;
  if (!body || !body.method) {
    return reply.status(400).send({
      jsonrpc: '2.0',
      id: null,
      error: { code: -32600, message: 'Invalid Request' },
    });
  }

  const effectiveEmail = emailHeader || user.email;
  const sessionHeader = (req.headers['mcp-session-id'] as string) || (req.query as any)?.sessionId;
  const sessionId = sessionHeader || `http_${user.userId}_${effectiveEmail || 'default'}`;
  const response = await mcpServer.handleJsonRpc(user.userId, body, effectiveEmail, sessionId);
  return reply.send(response);
});

// Standard MCP Server-Sent Events (SSE) Transport for Claude Desktop, Cursor, and ChatGPT
fastify.get('/sse', async (req, reply) => {
  const authHeader = (req.headers['authorization'] as string) || '';
  const apiKeyHeader = (req.headers['x-api-key'] as string) || '';
  const emailParam = (req.query as any)?.email;
  const emailHeader = (req.headers['x-user-email'] as string) || emailParam;
  const apiKeyParam = (req.query as any)?.apiKey;

  let apiKey = '';
  if (authHeader.startsWith('Bearer ')) {
    apiKey = authHeader.substring(7).trim();
  } else if (apiKeyHeader) {
    apiKey = apiKeyHeader.trim();
  } else if (apiKeyParam) {
    apiKey = apiKeyParam.trim();
  }

  let user = authManager.authenticateUser(apiKey);
  if (!user && emailHeader) {
    user = authManager.getOrCreateUserForEmail(emailHeader);
  }
  if (!user && (process.env.ALLOW_ANONYMOUS_DEV === 'true' || !apiKey)) {
    user = authManager.authenticateUser(process.env.MCP_API_KEY || 'mcp-user-secret-key-101');
  }

  if (!user) {
    return reply.status(401).send({ error: 'Unauthorized: Provide apiKey or email parameter' });
  }

  reply.hijack();
  const transport = new SSEServerTransport('/messages', reply.raw);
  const sessionId = transport.sessionId;
  sseTransports.set(sessionId, transport);

  const effectiveEmail = emailHeader || user.email;
  sessionManager.getOrCreateSession(sessionId, user.userId, effectiveEmail);

  transport.onclose = () => {
    sseTransports.delete(sessionId);
    sessionManager.removeSession(sessionId);
  };

  transport.onmessage = async (rpcMsg) => {
    try {
      const response = await mcpServer.handleJsonRpc(
        user!.userId,
        rpcMsg as JsonRpcRequest,
        effectiveEmail,
        sessionId
      );
      if (response) {
        await transport.send(response as any);
      }
    } catch (err) {
      console.error(`[SSE] Error handling message for session ${sessionId}:`, err);
    }
  };

  await transport.start();
});

fastify.post('/messages', async (req, reply) => {
  const sessionId = (req.query as any)?.sessionId;
  if (!sessionId) {
    return reply.status(400).send({ error: "Missing required query parameter 'sessionId'" });
  }

  const transport = sseTransports.get(sessionId);
  if (!transport) {
    return reply.status(404).send({ error: `SSE session '${sessionId}' not found or has expired` });
  }

  await transport.handlePostMessage(req.raw, reply.raw, req.body);
});

// Direct HTTPS Forwarding API: Forward instructions to Android device via Gmail or Device ID
fastify.post('/api/device/forward', async (req, reply) => {
  const body = req.body as {
    email?: string;
    deviceId?: string;
    action?: string;
    instruction?: string;
    params?: Record<string, unknown>;
    timeoutMs?: number;
  };

  if (!body || (!body.email && !body.deviceId)) {
    return reply.status(400).send({
      success: false,
      error: "Missing required parameter 'email' or 'deviceId'",
    });
  }

  const email = body.email ? body.email.toLowerCase().trim() : undefined;
  let targetDeviceId = body.deviceId;

  // Resolve device by Gmail
  if (!targetDeviceId && email) {
    const emailDevices = await deviceRegistry.getDevicesForEmailAsync(email);
    if (emailDevices.length === 0) {
      return reply.status(404).send({
        success: false,
        error: `No online device found for Gmail: ${email}. Please ensure device app is connected to gateway.`,
      });
    }
    targetDeviceId = emailDevices[0].deviceId;
  }

  const isOnline = targetDeviceId ? await deviceRegistry.isDeviceOnlineAsync(targetDeviceId) : false;
  if (!targetDeviceId || !isOnline) {
    return reply.status(503).send({
      success: false,
      error: `Target device '${targetDeviceId}' is currently OFFLINE or disconnected.`,
    });
  }

  const actionName = body.action || (body.instruction ? 'run_goal' : 'execute_action');
  const actionParams: Record<string, unknown> = { ...(body.params || {}) };
  if (body.instruction) {
    actionParams.goal = body.instruction;
  }
  if (body.action && !actionParams.action) {
    actionParams.action = body.action;
  }

  const user = email ? authManager.getOrCreateUserForEmail(email) : authManager.authenticateUser('mcp-user-secret-key-101')!;

  try {
    const result = await jobManager.submitJob(
      user.userId,
      targetDeviceId,
      actionName,
      actionParams,
      body.timeoutMs || 30000
    );

    return {
      success: true,
      deviceId: targetDeviceId,
      email: email || deviceRegistry.getDevice(targetDeviceId)?.email,
      action: actionName,
      result,
    };
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : String(err);
    return reply.status(500).send({
      success: false,
      deviceId: targetDeviceId,
      error: msg,
    });
  }
});

// Direct HTTPS endpoint to get screen UI tree by Gmail or Device ID
fastify.get('/api/device/screen', async (req, reply) => {
  const query = req.query as { email?: string; deviceId?: string };
  const email = query.email ? query.email.toLowerCase().trim() : undefined;
  let targetDeviceId = query.deviceId;

  if (!targetDeviceId && email) {
    const dev = deviceRegistry.getPrimaryDeviceForEmail(email);
    if (dev) targetDeviceId = dev.deviceId;
  }

  if (!targetDeviceId || !deviceRegistry.isOnline(targetDeviceId)) {
    return reply.status(404).send({
      success: false,
      error: `Device not found or offline for ${email ? `Gmail: ${email}` : `deviceId: ${targetDeviceId}`}`,
    });
  }

  const user = email ? authManager.getOrCreateUserForEmail(email) : authManager.authenticateUser('mcp-user-secret-key-101')!;

  try {
    const result = await jobManager.submitJob(user.userId, targetDeviceId, 'get_screen', {}, 15000);
    return {
      success: true,
      deviceId: targetDeviceId,
      result,
    };
  } catch (err: unknown) {
    return reply.status(500).send({
      success: false,
      error: err instanceof Error ? err.message : String(err),
    });
  }
});

// Direct HTTPS endpoint to capture screenshot by Gmail or Device ID
fastify.get('/api/device/screenshot', async (req, reply) => {
  const query = req.query as { email?: string; deviceId?: string };
  const email = query.email ? query.email.toLowerCase().trim() : undefined;
  let targetDeviceId = query.deviceId;

  if (!targetDeviceId && email) {
    const dev = deviceRegistry.getPrimaryDeviceForEmail(email);
    if (dev) targetDeviceId = dev.deviceId;
  }

  if (!targetDeviceId || !deviceRegistry.isOnline(targetDeviceId)) {
    return reply.status(404).send({
      success: false,
      error: `Device not found or offline for ${email ? `Gmail: ${email}` : `deviceId: ${targetDeviceId}`}`,
    });
  }

  const user = email ? authManager.getOrCreateUserForEmail(email) : authManager.authenticateUser('mcp-user-secret-key-101')!;

  try {
    const result = (await jobManager.submitJob(user.userId, targetDeviceId, 'take_screenshot', {}, 20000)) as any;
    if (result && result.screenshotBase64) {
      const buffer = Buffer.from(result.screenshotBase64, 'base64');
      reply.header('Content-Type', 'image/jpeg');
      return reply.send(buffer);
    }
    return {
      success: true,
      deviceId: targetDeviceId,
      result,
    };
  } catch (err: unknown) {
    return reply.status(500).send({
      success: false,
      error: err instanceof Error ? err.message : String(err),
    });
  }
});

// REST endpoint to list connected devices
fastify.get('/api/devices', async (req, reply) => {
  const authHeader = (req.headers['authorization'] as string) || '';
  const emailHeader = (req.headers['x-user-email'] as string) || (req.query as any)?.email;
  const apiKey = authHeader.replace(/^Bearer\s+/, '').trim() || process.env.MCP_API_KEY || 'mcp-user-secret-key-101';

  let user = authManager.authenticateUser(apiKey);
  if (!user && emailHeader) {
    user = authManager.getOrCreateUserForEmail(emailHeader);
  }

  if (!user) {
    return reply.status(401).send({ error: 'Unauthorized' });
  }

  const devices = emailHeader
    ? deviceRegistry.getDevicesForEmail(emailHeader)
    : deviceRegistry.listDevices(user.userId);

  return {
    success: true,
    count: devices.length,
    devices,
  };
});

// Endpoint to generate instant LLM configuration snippets (Claude Desktop, Cursor, Claude Code)
fastify.get('/api/mcp/config', async (req, reply) => {
  const email = ((req.query as any)?.email as string) || 'rishikareddy050@gmail.com';
  const apiKey = ((req.query as any)?.apiKey as string) || 'mcp-user-secret-key-101';
  const host = req.headers.host || `127.0.0.1:${PORT}`;
  const protocol = req.protocol || 'http';
  const baseUrl = `${protocol}://${host}`;

  return {
    success: true,
    server: {
      name: 'Android Remote MCP Router',
      version: '2.0.0',
      baseUrl,
      sseEndpoint: `${baseUrl}/sse?email=${encodeURIComponent(email)}`,
      httpEndpoint: `${baseUrl}/mcp?email=${encodeURIComponent(email)}`,
      email,
    },
    claude_desktop: {
      mcpServers: {
        android: {
          command: 'npx',
          args: [
            '-y',
            'mcp-remote-client',
            '--url',
            `${baseUrl}/sse?email=${encodeURIComponent(email)}`,
          ],
        },
      },
    },
    cursor: {
      mcpServers: {
        android: {
          url: `${baseUrl}/sse?email=${encodeURIComponent(email)}`,
        },
      },
    },
    claude_code: {
      command: `claude mcp add android -- ${baseUrl}/sse?email=${encodeURIComponent(email)}`,
    },
  };
});

// Outbound WebSocket Gateway for physical Android Devices
fastify.register(async function (fastifyInstance) {
  fastifyInstance.get('/device/ws', { websocket: true }, (connection: any, req) => {
    const socket: WebSocket = connection.socket || connection;
    let authenticatedDeviceId: string | null = null;
    let authenticatedUserId: string | null = null;
    let authenticatedEmail: string | null = null;

    console.log(`[WebSocket] New device incoming connection from ${req.ip}`);

    // Wait for AUTH handshake within 10 seconds, else terminate
    const authTimeout = setTimeout(() => {
      if (!authenticatedDeviceId) {
        console.warn(`[WebSocket] Device auth timeout from ${req.ip}. Closing socket.`);
        socket.close(4001, 'Authentication timeout');
      }
    }, 10000);

    socket.on('message', (raw: Buffer) => {
      try {
        const text = raw.toString('utf-8');
        const msg = JSON.parse(text) as DeviceIncomingMessage;

        // 1. Handle AUTH handshake
        if (msg.type === 'AUTH') {
          clearTimeout(authTimeout);
          const { deviceId, token, metadata, email } = msg;

          const authResult = authManager.authenticateDevice(deviceId, token, email);
          if (!authResult) {
            console.warn(`[WebSocket] Device auth rejected for deviceId: ${deviceId}`);
            socket.send(
              JSON.stringify({
                type: 'AUTH_ACK',
                success: false,
                error: 'Invalid device credentials',
              })
            );
            socket.close(4003, 'Invalid device credentials');
            return;
          }

          authenticatedDeviceId = deviceId;
          authenticatedUserId = authResult.userId;
          authenticatedEmail = email ? email.toLowerCase().trim() : null;

          deviceRegistry.register(deviceId, authResult.userId, socket, metadata, email);

          socket.send(
            JSON.stringify({
              type: 'AUTH_ACK',
              success: true,
              deviceId,
              userId: authResult.userId,
              email: authenticatedEmail,
            })
          );
          return;
        }

        // Require authentication for all other messages
        if (!authenticatedDeviceId) {
          console.warn(`[WebSocket] Message rejected: socket not authenticated yet.`);
          return;
        }

        // 2. Handle Heartbeat PONG
        if (msg.type === 'PONG') {
          deviceRegistry.updateHeartbeat(authenticatedDeviceId);
          return;
        }

        // 3. Handle Action ACK
        if (msg.type === 'ACTION_ACK') {
          jobManager.handleActionAck(msg.requestId, msg.status);
          return;
        }

        // 4. Handle Action Result
        if (msg.type === 'ACTION_RESULT') {
          jobManager.handleActionResult(msg.requestId, msg.success, msg.result, msg.error);
          return;
        }

        // 5. Handle Device Status update
        if (msg.type === 'DEVICE_STATUS') {
          deviceRegistry.setDeviceState(authenticatedDeviceId, msg.state);
          return;
        }
      } catch (err) {
        console.error('[WebSocket] Failed to parse device message:', err);
      }
    });

    socket.on('close', (code, reason) => {
      clearTimeout(authTimeout);
      if (authenticatedDeviceId) {
        deviceRegistry.unregister(authenticatedDeviceId);
      }
      console.log(`[WebSocket] Socket closed for device ${authenticatedDeviceId || req.ip} (code: ${code}, reason: ${reason})`);
    });

    socket.on('error', (err) => {
      console.error(`[WebSocket] Socket error for device ${authenticatedDeviceId || req.ip}:`, err);
    });
  });
});

// Start server
const start = async () => {
  try {
    await fastify.listen({ port: PORT, host: HOST });
    console.log(`\n======================================================`);
    console.log(`🚀 Multi-Tenant Android MCP Router running on port ${PORT}`);
    console.log(`   - MCP HTTP endpoint:    http://${HOST}:${PORT}/mcp`);
    console.log(`   - Device WebSocket:     ws://${HOST}:${PORT}/device/ws`);
    console.log(`   - Health check:         http://${HOST}:${PORT}/health`);
    console.log(`======================================================\n`);
  } catch (err) {
    fastify.log.error(err);
    process.exit(1);
  }
};

start();
