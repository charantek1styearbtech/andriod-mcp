#!/usr/bin/env bash
# ==============================================================================
# Android MCP Distributed Backend - Production Deployment for Oracle Cloud (OCI)
# ==============================================================================
set -e

echo "=================================================================="
echo "🚀 Android MCP Distributed Cluster - Oracle Cloud Auto Deployer"
echo "=================================================================="

# 1. Ensure running as root or with sudo
if [ "$EUID" -ne 0 ]; then
  echo "[-] Please run this script with sudo: sudo ./deploy.sh"
  exit 1
fi

# 2. Fix Oracle Cloud Ubuntu iptables firewall (Allow 80 & 443)
echo "[+] Configuring Ubuntu firewall for HTTP (80) & HTTPS (443)..."
iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT 2>/dev/null || iptables -A INPUT -p tcp --dport 80 -j ACCEPT
iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT 2>/dev/null || iptables -A INPUT -p tcp --dport 443 -j ACCEPT
if command -v netfilter-persistent &> /dev/null; then
  netfilter-persistent save
fi
if command -v ufw &> /dev/null; then
  ufw allow 80/tcp
  ufw allow 443/tcp
fi

# 3. Check and install Docker & Docker Compose if missing
if ! command -v docker &> /dev/null; then
  echo "[+] Installing Docker..."
  curl -fsSL https://get.docker.com | sh
  systemctl enable --now docker
fi

if ! docker compose version &> /dev/null; then
  echo "[+] Installing Docker Compose plugin..."
  apt-get update && apt-get install -y docker-compose-plugin
fi

# 4. Prompt for Domain and Email for SSL (or read from environment)
if [ -z "$DOMAIN" ]; then
  read -p "Enter your public Domain (e.g., mcp.example.com or yourname.duckdns.org): " DOMAIN
fi

if [ -z "$EMAIL" ]; then
  read -p "Enter your Email for Let's Encrypt SSL notices: " EMAIL
fi

if [ -z "$DOMAIN" ] || [ -z "$EMAIL" ]; then
  echo "[-] Error: DOMAIN and EMAIL are required for SSL certificate generation."
  exit 1
fi

echo "[+] Target Domain: $DOMAIN"
echo "[+] Let's Encrypt Email: $EMAIL"

# 5. Generate production NGINX config from template
echo "[+] Generating nginx.prod.conf..."
sed "s/\${DOMAIN}/$DOMAIN/g" nginx.prod.conf.template > nginx.prod.conf

# 6. Initialize dummy certificates so NGINX can boot for the ACME challenge
echo "[+] Setting up initial SSL certificate directory..."
docker volume create backend_certbot_etc 2>/dev/null || true
docker volume create backend_certbot_var 2>/dev/null || true

CERT_PATH="/var/lib/docker/volumes/backend_certbot_etc/_data/live/$DOMAIN"
mkdir -p "$CERT_PATH"

if [ ! -f "$CERT_PATH/fullchain.pem" ]; then
  echo "[+] Creating temporary self-signed certificate for first NGINX boot..."
  openssl req -x509 -nodes -newkey rsa:2048 -days 1 \
    -keyout "$CERT_PATH/privkey.pem" \
    -out "$CERT_PATH/fullchain.pem" \
    -subj "/CN=localhost"
fi

# 7. Start NGINX & Backend services
echo "[+] Booting initial container cluster..."
docker compose -f docker-compose.prod.yml up -d --build nginx redis mongo backend-1 backend-2

echo "[+] Waiting for NGINX to initialize..."
sleep 5

# 8. Request real Let's Encrypt SSL certificate
echo "[+] Requesting Let's Encrypt production SSL certificate for $DOMAIN..."
docker compose -f docker-compose.prod.yml run --rm --entrypoint "\
  certbot certonly --webroot -w /var/www/certbot \
    --email $EMAIL \
    -d $DOMAIN \
    --rsa-key-size 4096 \
    --agree-tos \
    --force-renewal \
    --non-interactive" certbot

# 9. Reload NGINX with genuine certificate
echo "[+] Reloading NGINX with verified Let's Encrypt certificate..."
docker compose -f docker-compose.prod.yml exec nginx nginx -s reload

# 10. Start Certbot auto-renewal loop
echo "[+] Starting background Certbot renewal daemon..."
docker compose -f docker-compose.prod.yml up -d certbot

echo "=================================================================="
echo "🎉 DEPLOYMENT SUCCESSFUL!"
echo "=================================================================="
echo "Your distributed Android MCP backend is live at:"
echo "  • Public Health Check:  https://$DOMAIN/health"
echo "  • Remote MCP Endpoint:  https://$DOMAIN/mcp"
echo "  • MCP SSE Transport:    https://$DOMAIN/sse"
echo "  • Android WebSocket:    wss://$DOMAIN/device/ws"
echo ""
echo "Mobile App Gateway URL to enter in phone:"
echo "  wss://$DOMAIN/device/ws"
echo ""
echo "Claude Code / Cursor Configuration:"
echo '  claude mcp add --transport sse android https://'$DOMAIN'/sse'
echo "=================================================================="
