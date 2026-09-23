import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

export interface UpdateMetadata {
  versionCode: number;
  versionName: string;
  changelog: string;
  minRequiredVersion?: number;
  apkSize: number;
  apkSha256: string;
  publishedAt: string;
}

export class UpdateController {
  private uploadsDir: string;
  private metadataPath: string;
  private currentMetadata: UpdateMetadata | null = null;

  constructor() {
    this.uploadsDir = path.resolve(__dirname, '../../uploads');
    this.metadataPath = path.join(this.uploadsDir, 'version.json');
    this.initStorage();
  }

  private initStorage(): void {
    if (!fs.existsSync(this.uploadsDir)) {
      fs.mkdirSync(this.uploadsDir, { recursive: true });
    }

    if (fs.existsSync(this.metadataPath)) {
      try {
        const raw = fs.readFileSync(this.metadataPath, 'utf-8');
        this.currentMetadata = JSON.parse(raw);
        console.log(`[UpdateController] Loaded release v${this.currentMetadata?.versionName} (Build ${this.currentMetadata?.versionCode})`);
      } catch (err) {
        console.warn(`[UpdateController] Failed to parse version.json:`, err);
      }
    } else {
      // Fallback to committed version.json if running in fresh container (e.g. Render)
      const fallbackPath = path.resolve(__dirname, '../../version.json');
      if (fs.existsSync(fallbackPath)) {
        try {
          const raw = fs.readFileSync(fallbackPath, 'utf-8');
          this.currentMetadata = JSON.parse(raw);
          console.log(`[UpdateController] Loaded fallback release v${this.currentMetadata?.versionName} (Build ${this.currentMetadata?.versionCode})`);
        } catch (err) {
          console.warn(`[UpdateController] Failed to parse fallback version.json:`, err);
        }
      }
    }
  }

  public getLatestMetadata(): UpdateMetadata | null {
    return this.currentMetadata;
  }

  public getApkFilePath(): string | null {
    const apkPath = path.join(this.uploadsDir, 'latest.apk');
    return fs.existsSync(apkPath) ? apkPath : null;
  }

  public checkUpdate(currentVersionCode: number, baseUrl: string): {
    updateAvailable: boolean;
    currentVersionCode: number;
    latestVersionCode: number;
    latestVersionName: string;
    downloadUrl: string;
    apkSize: number;
    apkSha256: string;
    changelog: string;
    publishedAt: string;
  } {
    const meta = this.currentMetadata;
    const latestCode = meta ? meta.versionCode : 1;
    const latestName = meta ? meta.versionName : '1.0.0';
    const isUpdateAvailable = meta ? meta.versionCode > currentVersionCode : false;

    return {
      updateAvailable: isUpdateAvailable,
      currentVersionCode,
      latestVersionCode: latestCode,
      latestVersionName: latestName,
      downloadUrl: (meta as any)?.downloadUrl || `${baseUrl}/api/update/download`,
      apkSize: meta?.apkSize || 0,
      apkSha256: meta?.apkSha256 || '',
      changelog: meta?.changelog || 'Performance improvements and bug fixes.',
      publishedAt: meta?.publishedAt || new Date().toISOString(),
    };
  }

  public async publishUpdate(
    apkBuffer: Buffer,
    versionCode: number,
    versionName: string,
    changelog: string
  ): Promise<UpdateMetadata> {
    const apkPath = path.join(this.uploadsDir, 'latest.apk');
    fs.writeFileSync(apkPath, apkBuffer);

    const hash = crypto.createHash('sha256').update(apkBuffer).digest('hex');
    const metadata: UpdateMetadata = {
      versionCode,
      versionName,
      changelog: changelog || 'New version update released.',
      apkSize: apkBuffer.length,
      apkSha256: hash,
      publishedAt: new Date().toISOString(),
    };

    fs.writeFileSync(this.metadataPath, JSON.stringify(metadata, null, 2), 'utf-8');
    this.currentMetadata = metadata;

    console.log(`[UpdateController] Successfully published release v${versionName} (Build ${versionCode}, ${apkBuffer.length} bytes, SHA256: ${hash.substring(0, 10)}...)`);
    return metadata;
  }

