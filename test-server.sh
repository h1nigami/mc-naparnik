#!/bin/sh
set -e
cd "$(dirname "$0")/server"
export JAVA_HOME="$(cd ..; pwd)/.jdk/jdk-25.0.4.1+1/Contents/Home"
rm -f server.log; rm -f /tmp/naparnik-in; mkfifo /tmp/naparnik-in
"$JAVA_HOME/bin/java" -Xmx2G -jar fabric-server-launch.jar nogui < /tmp/naparnik-in > server.log 2>&1 &
SRV=$!
exec 3> /tmp/naparnik-in
for i in $(seq 1 150); do
  grep -q 'Done (' server.log && break
  kill -0 $SRV 2>/dev/null || { echo "СЕРВЕР УПАЛ"; break; }
  sleep 2
done
echo "naparnik status" >&3
sleep 3
echo "stop" >&3
wait $SRV 2>/dev/null || true
exec 3>&-
