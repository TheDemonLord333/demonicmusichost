# Demonic Music Host – Deployment Guide (Debian 12)

This guide sets up the Ktor backend on a **Debian 12 "Bookworm"** server with:
- **systemd** as process supervisor
- **nginx** as HTTPS reverse proxy (Let's Encrypt via certbot)
- A dedicated system user for security isolation

---

## Prerequisites

| Item | Minimum |
|------|---------|
| Server OS | Debian 12 (Bookworm) |
| RAM | 512 MB |
| Java | JDK 17+ |
| Public domain | Required for HTTPS / Spotify OAuth |
| Spotify Developer App | Client ID + Secret |
| YouTube Data API key | For YouTube search |

---

## 1 – Prepare the server

```bash
# Update system
sudo apt update && sudo apt upgrade -y

# Install Java 17 (JRE is sufficient)
sudo apt install -y openjdk-17-jre-headless curl nginx certbot python3-certbot-nginx

# Create a dedicated user (no login shell, no home dir)
sudo useradd --system --no-create-home --shell /usr/sbin/nologin demonicmusichost

# Create app directory
sudo mkdir -p /opt/demonicmusichost
sudo chown demonicmusichost:demonicmusichost /opt/demonicmusichost

# Create log directory
sudo mkdir -p /var/log/demonicmusichost
sudo chown demonicmusichost:demonicmusichost /var/log/demonicmusichost
```

---

## 2 – Configure environment variables

Copy the template and fill in your credentials:

```bash
sudo cp /path/to/repo/deploy/demonicmusichost.env /opt/demonicmusichost/demonicmusichost.env
sudo nano /opt/demonicmusichost/demonicmusichost.env
```

Fill in:
- `BASE_URL` – e.g. `https://music.example.com`
- `SPOTIFY_CLIENT_ID` and `SPOTIFY_CLIENT_SECRET` – from your Spotify Developer Dashboard
- `SPOTIFY_REDIRECT_URI` – must match `https://<YOUR_DOMAIN>/auth/callback`
- `YOUTUBE_API_KEY` – from Google Cloud Console
- `SESSION_SECRET` – run `openssl rand -hex 32` and paste the output

Then lock down the file:

```bash
sudo chmod 600 /opt/demonicmusichost/demonicmusichost.env
sudo chown demonicmusichost:demonicmusichost /opt/demonicmusichost/demonicmusichost.env
```

---

## 3 – Install the systemd service

```bash
sudo cp /path/to/repo/deploy/demonicmusichost.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable demonicmusichost
```

The service will start automatically on boot. It will also restart on failure.

---

## 4 – Configure nginx

```bash
# Copy the nginx config
sudo cp /path/to/repo/deploy/nginx.conf /etc/nginx/sites-available/demonicmusichost

# Edit: replace all <YOUR_DOMAIN> placeholders
sudo nano /etc/nginx/sites-available/demonicmusichost

# Enable the site
sudo ln -s /etc/nginx/sites-available/demonicmusichost /etc/nginx/sites-enabled/

# Disable the default nginx site (optional but recommended)
sudo rm -f /etc/nginx/sites-enabled/default

# Test the config
sudo nginx -t
```

---

## 5 – Obtain an SSL certificate (Let's Encrypt)

```bash
# Obtain certificate and auto-configure nginx
sudo certbot --nginx -d <YOUR_DOMAIN>
```

Certbot will update the nginx config automatically with the certificate paths.
It also installs a cron job for automatic renewal.

Test renewal (dry run):

```bash
sudo certbot renew --dry-run
```

---

## 6 – Deploy the backend JAR

**Option A – automated script (from your local dev machine):**

```bash
# First build + deploy
./deploy/deploy.sh deploy@<YOUR_DOMAIN>

# Deploy without rebuilding (e.g. after manual build)
./deploy/deploy.sh deploy@<YOUR_DOMAIN> --skip-build
```

**Option B – manual:**

```bash
# On local machine – build the fat JAR
./gradlew :backend:buildFatJar

# Upload
scp backend/build/libs/demonicmusichost-backend.jar deploy@<YOUR_DOMAIN>:/opt/demonicmusichost/

# On the server – start the service
sudo systemctl start demonicmusichost
```

---

## 7 – Verify the deployment

```bash
# Check service status
sudo systemctl status demonicmusichost

# Follow logs in real-time
sudo journalctl -u demonicmusichost -f

# Or tail the log file
tail -f /var/log/demonicmusichost/backend.log

# Health check via nginx
curl https://<YOUR_DOMAIN>/health
# Expected: {"status":"ok"}
```

---

## 8 – Configure the Android app

In the app's **Settings** screen, set the Backend URL to:

```
https://<YOUR_DOMAIN>
```

Tap **Verbindung testen** – you should see a green checkmark.

---

## 9 – Spotify Developer Dashboard setup

1. Go to [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard)
2. Open your app → **Settings**
3. Add Redirect URI: `https://<YOUR_DOMAIN>/auth/callback`
4. Under **Android packages**, add:
   - Package name: `com.demonicmusichost.app`
   - SHA-1 fingerprint of your release key

---

## Useful commands

| Command | Description |
|---------|-------------|
| `sudo systemctl restart demonicmusichost` | Restart after new deploy |
| `sudo systemctl stop demonicmusichost` | Stop the backend |
| `sudo journalctl -u demonicmusichost -n 100` | Last 100 log lines |
| `sudo nginx -t && sudo systemctl reload nginx` | Reload nginx config |
| `sudo certbot renew` | Renew SSL certificate |
| `sudo tail -f /var/log/nginx/demonicmusichost_error.log` | nginx error log |

---

## Firewall (ufw)

```bash
sudo ufw allow OpenSSH
sudo ufw allow 'Nginx Full'   # ports 80 + 443
sudo ufw enable
sudo ufw status
```

> Port 8080 (Ktor) should **not** be exposed publicly – nginx is the only entry point.
