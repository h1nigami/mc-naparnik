#!/bin/sh
# Тонет ли житель без ИИ в ванильной игре: бак с водой, 40 секунд.
set -e
cd "$(dirname "$0")/server"
for d in ../.jdk/jdk-25*/Contents/Home; do [ -x "$d/bin/java" ] && JAVA_HOME="$d"; done
rm -f drown.log; rm -f /tmp/naparnik-in; mkfifo /tmp/naparnik-in
"$JAVA_HOME/bin/java" -Xmx2G -jar fabric-server-launch.jar nogui < /tmp/naparnik-in > drown.log 2>&1 &
SRV=$!
exec 3> /tmp/naparnik-in
for i in $(seq 1 150); do grep -q 'Done (' drown.log && break; sleep 2; done
echo "forceload add 0 0 8 8" >&3; sleep 3
echo "fill 0 150 0 4 158 4 minecraft:glass" >&3
echo "fill 1 151 1 3 158 3 minecraft:water" >&3
sleep 1
echo "summon minecraft:villager 2 151 2 {NoAI:1b,PersistenceRequired:1b,CustomName:'\"Проба\"'}" >&3
sleep 2
echo "data get entity @e[type=minecraft:villager,limit=1] Air" >&3
sleep 20
echo "data get entity @e[type=minecraft:villager,limit=1] Air" >&3
echo "data get entity @e[type=minecraft:villager,limit=1] Health" >&3
sleep 20
echo "execute if entity @e[type=minecraft:villager] run say ЖИТЕЛЬ ЖИВ" >&3
echo "execute unless entity @e[type=minecraft:villager] run say ЖИТЕЛЬ УТОНУЛ" >&3
sleep 2
echo "stop" >&3
wait $SRV 2>/dev/null || true
exec 3>&-
