# Oracle Cloud (OCI) Always Free Production Deployment Guide

Deploy your enterprise-grade distributed Android MCP backend (**NGINX Load Balancer + Multi-Instance Backend + Redis 7 + MongoDB 7 + Let's Encrypt SSL**) on Oracle Cloud Infrastructure (OCI) at **$0 / month forever**.

---

## 1. Prerequisites (Free Forever)

1. **Oracle Cloud Account**: Sign up at [cloud.oracle.com](https://cloud.oracle.com).
2. **Domain Name**: Either:
   - A free dynamic subdomain from [DuckDNS.org](https://www.duckdns.org) (e.g. `android-mcp.duckdns.org`).
   - Your own domain name with an `A` record pointing to your OCI VM's public IP.

---

## 2. Step 1: Create Always Free OCI Compute Instance

1. In the Oracle Cloud Console, navigate to **Compute > Instances > Create Instance**.
2. **Name**: `android-mcp-cluster`
3. **Placement**: Keep default AD.
4. **Image and Shape**:
   - **Image**: Select **Canonical Ubuntu 24.04** (or 22.04 Minimal).
   - **Shape**: Click **Change Shape** > select **Ampere (ARM)** > **VM.Standard.A1.Flex**.
   - Allocate: **2 to 4 OCPUs** and **12 to 24 GB RAM** (Included in Always Free Tier).
5. **Networking**:
   - Select default VCN and public subnet.
   - Ensure **Assign a public IPv4 address** is checked.
6. **Add SSH Keys**:
   - Choose **Generate a key pair for me** and download both the **Private Key** and **Public Key**.
7. Click **Create**. Note your **Public IPv4 Address** once the instance shows `Running`.

---

## 3. Step 2: Open Oracle Cloud Firewall Ports (VCN Ingress)

> [!IMPORTANT]
> Oracle Cloud blocks all ports by default except SSH (22). You MUST allow ports 80 and 443 in the Oracle Web Console.

1. In Oracle Cloud Console, go to **Networking > Virtual Cloud Networks**.
2. Click your VCN > Click **Security Lists** on the left > Click **Default Security List for...**.
3. Click **Add Ingress Rules**:
   - **Source CIDR**: `0.0.0.0/0`
   - **IP Protocol**: `TCP`
   - **Destination Port Range**: `80,443`
   - **Description**: `Allow HTTP and HTTPS traffic`
4. Click **Add Ingress Rules**.

---

## 4. Step 3: Point Your Domain to the Public IP

1. Go to [DuckDNS.org](https://www.duckdns.org) (or your DNS registrar).
2. Create a domain (e.g. `android-mcp.duckdns.org`).
3. Set the **IP** to your OCI instance's **Public IPv4 Address**.

---

## 5. Step 4: Run the One-Click Deployment Script

### Option A: Direct Copy from Windows / Local Machine
Run this in PowerShell from `C:\Users\chara\Desktop\Andriod MCP`:
```powershell
scp -i "C:\path\to\your_private_key.key" -r backend ubuntu@<OCI_PUBLIC_IP>:~/backend
```

### Option B: Clone via Git
```bash
git clone https://github.com/yourusername/your-repo.git
cd your-repo/backend
```

### Run Deployment on the Server
Connect via SSH and execute `deploy.sh`:
```bash
ssh -i /path/to/your_private_key.key ubuntu@<OCI_PUBLIC_IP>
cd ~/backend
chmod +x deploy.sh
sudo ./deploy.sh
```

The script automatically:
- Fixes Oracle Cloud's internal Ubuntu `iptables` rules for ports 80 and 443.
- Installs Docker Engine & Docker Compose plugin.
- Generates modern production NGINX configuration.
- Obtains genuine **Let's Encrypt SSL certificates** (`fullchain.pem` & `privkey.pem`).
- Starts all 6 containers with auto-restart policies (`always`).
- Configures automated 12-hour SSL renewal.

---

## 6. Verification & Connecting Clients

### Health Check
Visit your public domain in any browser:
```
https://<YOUR_DOMAIN>/health
```
You will receive:
```json
{
  "status": "UP",
  "instanceId": "prod-backend-1",
  "uptimeSeconds": 120,
  "connectedDevices": 0,
  "redis": { "connected": true },
  "mongodb": { "connected": true }
}
```

### Connect Mobile Phone App
1. Open the Android MCP Agent app on your phone.
2. Go to the **Remote Server** settings tab.
3. Enter Gateway WebSocket URL:
   ```
   wss://<YOUR_DOMAIN>/device/ws
   ```
4. Sign in with Google. The phone will connect securely over 4G/5G mobile data from anywhere in the world!

### Connect Claude Code / Cursor / AI Clients
Add the remote MCP server directly to Claude Code:
```bash
claude mcp add --transport sse android https://<YOUR_DOMAIN>/sse
```
Or for HTTP Streamable transport:
```bash
claude mcp add android https://<YOUR_DOMAIN>/mcp
```
