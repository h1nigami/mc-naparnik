#!/bin/sh
# Шахта: напарник на дне колодца 1x1 глубиной 4 блока, стенки из земли. Выберется ли?
set -e
cd "$(dirname "$0")/server"
for d in ../.jdk/jdk-25*/Contents/Home; do [ -x "$d/bin/java" ] && JAVA_HOME="$d"; done
rm -f pit.log; rm -f /tmp/naparnik-in; mkfifo /tmp/naparnik-in
mkdir -p "$PWD/.test-home"
"$JAVA_HOME/bin/java" -Duser.home="$PWD/.test-home" -Xmx2G -jar fabric-server-launch.jar nogui < /tmp/naparnik-in > pit.log 2>&1 &
SRV=$!
exec 3> /tmp/naparnik-in
for i in $(seq 1 150); do grep -q 'Done (' pit.log && break; sleep 2; done
echo "time set day" >&3
echo "forceload add -32 -32 32 32" >&3; sleep 4
# земляной массив в оболочке коренной породы: ни вбок, ни вниз не уйти — только лестницей вверх.
# Порода 21x21 с 180 до 206, внутри земля, в центре колодец 1x1 с дна 201; поверхность — 207.
echo "fill -10 180 -10 10 206 10 minecraft:bedrock" >&3
echo "fill -9 181 -9 9 206 9 minecraft:dirt" >&3
echo "fill 0 201 0 0 206 0 minecraft:air" >&3
sleep 1
echo "execute positioned 0.5 201 0.5 run naparnik spawn" >&3
sleep 2
echo "naparnik speed ${SPEED:-2}" >&3
# снимок координат каждые 5 секунд — чтобы видеть высоту, а не только число клеток
T=0; while [ "$T" -lt "${LIVE_SECONDS:-120}" ]; do echo "naparnik status" >&3; sleep 5; T=$((T + 5)); done
sleep 3
echo "stop" >&3
wait $SRV 2>/dev/null || true
exec 3>&-
