#!/bin/sh
# Полный цикл: поднять сервер, призвать напарника, дать ему пожить, снять статус.
set -e
cd "$(dirname "$0")/server"
export JAVA_HOME="$(cd ..; pwd)/.jdk/jdk-25.0.4.1+1/Contents/Home"
rm -f live.log; rm -f /tmp/naparnik-in; mkfifo /tmp/naparnik-in
"$JAVA_HOME/bin/java" -Xmx2G -jar fabric-server-launch.jar nogui < /tmp/naparnik-in > live.log 2>&1 &
SRV=$!
exec 3> /tmp/naparnik-in
for i in $(seq 1 150); do
  grep -q 'Done (' live.log && break
  kill -0 $SRV 2>/dev/null || { echo "СЕРВЕР УПАЛ ПРИ СТАРТЕ"; exit 1; }
  sleep 2
done
# без игрока чанки не загружены — принудительно держим область, иначе спавн уходит в пустоту
echo "forceload add -32 -32 32 32" >&3
sleep 4
for i in 1 2 3; do echo "execute positioned 0.0 100.0 0.0 run naparnik spawn" >&3; sleep 1; done
sleep 5
# ставим вокруг напарника стену из брёвен — проверяем, что связка сенсор->решение->копка работает
echo "execute at @e[type=minecraft:villager,limit=1] run fill ~-3 ~ ~-3 ~3 ~2 ~3 minecraft:oak_log hollow" >&3
sleep 2
echo "execute if entity @e[type=minecraft:villager] run say ЖИТЕЛЬ В МИРЕ ЕСТЬ" >&3
sleep 2
echo "data get entity @e[type=minecraft:villager,limit=1] Pos" >&3
sleep 2
echo "naparnik speed 1" >&3
sleep 1
echo "naparnik status" >&3   # первый замер
sleep "${LIVE_SECONDS:-90}"   # ~2 решения мозга в секунду
echo "naparnik status" >&3   # второй замер
sleep 3
echo "stop" >&3
wait $SRV 2>/dev/null || true
exec 3>&-
