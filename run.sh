#!/bin/sh
# ./run.sh check  — проверки  |  ./run.sh train [поп] [поколений] [тиков] — обучение
set -e
cd "$(dirname "$0")"
# JDK 25: локальная в .jdk, иначе уже заданный JAVA_HOME, иначе java из PATH
for d in .jdk/jdk-25*/Contents/Home .jdk/jdk-25*; do
  [ -x "$d/bin/javac" ] && { export JAVA_HOME="$PWD/$d"; break; }
done
[ -n "$JAVA_HOME" ] && export PATH="$JAVA_HOME/bin:$PATH"
mkdir -p out
javac -d out $(find trainer/src -name '*.java')
cmd="${1:-check}"; shift 2>/dev/null || true
case "$cmd" in
  check) java -cp out naparnik.SelfCheck ;;
  train) java -cp out naparnik.Main "$@" ;;
  *) echo "используй: ./run.sh check | ./run.sh train"; exit 1 ;;
esac
