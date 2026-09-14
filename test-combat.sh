#!/bin/sh
# Бой: зомби и корова вплотную к напарнику. Ждём — зомби получает удары, корова цела.
set -e
cd "$(dirname "$0")/server"
export JAVA_HOME="$(cd ..; pwd)/.jdk/jdk-25.0.4.1+1/Contents/Home"
rm -f combat.log; rm -f /tmp/naparnik-in; mkfifo /tmp/naparnik-in
"$JAVA_HOME/bin/java" -Xmx2G -jar fabric-server-launch.jar nogui < /tmp/naparnik-in > combat.log 2>&1 &
SRV=$!
exec 3> /tmp/naparnik-in
for i in $(seq 1 150); do grep -q 'Done (' combat.log && break; sleep 2; done
echo "time set midnight" >&3
echo "gamerule doDaylightCycle false" >&3
echo "difficulty easy" >&3
echo "forceload add -32 -32 32 32" >&3; sleep 4
echo "execute positioned 0.0 100.0 0.0 run naparnik spawn" >&3; sleep 3
echo "naparnik control" >&3; sleep 2
# зомби с одной стороны, корова с другой — обе в радиусе удара
echo "execute at @e[type=minecraft:villager,limit=1] run summon minecraft:zombie ~2 ~ ~ {NoAI:1b,PersistenceRequired:1b}" >&3
echo "execute at @e[type=minecraft:villager,limit=1] run summon minecraft:cow ~-2 ~ ~ {NoAI:1b}" >&3
sleep 3
echo "--- обзор до боя ---"
cat naparnik-test/naparnik-view.txt 2>&1 | grep -E "цель|позиция|смотрит" || true
# двадцать ударов подряд: рефлекс разворота должен навести на зомби, корова в конус не попадает
for i in $(seq 1 20); do echo "бить"; done > naparnik-test/naparnik-command.txt
sleep 14
echo "--- обзор после боя ---"
cat naparnik-test/naparnik-view.txt 2>&1 | grep -E "цель|смотрит" || true
echo "data get entity @e[type=minecraft:cow,limit=1] Health" >&3
echo "execute if entity @e[type=minecraft:zombie] run say ЗОМБИ ЕЩЁ ЖИВ" >&3
echo "execute unless entity @e[type=minecraft:zombie] run say ЗОМБИ УБИТ" >&3
sleep 3
echo "stop" >&3
wait $SRV 2>/dev/null || true
exec 3>&-
