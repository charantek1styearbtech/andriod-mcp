/**
 * Test client simulating Claude / Cursor / ChatGPT interacting with Android MCP Router
 */
async function main() {
  const backendUrl = process.env.BACKEND_URL || 'http://localhost:3000/mcp';
  const apiKey = process.env.MCP_API_KEY || 'mcp-user-secret-key-101';
  const targetDevice = process.env.DEVICE_ID || 'oneplus_nord_4';

  console.log(`\n===========================================================`);
  console.log(`Testing MCP Client -> Backend (${backendUrl})`);
  console.log(`API Key: ${apiKey}`);
  console.log(`Target Device: ${targetDevice}`);
  console.log(`===========================================================\n`);

  async function postMcp(body: any) {
    const res = await fetch(backendUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${apiKey}`,
      },
      body: JSON.stringify(body),
    });
    return await res.json();
  }

  // 1. Initialize
  console.log('1. Testing initialize...');
  const initRes = await postMcp({
    jsonrpc: '2.0',
    id: 1,
    method: 'initialize',
    params: {},
  });
  console.log('Init Response:', JSON.stringify(initRes, null, 2));

  // 2. List Tools
  console.log('\n2. Testing tools/list...');
  const listRes = await postMcp({
    jsonrpc: '2.0',
    id: 2,
    method: 'tools/list',
    params: {},
  });
  const tools = (listRes.result as any)?.tools || [];
  console.log(`Found ${tools.length} available MCP tools:`, tools.map((t: any) => t.name));

  // 3. List Connected Devices
  console.log('\n3. Testing tools/call: android_list_devices...');
  const devRes = await postMcp({
    jsonrpc: '2.0',
    id: 3,
    method: 'tools/call',
    params: {
      name: 'android_list_devices',
      arguments: {},
    },
  });
  console.log('Devices:', (devRes.result as any)?.content?.[0]?.text);

  // 4. Test Inspect Screen
  console.log(`\n4. Testing tools/call: android_get_screen for ${targetDevice}...`);
  const screenRes = await postMcp({
    jsonrpc: '2.0',
    id: 4,
    method: 'tools/call',
    params: {
      name: 'android_get_screen',
      arguments: {
        device_id: targetDevice,
      },
    },
  });
  console.log('Screen Response Status:', screenRes.error ? `Error: ${screenRes.error.message}` : 'Success!');
  if (screenRes.result) {
    const text = (screenRes.result as any).content?.[0]?.text || '';
    console.log('Screen preview:', text.substring(0, 300) + '...');
  }

  // 5. Test Execute Action
  console.log(`\n5. Testing tools/call: android_execute_action for ${targetDevice}...`);
  const actionRes = await postMcp({
    jsonrpc: '2.0',
    id: 5,
    method: 'tools/call',
    params: {
      name: 'android_execute_action',
      arguments: {
        device_id: targetDevice,
        action: 'wait',
        duration_ms: 1000,
      },
    },
  });
  console.log('Action Response:', JSON.stringify(actionRes, null, 2));
}

main().catch(console.error);
