#!/bin/sh
# Выживание в сухой огороженной арене — место опыта не должно подменять результат (раньше спавн
# у моря ставил напарников ногами в воду).
#   MODE=adjacent — неподвижные коровы вплотную: срабатывает ли охота
#   MODE=hunt     — бродящие коровы по арене: находят ли и едят
#   MODE=water    — напарников сталкивают на дно пруда: выбираются ли
set -e
cd "$(dirname "$0")/server"
for d in ../.jdk/jdk-25*/Contents/Home; do [ -x "$d/bin/java" ] && JAVA_HOME="$d"; done
LOG="survival-${MODE:-hunt}.log"
rm -f "$LOG"; rm -f /tmp/naparnik-in; mkfifo /tmp/naparnik-in
mkdir -p "$PWD/.test-home/.naparnik"
[ -n "${TEST_CONFIG:-}" ] && printf '%s\n' $TEST_CONFIG > "$PWD/.test-home/.naparnik/config.properties"
"$JAVA_HOME/bin/java" -Duser.home="$PWD/.test-home" -Xmx2G -jar fabric-server-launch.jar nogui < /tmp/naparnik-in > "$LOG" 2>&1 &
SRV=$!
exec 3> /tmp/naparnik-in
for i in $(seq 1 150); do grep -q 'Done (' "$LOG" && break; sleep 2; done
echo "time set day" >&3
echo "gamerule doDaylightCycle false" >&3
echo "forceload add -32 -32 32 32" >&3; sleep 4

# арена 15x15: пол из дёрна на 149, стены из коренной породы в 3 блока, внутри воздух
echo "fill -9 145 -9 9 160 9 minecraft:air" >&3
echo "fill -8 140 -8 8 149 8 minecraft:dirt" >&3
echo "fill -8 149 -8 8 149 8 minecraft:grass_block" >&3
echo "fill -8 150 -8 8 152 -8 minecraft:bedrock" >&3
echo "fill -8 150 8 8 152 8 minecraft:bedrock" >&3
echo "fill -8 150 -8 -8 152 8 minecraft:bedrock" >&3
echo "fill 8 150 -8 8 152 8 minecraft:bedrock" >&3
sleep 1

echo "execute positioned 0.5 150 0.5 run naparnik spawn" >&3; sleep 1
echo "execute positioned -3.5 150 0.5 run naparnik spawn" >&3; sleep 2

case "${MODE:-hunt}" in
  adjacent)
    for dz in -2 0 2; do echo "summon minecraft:cow 2.5 150 $dz.5 {NoAI:1b}" >&3; done ;;
  hunt)
    for i in 1 2 3 4 5 6; do echo "summon minecraft:cow $((i * 2 - 7)).5 150 5.5" >&3; done ;;
  water)
    # пруд 5x7 глубиной 4 внутри арены, обоих напарников — на дно
    echo "fill 2 146 -3 6 149 3 minecraft:water" >&3
    sleep 1
    echo "tp @e[type=minecraft:villager] 4.5 146 0.5" >&3 ;;
esac

T=0
while [ "$T" -lt "${LIVE_SECONDS:-150}" ]; do
  echo "naparnik status" >&3
  sleep 10
  T=$((T + 10))
done
echo "stop" >&3
wait $SRV 2>/dev/null || true
exec 3>&-
