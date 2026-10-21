#!/bin/bash
# Run a command on the phone (Termux sshd :8022).   ph.sh 'cmd ...'   |   ph.sh  (interactive)
# The phone's IP is DHCP and drifts: the last good address is cached in ~/.hermes-phone-ip;
# when it stops answering, the PC's local /24 networks are scanned for port 8022.
KEY="${PHONE_KEY:-/c/Users/Emad Karimi/hermes-handoff/connect/id_phone}"
CACHE="$HOME/.hermes-phone-ip"
USER_ID=u0_a309

open8022() { python -c "import socket,sys;s=socket.socket();s.settimeout(1.5);sys.exit(s.connect_ex(('$1',8022)))" 2>/dev/null; }

find_phone() {
  python - <<'PY'
import concurrent.futures, re, socket, subprocess
out = subprocess.run(["ipconfig"], capture_output=True, text=True).stdout
nets = {ip.rsplit(".", 1)[0] for ip in re.findall(r"IPv4[^:]*:\s*([\d.]+)", out)
        if not ip.startswith(("127.", "169.254."))}
def probe(ip):
    s = socket.socket(); s.settimeout(0.4)
    try:
        return ip if s.connect_ex((ip, 8022)) == 0 else None
    finally:
        s.close()
with concurrent.futures.ThreadPoolExecutor(256) as ex:
    hits = [h for h in ex.map(probe, [f"{n}.{i}" for n in nets for i in range(1, 255)]) if h]
print(hits[0] if hits else "")
PY
}

IP="${PHONE_IP:-$(cat "$CACHE" 2>/dev/null)}"
if [ -z "$IP" ] || ! open8022 "$IP"; then
  IP=$(find_phone | tr -d '\r')
  [ -n "$IP" ] || { echo "phone not found on any local network (is Termux sshd running?)" >&2; exit 1; }
  echo "$IP" > "$CACHE"
fi

exec ssh -p 8022 -i "$KEY" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
  -o LogLevel=ERROR -o IdentitiesOnly=yes -o ConnectTimeout=15 -o ServerAliveInterval=30 \
  "$USER_ID@$IP" "$@"
