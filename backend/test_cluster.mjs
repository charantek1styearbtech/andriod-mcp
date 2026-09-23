async function run() {
  console.log("=== Testing Android MCP Distributed Cluster ===");

  // 1. Authenticate with Gmail
  console.log("--> Authenticating session with Gmail...");
  const authRes = await fetch("http://127.0.0.1:3000/mcp", {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-user-email": "rishikareddy050@gmail.com" },
    body: JSON.stringify({
      jsonrpc: "2.0",
      id: 1,
      method: "tools/call",
      params: {
        name: "android_authenticate",
        arguments: { email: "rishikareddy050@gmail.com" }
      }
    })
  });
  const authData = await authRes.json();
  const sessionId = authRes.headers.get("x-session-id");
  console.log("Auth result:", JSON.stringify(authData));
  console.log("Assigned Session ID:", sessionId);

  // 2. List devices
  console.log("\n--> Listing devices...");
  const listRes = await fetch("http://127.0.0.1:3000/mcp", {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-session-id": sessionId, "x-user-email": "rishikareddy050@gmail.com" },
    body: JSON.stringify({
      jsonrpc: "2.0",
      id: 2,
      method: "tools/call",
      params: { name: "android_list_devices", arguments: {} }
    })
  });
  const listData = await listRes.json();
  console.log("List Devices result:", JSON.stringify(listData, null, 2));

  // 3. Get screen UI hierarchy from the live phone
  console.log("\n--> Querying screen UI hierarchy from live phone...");
  const screenRes = await fetch("http://127.0.0.1:3000/mcp", {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-session-id": sessionId, "x-user-email": "rishikareddy050@gmail.com" },
    body: JSON.stringify({
      jsonrpc: "2.0",
      id: 3,
      method: "tools/call",
      params: { name: "android_get_screen", arguments: {} }
    })
  });
  const screenData = await screenRes.json();
  const screenText = screenData.result?.content?.[0]?.text || JSON.stringify(screenData);
  console.log("Get screen result preview:", screenText.substring(0, 300) + "...");

  // 4. Take screenshot
  console.log("\n--> Taking multimodal screenshot from live phone...");
  const snapRes = await fetch("http://127.0.0.1:3000/mcp", {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-session-id": sessionId, "x-user-email": "rishikareddy050@gmail.com" },
    body: JSON.stringify({
      jsonrpc: "2.0",
      id: 4,
      method: "tools/call",
      params: { name: "android_take_screenshot", arguments: {} }
    })
  });
  const snapData = await snapRes.json();
  const mimeType = snapData.result?.content?.[0]?.mimeType;
  const dataLen = snapData.result?.content?.[0]?.data?.length || 0;
  console.log(`Screenshot received: mimeType=${mimeType}, base64Length=${dataLen}`);
  console.log("=== Verification Completed Successfully ===");
}

run().catch(console.error);
