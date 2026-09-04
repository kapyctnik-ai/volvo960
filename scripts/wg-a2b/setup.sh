#!/usr/bin/env bash
# Поднимает обычный WireGuard-туннель Сервер А -> Сервер Б и заворачивает
# в него трафик клиентов AmneziaWG, которые подключаются к серверу А.
#
# Запускать НА СЕРВЕРЕ А от root:
#   SERVER_B_IP=139.84.177.77 bash setup.sh
#
# Скрипт сам зайдёт по ssh на сервер Б и настроит его (пароль спросит один раз).
# Переменные, которые можно переопределить:
#   SERVER_B_IP   - публичный IP сервера Б (обязательно)
#   SERVER_B_SSH  - ssh-порт сервера Б (по умолчанию 22)
#   WG_PORT       - UDP-порт туннеля на Б (по умолчанию 51820)
#   TUN_NET       - /30 для линка А<->Б (по умолчанию 10.66.66.0/30)
#   AWG_SUBNET    - подсеть клиентов AmneziaWG, напр. 10.8.1.0/24
#                   (если не задана - определяется автоматически)
#   IFACE         - имя нового интерфейса на А (по умолчанию wgb)

set -euo pipefail

SERVER_B_IP="${SERVER_B_IP:?укажи SERVER_B_IP=<ip сервера Б>}"
SERVER_B_SSH="${SERVER_B_SSH:-22}"
WG_PORT="${WG_PORT:-51820}"
IFACE="${IFACE:-wgb}"
A_TUN_IP="${A_TUN_IP:-10.66.66.2}"
B_TUN_IP="${B_TUN_IP:-10.66.66.1}"
TUN_CIDR="${TUN_CIDR:-30}"
TUN_NET="${TUN_NET:-10.66.66.0/30}"
RT_TABLE="${RT_TABLE:-200}"

log() { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m!!\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" = 0 ] || die "нужен root"

# ---------------------------------------------------------------- пакеты на А
if ! command -v wg >/dev/null 2>&1; then
  log "ставлю wireguard-tools на сервере А"
  if command -v apt-get >/dev/null; then
    DEBIAN_FRONTEND=noninteractive apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq wireguard-tools
  elif command -v dnf >/dev/null; then dnf install -y wireguard-tools
  else die "не знаю пакетный менеджер, поставь wireguard-tools руками"; fi
fi
command -v iptables >/dev/null || die "нет iptables"

# ------------------------------------------------- подсеть клиентов AmneziaWG
if [ -z "${AWG_SUBNET:-}" ]; then
  log "ищу подсеть клиентов AmneziaWG"
  for f in /etc/amnezia/amneziawg/*.conf /etc/amneziawg/*.conf /etc/wireguard/*.conf; do
    [ -e "$f" ] || continue
    case "$f" in */"$IFACE".conf) continue;; esac
    addr=$(awk -F= '/^[[:space:]]*Address/{gsub(/[[:space:]]/,"",$2); print $2; exit}' "$f" || true)
    [ -n "$addr" ] || continue
    addr=${addr%%,*}
    AWG_SUBNET=$(python3 - "$addr" <<'PY' 2>/dev/null || true
import ipaddress, sys
print(ipaddress.ip_interface(sys.argv[1]).network)
PY
)
    [ -n "$AWG_SUBNET" ] && { log "нашёл в $f: $AWG_SUBNET"; break; }
  done
fi
[ -n "${AWG_SUBNET:-}" ] || die "не смог определить подсеть AmneziaWG, задай AWG_SUBNET=10.8.1.0/24"

# ------------------------------------------------------------------- ключи
log "генерю ключи"
umask 077
A_PRIV=$(wg genkey); A_PUB=$(printf '%s' "$A_PRIV" | wg pubkey)
B_PRIV=$(wg genkey); B_PUB=$(printf '%s' "$B_PRIV" | wg pubkey)

# --------------------------------------------------------------- сервер Б
log "настраиваю сервер Б ($SERVER_B_IP) по ssh"
ssh -o StrictHostKeyChecking=accept-new -p "$SERVER_B_SSH" "root@$SERVER_B_IP" \
  "B_PRIV='$B_PRIV' A_PUB='$A_PUB' WG_PORT='$WG_PORT' B_TUN_IP='$B_TUN_IP' \
   TUN_CIDR='$TUN_CIDR' TUN_NET='$TUN_NET' A_TUN_IP='$A_TUN_IP' bash -s" <<'REMOTE'
set -euo pipefail
if ! command -v wg >/dev/null 2>&1; then
  if command -v apt-get >/dev/null; then
    DEBIAN_FRONTEND=noninteractive apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq wireguard iptables
  elif command -v dnf >/dev/null; then dnf install -y wireguard-tools iptables
  else echo "поставь wireguard руками" >&2; exit 1; fi
fi

WAN=$(ip -4 route show default | awk '{for(i=1;i<=NF;i++) if($i=="dev"){print $(i+1); exit}}')
[ -n "$WAN" ] || { echo "не нашёл внешний интерфейс" >&2; exit 1; }
echo "внешний интерфейс на Б: $WAN"