  public renderDeveloperPortal(baseUrl: string): string {
    const meta = this.currentMetadata;
    const hasApk = this.getApkFilePath() !== null;

    return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Developer Portal | Android MCP Auto-Update</title>
  <style>
    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background: #0f172a; color: #f8fafc; padding: 40px 20px; margin: 0; }
    .container { max-width: 680px; margin: 0 auto; background: #1e293b; border: 1px solid #334155; border-radius: 16px; padding: 32px; box-shadow: 0 10px 30px rgba(0,0,0,0.5); }
    h1 { font-size: 24px; margin-top: 0; color: #38bdf8; display: flex; align-items: center; gap: 10px; }
    p.subtitle { color: #94a3b8; font-size: 14px; margin-bottom: 24px; }
    .status-card { background: #0f172a; border: 1px solid #334155; border-radius: 12px; padding: 18px; margin-bottom: 28px; }
    .status-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; font-size: 13px; }
    .status-label { color: #64748b; }
    .status-val { color: #f1f5f9; font-weight: 600; }
    .tag { display: inline-block; padding: 3px 8px; border-radius: 6px; font-size: 11px; font-weight: 600; }
    .tag-online { background: rgba(34, 197, 94, 0.2); color: #4ade80; border: 1px solid rgba(34, 197, 94, 0.4); }
    .tag-offline { background: rgba(239, 68, 68, 0.2); color: #f87171; border: 1px solid rgba(239, 68, 68, 0.4); }
    .form-group { margin-bottom: 20px; }
    label { display: block; font-size: 13px; font-weight: 500; color: #cbd5e1; margin-bottom: 6px; }
    input[type="text"], input[type="number"], textarea { width: 100%; padding: 10px 14px; background: #0f172a; border: 1px solid #334155; border-radius: 8px; color: #f8fafc; font-size: 14px; box-sizing: border-box; outline: none; }
    input:focus, textarea:focus { border-color: #38bdf8; }
    .drop-zone { border: 2px dashed #475569; border-radius: 12px; padding: 24px; text-align: center; background: #0f172a; cursor: pointer; transition: border-color 0.2s; }
    .drop-zone:hover { border-color: #38bdf8; }
    .btn-submit { width: 100%; padding: 14px; background: #0284c7; color: white; border: none; border-radius: 8px; font-size: 15px; font-weight: 600; cursor: pointer; transition: background 0.2s; margin-top: 10px; }
    .btn-submit:hover { background: #0369a1; }
    .badge { font-family: monospace; font-size: 12px; color: #38bdf8; background: #0f172a; padding: 6px 12px; border-radius: 6px; display: inline-block; }
  </style>
</head>
<body>
  <div class="container">
    <h1>🚀 Android MCP Auto-Update Dashboard</h1>
    <p class="subtitle">Deploy new APK releases directly to user devices over the air (OTA).</p>

    <div class="status-card">
      <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;">
        <span style="font-weight: 600; font-size: 14px; color: #cbd5e1;">Live Fleet Release</span>
        ${hasApk ? '<span class="tag tag-online">● Ready for OTA</span>' : '<span class="tag tag-offline">No Release Published</span>'}
      </div>
      <div class="status-grid">
        <div><span class="status-label">Version: </span><span class="status-val">${meta ? `v${meta.versionName} (Build ${meta.versionCode})` : 'None'}</span></div>
        <div><span class="status-label">Size: </span><span class="status-val">${meta ? `${(meta.apkSize / (1024 * 1024)).toFixed(2)} MB` : 'N/A'}</span></div>
        <div><span class="status-label">Published: </span><span class="status-val">${meta ? new Date(meta.publishedAt).toLocaleString() : 'N/A'}</span></div>
        <div><span class="status-label">SHA256: </span><span class="status-val" style="font-family: monospace; font-size: 11px;">${meta ? meta.apkSha256.substring(0, 16) + '...' : 'N/A'}</span></div>
      </div>
      ${meta ? `<div style="margin-top: 12px; font-size: 12px; color: #94a3b8;"><strong>Changelog:</strong> ${meta.changelog}</div>` : ''}
    </div>

    <form method="POST" action="/api/update/publish" enctype="multipart/form-data">
      <h3 style="font-size: 16px; margin-bottom: 16px; color: #f1f5f9;">Publish New APK Build</h3>

      <div class="form-group">
        <label>APK Binary File (.apk)</label>
        <div class="drop-zone" onclick="document.getElementById('apkFile').click()">
          <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#38bdf8" stroke-width="2" style="margin-bottom: 8px;">
            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
            <polyline points="17 8 12 3 7 8"></polyline>
            <line x1="12" y1="3" x2="12" y2="15"></line>
          </svg>
          <div style="font-size: 13px; color: #94a3b8;" id="fileLabel">Click to browse or drop app-debug.apk here</div>
          <input type="file" id="apkFile" name="apk" accept=".apk" required style="display:none;" onchange="document.getElementById('fileLabel').innerText = this.files[0].name">
        </div>
      </div>

      <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 14px;">
        <div class="form-group">
          <label for="versionName">Version Name (e.g. 1.1.0)</label>
          <input type="text" id="versionName" name="versionName" placeholder="1.1.0" required>
        </div>
        <div class="form-group">
          <label for="versionCode">Version Code (integer e.g. 2)</label>
          <input type="number" id="versionCode" name="versionCode" placeholder="2" required>
        </div>
      </div>

      <div class="form-group">
        <label for="changelog">Release Notes & Changelog</label>
        <textarea id="changelog" name="changelog" rows="3" placeholder="What's new in this build..."></textarea>
      </div>

      <div class="form-group">
        <label for="apiKey">Developer API Key</label>
        <input type="text" id="apiKey" name="apiKey" value="mcp-user-secret-key-101" required>
      </div>

      <button type="submit" class="btn-submit">⚡ Publish Update to Device Fleet</button>
    </form>
  </div>
</body>
</html>`;
  }
}

export const updateController = new UpdateController();
