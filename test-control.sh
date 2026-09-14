#!/bin/sh
set -e
cd "$(dirname "$0")/server"
export JAVA_HOME="$(cd ..; pwd)/.jdk/jdk-25.0.4.1+1/Contents/Home"
rm -f control.log; rm -f /tmp/naparnik-in; mkfifo /tmp/naparnik-in
"$JAVA_HOME/bin/java" -Xmx2G -jar fabric-server-launch.jar nogui < /tmp/naparnik-in > control.log 2>&1 &
SRV=$!
exec 3> /tmp/naparnik-in
for i in $(seq 1 150); do
  grep -q 'Done (' control.log && break
  kill -0 $SRV 2>/dev/null || { echo "СЕРВЕР УПАЛ"; exit 1; }
  sleep 2
done
echo "forceload add -32 -32 32 32" >&3
sleep 4
echo "execute positioned 0.0 100.0 0.0 run naparnik spawn" >&3
sleep 3
echo "naparnik control" >&3
sleep 3
W=naparnik-test
printf 'копать\nвперёд\nкопать\nвперёд\nвправо\nкопать\nвперёд\nбить\nставить\nвперёд\n' > "$W/naparnik-command.txt"
sleep 10
echo "--- ОБЗОР ПОСЛЕ КОМАНД ---"
cat "$W/naparnik-view.txt" 2>&1
echo "stop" >&3
wait $SRV 2>/dev/null || true
exec 3>&-