install -d -m 700 /etc/wireguard
umask 077
cat > /etc/wireguard/wg0.conf <<CONF
[Interface]
Address = ${B_TUN_IP}/${TUN_CIDR}
ListenPort = ${WG_PORT}
PrivateKey = ${B_PRIV}

PostUp = sysctl -qw net.ipv4.ip_forward=1
PostUp = iptables -t nat -A POSTROUTING -s ${TUN_NET} -o ${WAN} -j MASQUERADE
PostUp = iptables -A FORWARD -i %i -o ${WAN} -j ACCEPT
PostUp = iptables -A FORWARD -i ${WAN} -o %i -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
PostDown = iptables -t nat -D POSTROUTING -s ${TUN_NET} -o ${WAN} -j MASQUERADE
PostDown = iptables -D FORWARD -i %i -o ${WAN} -j ACCEPT
PostDown = iptables -D FORWARD -i ${WAN} -o %i -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT

[Peer]
PublicKey = ${A_PUB}
AllowedIPs = ${A_TUN_IP}/32
CONF
chmod 600 /etc/wireguard/wg0.conf

printf 'net.ipv4.ip_forward=1\n' > /etc/sysctl.d/99-wg-forward.conf
sysctl -qw net.ipv4.ip_forward=1

# открыть порт, если фаервол включён
if command -v ufw >/dev/null && ufw status 2>/dev/null | grep -q '^Status: active'; then
  ufw allow "${WG_PORT}/udp" >/dev/null || true
fi
if command -v firewall-cmd >/dev/null && firewall-cmd --state >/dev/null 2>&1; then
  firewall-cmd --permanent --add-port="${WG_PORT}/udp" >/dev/null || true
  firewall-cmd --reload >/dev/null || true
fi
iptables -C INPUT -p udp --dport "${WG_PORT}" -j ACCEPT 2>/dev/null || \
  iptables -I INPUT -p udp --dport "${WG_PORT}" -j ACCEPT

systemctl enable wg-quick@wg0 >/dev/null 2>&1 || true
systemctl restart wg-quick@wg0
sleep 1
wg show wg0 >/dev/null && echo "сервер Б: wg0 поднят"
REMOTE

# ---------------------------------------------------------------- сервер А
log "настраиваю сервер А (интерфейс $IFACE)"
install -d -m 700 /etc/wireguard
umask 077
cat > "/etc/wireguard/${IFACE}.conf" <<CONF
[Interface]
Address = ${A_TUN_IP}/${TUN_CIDR}
PrivateKey = ${A_PRIV}
MTU = 1380
# маршруты ставим сами - штатный default сервера А (ssh!) трогать нельзя
Table = off

PostUp = sysctl -qw net.ipv4.ip_forward=1
PostUp = ip route replace default dev %i table ${RT_TABLE}
PostUp = ip rule add from ${AWG_SUBNET} lookup ${RT_TABLE} priority 200
PostUp = ip rule add from ${A_TUN_IP}/32 lookup ${RT_TABLE} priority 199
PostUp = iptables -t nat -A POSTROUTING -o %i -j MASQUERADE
PostUp = iptables -A FORWARD -o %i -j ACCEPT
PostUp = iptables -A FORWARD -i %i -j ACCEPT
PostUp = iptables -t mangle -A FORWARD -o %i -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu
PostDown = ip rule del from ${AWG_SUBNET} lookup ${RT_TABLE} priority 200
PostDown = ip rule del from ${A_TUN_IP}/32 lookup ${RT_TABLE} priority 199
PostDown = ip route flush table ${RT_TABLE}
PostDown = iptables -t nat -D POSTROUTING -o %i -j MASQUERADE
PostDown = iptables -D FORWARD -o %i -j ACCEPT
PostDown = iptables -D FORWARD -i %i -j ACCEPT
PostDown = iptables -t mangle -D FORWARD -o %i -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu

[Peer]
PublicKey = ${B_PUB}
Endpoint = ${SERVER_B_IP}:${WG_PORT}
AllowedIPs = 0.0.0.0/0
PersistentKeepalive = 25
CONF
chmod 600 "/etc/wireguard/${IFACE}.conf"

printf 'net.ipv4.ip_forward=1\n' > /etc/sysctl.d/99-wg-forward.conf
sysctl -qw net.ipv4.ip_forward=1

systemctl enable "wg-quick@${IFACE}" >/dev/null 2>&1 || true
systemctl restart "wg-quick@${IFACE}"
sleep 2

# ------------------------------------------------------------------ проверка
log "проверяю"
wg show "$IFACE"
echo
ping -c2 -W3 -I "$IFACE" "$B_TUN_IP" >/dev/null && echo "ping до Б по туннелю: ок" || die "туннель не отвечает"
OUT_IP=$(curl -s --max-time 15 --interface "$IFACE" https://api.ipify.org || true)
echo "внешний IP через туннель: ${OUT_IP:-<не определился>}"
if [ "$OUT_IP" = "$SERVER_B_IP" ]; then
  log "готово: трафик клиентов $AWG_SUBNET уходит через сервер Б"
else
  echo "!! ожидался $SERVER_B_IP - проверь NAT на сервере Б" >&2
fi
