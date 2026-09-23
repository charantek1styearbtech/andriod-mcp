#!/usr/bin/env node
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const GATEWAY_URL = process.env.GATEWAY_URL || 'https://andriod-mcp-gateway.onrender.com';
const API_KEY = process.env.MCP_API_KEY || 'mcp-user-secret-key-101';

async function main() {
  console.log('\n======================================================');
  console.log('🚀 Android MCP 1-Click APK Publisher (Developer End)');
  console.log(`Target Gateway: ${GATEWAY_URL}`);
  console.log('======================================================\n');

  // 1. Locate APK file
  const rootDir = path.resolve(__dirname, '../../');
  const debugApkPath = path.join(rootDir, 'app/build/outputs/apk/debug/app-debug.apk');
  const releaseApkPath = path.join(rootDir, 'app/build/outputs/apk/release/app-release.apk');

  let apkPath = fs.existsSync(debugApkPath) ? debugApkPath : (fs.existsSync(releaseApkPath) ? releaseApkPath : null);

  if (!apkPath) {
    console.error('❌ Error: Could not find built APK at app/build/outputs/apk/debug/app-debug.apk');
    console.log('Run `./gradlew assembleDebug` first to build the APK.');
    process.exit(1);
  }

  // 2. Extract version from build.gradle.kts
  const gradlePath = path.join(rootDir, 'app/build.gradle.kts');
  let versionCode = 2;
  let versionName = '1.1.0';

  if (fs.existsSync(gradlePath)) {
    const gradleContent = fs.readFileSync(gradlePath, 'utf-8');
    const codeMatch = gradleContent.match(/versionCode\s*=\s*(\d+)/);
    const nameMatch = gradleContent.match(/versionName\s*=\s*"([^"]+)"/);
    if (codeMatch) versionCode = parseInt(codeMatch[1], 10);
    if (nameMatch) versionName = nameMatch[1];
  }

  // Parse command-line args for custom changelog or version
  const args = process.argv.slice(2);
  let changelog = 'OTA Auto-update: Performance improvements and UI optimizations.';
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--notes' || args[i] === '-m') {
      changelog = args[i + 1] || changelog;
      i++;
    } else if (args[i] === '--code') {
      versionCode = parseInt(args[i + 1] || String(versionCode), 10);
      i++;
    } else if (args[i] === '--version') {
      versionName = args[i + 1] || versionName;
      i++;
    }
  }

  const apkStats = fs.statSync(apkPath);
  const sizeMb = (apkStats.size / (1024 * 1024)).toFixed(2);
  console.log(`📦 Found APK: ${apkPath}`);
  console.log(`📊 Size: ${sizeMb} MB (${apkStats.size} bytes)`);
  console.log(`🏷️  Target Version: v${versionName} (Build ${versionCode})`);
  console.log(`📝 Notes: "${changelog}"\n`);

  console.log(`⏳ Uploading APK to ${GATEWAY_URL}/api/update/publish ...`);

  const apkBuffer = fs.readFileSync(apkPath);

  // Upload using multipart/form-data
  const boundary = '----WebKitFormBoundary' + Math.random().toString(36).substring(2);
  const prefix = Buffer.from(
    `--${boundary}\r\n` +
    `Content-Disposition: form-data; name="versionCode"\r\n\r\n${versionCode}\r\n` +
    `--${boundary}\r\n` +
    `Content-Disposition: form-data; name="versionName"\r\n\r\n${versionName}\r\n` +
    `--${boundary}\r\n` +
    `Content-Disposition: form-data; name="changelog"\r\n\r\n${changelog}\r\n` +
    `--${boundary}\r\n` +
    `Content-Disposition: form-data; name="apiKey"\r\n\r\n${API_KEY}\r\n` +
    `--${boundary}\r\n` +
    `Content-Disposition: form-data; name="apk"; filename="app-debug.apk"\r\n` +
    `Content-Type: application/vnd.android.package-archive\r\n\r\n`
  );
  const suffix = Buffer.from(`\r\n--${boundary}--\r\n`);
  const fullBody = Buffer.concat([prefix, apkBuffer, suffix]);

  const res = await fetch(`${GATEWAY_URL}/api/update/publish`, {
    method: 'POST',
    headers: {
      'Content-Type': `multipart/form-data; boundary=${boundary}`,
      'Authorization': `Bearer ${API_KEY}`,
    },
    body: fullBody,
  });

  if (!res.ok) {
    const errText = await res.text();
    console.error(`\n❌ Failed to publish update (HTTP ${res.status}): ${errText}`);
    process.exit(1);
  }

  const data = await res.json();
  console.log('\n======================================================');
  console.log('✅ Update Published Successfully to Cloud Fleet!');
  console.log(`Version:       v${data.metadata.versionName} (Build ${data.metadata.versionCode})`);
  console.log(`File Size:     ${(data.metadata.apkSize / (1024 * 1024)).toFixed(2)} MB`);
  console.log(`SHA256:        ${data.metadata.apkSha256}`);
  console.log(`Published At:  ${data.metadata.publishedAt}`);
  console.log(`Download URL:  ${GATEWAY_URL}/api/update/download`);
  console.log('======================================================\n');
  console.log('📲 User devices will now detect this update when clicking "Auto Update" in the app.');
}

main().catch((err) => {
  console.error('Fatal error:', err);
  process.exit(1);
});
