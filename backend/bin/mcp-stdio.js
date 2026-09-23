#!/usr/bin/env node
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';

const GATEWAY_URL = process.env.GATEWAY_URL || 'https://andriod-mcp-gateway.onrender.com/mcp';
const USER_EMAIL = process.env.USER_EMAIL || 'reddypavitra687@gmail.com';
const API_KEY = process.env.MCP_API_KEY || 'mcp-user-secret-key-101';

console.error(`[Android MCP Bridge] Starting Stdio Bridge for ${USER_EMAIL} -> ${GATEWAY_URL}`);

const transport = new StdioServerTransport();

transport.onmessage = async (msg) => {
  try {
    const isNotification = msg.id === undefined || msg.id === null;
    const url = new URL(GATEWAY_URL);
    if (USER_EMAIL) {
      url.searchParams.set('email', USER_EMAIL);
    }

    const response = await fetch(url.toString(), {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-user-email': USER_EMAIL,
        'Authorization': `Bearer ${API_KEY}`,
      },
      body: JSON.stringify(msg),
    });

    if (!response.ok) {
      const errText = await response.text();
      console.error(`[Android MCP Bridge] HTTP error ${response.status}: ${errText}`);
      if (!isNotification) {
        await transport.send({
          jsonrpc: '2.0',
          id: msg.id,
          error: {
            code: -32000,
            message: `Gateway HTTP ${response.status}: ${errText.substring(0, 200)}`,
          },
        });
      }
      return;
    }

    const json = await response.json();

    // Standard JSON-RPC: notifications MUST NOT be answered
    if (!isNotification && json) {
      await transport.send({
        jsonrpc: '2.0',
        id: msg.id,
        ...(json.result !== undefined ? { result: json.result } : {}),
        ...(json.error !== undefined ? { error: json.error } : {}),
      });
    }
  } catch (err) {
    console.error(`[Android MCP Bridge] Error handling RPC request:`, err);
    if (msg.id !== undefined && msg.id !== null) {
      await transport.send({
        jsonrpc: '2.0',
        id: msg.id,
        error: {
          code: -32603,
          message: `Internal bridge error: ${err instanceof Error ? err.message : String(err)}`,
        },
      });
    }
  }
};

transport.onerror = (err) => {
  console.error(`[Android MCP Bridge] Transport error:`, err);
};

await transport.start();
console.error(`[Android MCP Bridge] Stdio Transport active and ready.`);
