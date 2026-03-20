#!/usr/bin/env bash
# ── Demonic Music Host – Deployment Script ───────────────────────────────────
# Usage:
#   ./deploy/deploy.sh [user@host] [--skip-build]
#
# Examples:
#   ./deploy/deploy.sh deploy@music.example.com
#   ./deploy/deploy.sh deploy@music.example.com --skip-build
#
# Prerequisites (local machine):
#   - JDK 17+ and Gradle installed (or ./gradlew available)
#   - ssh access to the server (key-based auth recommended)
#   - The user on the server must be in the sudoers group
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

# ── Configuration ─────────────────────────────────────────────────────────────
REMOTE="${1:-}"
SKIP_BUILD=false
for arg in "$@"; do
    [[ "$arg" == "--skip-build" ]] && SKIP_BUILD=true
done

JAR_NAME="demonicmusichost-backend.jar"
REMOTE_DIR="/opt/demonicmusichost"
SERVICE_NAME="demonicmusichost"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ── Colour helpers ────────────────────────────────────────────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info()    { echo -e "${GREEN}[deploy]${NC} $*"; }
warning() { echo -e "${YELLOW}[deploy]${NC} $*"; }
error()   { echo -e "${RED}[deploy]${NC} $*" >&2; exit 1; }

# ── Validate arguments ────────────────────────────────────────────────────────
if [[ -z "$REMOTE" ]]; then
    error "Usage: $0 <user@host> [--skip-build]"
fi

# ── Step 1 – Build fat JAR ────────────────────────────────────────────────────
if [[ "$SKIP_BUILD" == false ]]; then
    info "Building fat JAR..."
    cd "$REPO_ROOT"
    ./gradlew :backend:buildFatJar --quiet
    info "Build complete."
else
    warning "Skipping build (--skip-build flag set)."
fi

JAR_PATH="$REPO_ROOT/backend/build/libs/$JAR_NAME"
if [[ ! -f "$JAR_PATH" ]]; then
    error "JAR not found at $JAR_PATH. Run without --skip-build."
fi

# ── Step 2 – Upload JAR ───────────────────────────────────────────────────────
info "Uploading $JAR_NAME to $REMOTE:$REMOTE_DIR ..."
scp "$JAR_PATH" "$REMOTE:$REMOTE_DIR/$JAR_NAME.new"

# ── Step 3 – Atomic swap + restart ───────────────────────────────────────────
info "Deploying on remote server..."
ssh "$REMOTE" bash -s << REMOTE_SCRIPT
    set -euo pipefail

    # Atomic replace
    mv "$REMOTE_DIR/$JAR_NAME.new" "$REMOTE_DIR/$JAR_NAME"

    # Restart service
    sudo systemctl restart $SERVICE_NAME
    sleep 3

    # Verify it came back up
    if sudo systemctl is-active --quiet $SERVICE_NAME; then
        echo "  Service is running."
    else
        echo "  ERROR: Service failed to start. Showing last 20 log lines:"
        sudo journalctl -u $SERVICE_NAME -n 20 --no-pager
        exit 1
    fi
REMOTE_SCRIPT

info "Deployment successful!"

# ── Step 4 – Health check ─────────────────────────────────────────────────────
# Extract hostname from user@host
HOST="${REMOTE#*@}"
HEALTH_URL="https://$HOST/health"
info "Checking $HEALTH_URL ..."
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 "$HEALTH_URL" || true)
if [[ "$HTTP_CODE" == "200" ]]; then
    info "Health check passed (HTTP 200)."
else
    warning "Health check returned HTTP $HTTP_CODE – check nginx and the service."
fi
