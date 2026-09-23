import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const TOKEN = process.env.GITHUB_TOKEN || process.env.GH_TOKEN;
if (!TOKEN) {
  console.error('Error: GITHUB_TOKEN or GH_TOKEN environment variable is required.');
  process.exit(1);
}
const REPO = 'charantek1styearbtech/andriod-mcp';
const TAG = 'v1.1.0';
const RELEASE_NAME = 'Android MCP v1.1.0 (Build 2)';
const BODY = 'OTA Update: Added in-app auto updates, robust gateway URL resolution, fallback update check, and strict Google OAuth.';
const APK_PATH = path.resolve(__dirname, '../../app/build/outputs/apk/debug/app-debug.apk');

async function main() {
  if (!fs.existsSync(APK_PATH)) {
    console.error(`APK not found at: ${APK_PATH}`);
    process.exit(1);
  }

  const apkStats = fs.statSync(APK_PATH);
  console.log(`[GitHub Release] Preparing release ${TAG} with APK (${(apkStats.size / (1024 * 1024)).toFixed(2)} MB)...`);

  // 1. Check if release already exists
  let releaseId = null;
  let uploadUrl = null;

  const getRes = await fetch(`https://api.github.com/repos/${REPO}/releases/tags/${TAG}`, {
    headers: {
      Authorization: `token ${TOKEN}`,
      'User-Agent': 'Node-Release-Uploader',
      Accept: 'application/vnd.github.v3+json',
    },
  });

  if (getRes.ok) {
    const existing = await getRes.json();
    releaseId = existing.id;
    uploadUrl = existing.upload_url;
    console.log(`[GitHub Release] Release ${TAG} already exists (id: ${releaseId}). Checking assets...`);

    // Check if asset app-debug.apk already exists and delete it to re-upload
    for (const asset of existing.assets || []) {
      if (asset.name === 'app-debug.apk') {
        console.log(`[GitHub Release] Deleting old asset ${asset.id}...`);
        await fetch(`https://api.github.com/repos/${REPO}/releases/assets/${asset.id}`, {
          method: 'DELETE',
          headers: {
            Authorization: `token ${TOKEN}`,
            'User-Agent': 'Node-Release-Uploader',
          },
        });
      }
    }
  } else {
    // 2. Create release
    console.log(`[GitHub Release] Creating new release ${TAG}...`);
    const createRes = await fetch(`https://api.github.com/repos/${REPO}/releases`, {
      method: 'POST',
      headers: {
        Authorization: `token ${TOKEN}`,
        'User-Agent': 'Node-Release-Uploader',
        Accept: 'application/vnd.github.v3+json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        tag_name: TAG,
        name: RELEASE_NAME,
        body: BODY,
        draft: false,
        prerelease: false,
      }),
    });

    if (!createRes.ok) {
      const errText = await createRes.text();
      console.error(`[GitHub Release] Failed to create release:`, errText);
      process.exit(1);
    }

    const created = await createRes.json();
    releaseId = created.id;
    uploadUrl = created.upload_url;
    console.log(`[GitHub Release] Created release ${TAG} (id: ${releaseId})`);
  }

  // 3. Upload APK binary asset
  const targetUploadUrl = uploadUrl.replace(/\{.*?\}$/, '') + '?name=app-debug.apk';
  console.log(`[GitHub Release] Uploading asset to: ${targetUploadUrl}`);

  const apkBuffer = fs.readFileSync(APK_PATH);
  const uploadRes = await fetch(targetUploadUrl, {
    method: 'POST',
    headers: {
      Authorization: `token ${TOKEN}`,
      'User-Agent': 'Node-Release-Uploader',
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Length': String(apkStats.size),
    },
    body: apkBuffer,
  });

  if (!uploadRes.ok) {
    const errText = await uploadRes.text();
    console.error(`[GitHub Release] Asset upload failed:`, errText);
    process.exit(1);
  }

  const assetInfo = await uploadRes.json();
  console.log(`[GitHub Release] Successfully uploaded app-debug.apk!`);
  console.log(`[GitHub Release] Browser download URL: ${assetInfo.browser_download_url}`);
}

main().catch((err) => {
  console.error('[GitHub Release] Fatal error:', err);
  process.exit(1);
});
