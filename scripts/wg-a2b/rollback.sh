#!/usr/bin/env bash
# Откат: снимает туннель на сервере А и возвращает выход в интернет
# для клиентов AmneziaWG через сам сервер А.
# Запускать НА СЕРВЕРЕ А от root. Сервер Б при этом можно не трогать.
set -euo pipefail
IFACE="${IFACE:-wgb}"
systemctl disable --now "wg-quick@${IFACE}" 2>/dev/null || true
ip link del "$IFACE" 2>/dev/null || true
rm -f "/etc/wireguard/${IFACE}.conf"
ip rule show | awk '/lookup 200/{print}' | while read -r l; do
  prio=${l%%:*}; ip rule del priority "$prio" 2>/dev/null || true
done
ip route flush table 200 2>/dev/null || true
echo "туннель $IFACE удалён, правила маршрутизации сняты"
