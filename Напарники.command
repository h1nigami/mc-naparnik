#!/bin/sh
# Двойной клик — поднимает панель напарников и открывает её в браузере.
cd "$(dirname "$0")" || exit 1
if ! curl -s -o /dev/null http://127.0.0.1:8765/api/state; then
  python3 panel/server.py &
  for i in $(seq 1 40); do
    curl -s -o /dev/null http://127.0.0.1:8765/api/state && break
    sleep 1
  done
fi
open http://127.0.0.1:8765
echo "Панель работает. Закрой это окно, чтобы остановить панель (острова и игра продолжат жить)."
wait
