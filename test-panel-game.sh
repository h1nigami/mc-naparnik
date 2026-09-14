#!/bin/sh
set -e
cd "$(dirname "$0")/server"
export JAVA_HOME="$(cd ..; pwd)/.jdk/jdk-25.0.4.1+1/Contents/Home"
rm -f panelgame.log; rm -f /tmp/naparnik-in; mkfifo /tmp/naparnik-in
"$JAVA_HOME/bin/java" -Xmx2G -jar fabric-server-launch.jar nogui < /tmp/naparnik-in > panelgame.log 2>&1 &
SRV=$!
exec 3> /tmp/naparnik-in
for i in $(seq 1 150); do grep -q 'Done (' panelgame.log && break; sleep 2; done
echo "forceload add -32 -32 32 32" >&3; sleep 4
for i in 1 2; do echo "execute positioned 0.0 100.0 0.0 run naparnik spawn" >&3; sleep 1; done
echo "naparnik speed 2" >&3
sleep 25
# выкладываем мозг, пока игра идёт — должна подхватить сама
touch "$HOME/.naparnik/promote/brain.json"
sleep 12
echo "stop" >&3
wait $SRV 2>/dev/null || true
exec 3>&-
