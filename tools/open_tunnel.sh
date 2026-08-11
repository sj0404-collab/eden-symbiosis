#!/usr/bin/env bash
# Put a local HTTP port on the public internet, with no account and no token.
#
# WHY CLOUDFLARE AND NOT NGROK
#   ngrok's free plan allows one tunnel per account, so two desks - Linux and
#   Windows side by side - could never both be reachable. It also wants an
#   authtoken, which is one more secret to keep alive. cloudflared's quick
#   tunnels need neither: no account, no login, as many as you start.
#
#   Measured before this was written, in this workspace, not assumed:
#     * a quick tunnel came up in about 12 seconds and served a file over it
#     * websockify behind it completed a WebSocket upgrade (HTTP 101) and the
#       VNC banner arrived through the tunnel
#     * no X-Frame-Options and no CSP frame-ancestors on the response, so the
#       panel can show the desk in an iframe
#
#   The cost is an address that changes every session. That is why the panel
#   reads the address from session.json instead of anyone memorising it.
#
# Usage: open_tunnel.sh <local-port> [log-file]
# Prints the https URL on stdout, or nothing and exit 1.

set -uo pipefail

PORT="${1:?usage: open_tunnel.sh <local-port> [log]}"
LOG="${2:-/tmp/cloudflared-$PORT.log}"

if ! command -v cloudflared >/dev/null 2>&1; then
  curl -sL --retry 3 -o /tmp/cloudflared \
    https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64
  chmod +x /tmp/cloudflared
  sudo mv /tmp/cloudflared /usr/local/bin/cloudflared 2>/dev/null || {
    mkdir -p "$HOME/.local/bin"; mv /tmp/cloudflared "$HOME/.local/bin/cloudflared"
    export PATH="$HOME/.local/bin:$PATH"
  }
fi

nohup cloudflared tunnel --url "http://localhost:$PORT" \
  --no-autoupdate --loglevel info > "$LOG" 2>&1 &
echo $! > "/tmp/cloudflared-$PORT.pid"

# Poll rather than sleep for a fixed span: the tunnel is usually up in ten
# seconds but occasionally takes thirty, and a fixed wait either wastes time or
# reports a failure that was only slowness.
URL=""
for _ in $(seq 1 40); do
  sleep 2
  URL=$(grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' "$LOG" | head -1)
  [ -n "$URL" ] && break
done

if [ -z "$URL" ]; then
  echo "open_tunnel: no address after 80s" >&2
  tail -20 "$LOG" >&2
  exit 1
fi

# The address exists before the edge is ready to serve it; a client that opens
# it too early gets a 502 and thinks the desk is broken. Wait for a real answer.
for _ in $(seq 1 20); do
  code=$(curl -s -o /dev/null -w '%{http_code}' -m 10 "$URL/" || true)
  case "$code" in 2*|3*|4*) break ;; esac
  sleep 3
done

echo "$URL"
