#!/bin/sh
# Первый призванный напарник обязан получить ровно стартовый мозг; популяция сохраняется при остановке.
set -e
cd "$(dirname "$0")/server"
for d in ../.jdk/jdk-25*/Contents/Home ../.jdk/jdk-25*; do [ -x "$d/bin/java" ] && { JAVA_HOME="$d"; break; }; done
rm -f seed.log; rm -f /tmp/naparnik-in; mkfifo /tmp/naparnik-in
mkdir -p "$PWD/.test-home"
"$JAVA_HOME/bin/java" -Duser.home="$PWD/.test-home" -Xmx2G -jar fabric-server-launch.jar nogui < /tmp/naparnik-in > seed.log 2>&1 &
SRV=$!
exec 3> /tmp/naparnik-in
for i in $(seq 1 150); do grep -q 'Done (' seed.log && break; sleep 2; done
echo "forceload add -32 -32 32 32" >&3; sleep 4
echo "execute positioned 0.0 100.0 0.0 run naparnik spawn" >&3; sleep 3
echo "stop" >&3
wait $SRV 2>/dev/null || true
exec 3>&-
