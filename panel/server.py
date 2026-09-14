#!/usr/bin/env python3
"""Панель напарников: настройки, фоновое обучение на островах, мониторинг игры, выкладка в игру.

    python3 panel/server.py      → http://127.0.0.1:8765

Только стандартная библиотека. Все данные — в ~/.naparnik: панель не держит своего состояния,
поэтому её можно закрыть и открыть, а острова и игра продолжат жить.
"""
import json
import os
import shutil
import subprocess
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HOME = Path.home() / ".naparnik"
CONFIG = HOME / "config.properties"
RUNS = HOME / "runs"
GAME = HOME / "game" / "stats.json"
PROMOTE = HOME / "promote" / "brain.json"
PROMOTE_INFO = HOME / "promote" / "info.json"
OUT = ROOT / "out"
def find_java_bin():
    """JDK 25: сначала локальная в .jdk проекта, потом JAVA_HOME, потом java из PATH."""
    for home in sorted((ROOT / ".jdk").glob("jdk-25*"), reverse=True):
        for candidate in (home / "Contents" / "Home" / "bin", home / "bin"):
            if (candidate / "java").exists():
                return candidate
    if os.environ.get("JAVA_HOME"):
        return Path(os.environ["JAVA_HOME"]) / "bin"
    found = shutil.which("java")
    if found:
        return Path(found).resolve().parent
    sys.exit("Не найдена Java 25: положи JDK в .jdk/ или задай JAVA_HOME (см. README)")


JAVA = find_java_bin()
PORT = 8765


def build_trainer():
    """Тренер компилируется при старте панели: запускать острова по устаревшим классам нельзя."""
    OUT.mkdir(exist_ok=True)
    sources = [str(p) for p in (ROOT / "trainer" / "src" / "naparnik").glob("*.java")]
    subprocess.run([str(JAVA / "javac"), "-d", str(OUT), *sources], check=True)


def read_config():
    cfg = {}
    if CONFIG.exists():
        for line in CONFIG.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                cfg[k.strip()] = v.strip()
    return cfg


def write_config(cfg):
    HOME.mkdir(parents=True, exist_ok=True)
    lines = ["# настройки напарников: правит панель, читают тренер и игра"]
    lines += [f"{k}={v}" for k, v in cfg.items()]
    tmp = CONFIG.with_suffix(".tmp")
    tmp.write_text("\n".join(lines) + "\n", encoding="utf-8")
    tmp.replace(CONFIG)


def read_json(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def list_runs():
    runs = []
    if not RUNS.exists():
        return runs
    for d in sorted(RUNS.iterdir()):
        if not d.is_dir():
            continue
        status = read_json(d / "status.json") or {"name": d.name}
        # статус без свежего обновления = процесс умер, а не «всё ещё считает»
        if status.get("running") and time.time() * 1000 - status.get("updated", 0) > 120_000:
            status["running"] = False
            status["stale"] = True
        history = []
        h = d / "history.csv"
        if h.exists():
            for row in h.read_text(encoding="utf-8").splitlines()[1:]:
                parts = row.split(",")
                if len(parts) >= 3:
                    history.append([int(parts[0]), float(parts[1]), float(parts[2])])
        status["history"] = history[-400:]
        status["hasBest"] = (d / "best.json").exists()
        runs.append(status)
    return runs


def start_islands(prefix, count):
    build_trainer()
    RUNS.mkdir(parents=True, exist_ok=True)
    started = []
    for i in range(count):
        name = f"{prefix}-{i + 1}"
        log = open(RUNS.parent / f"{name}.log", "w")
        subprocess.Popen(
            [str(JAVA / "java"), "-cp", str(OUT), "naparnik.Island", name, str(int(time.time() * 1000) + i)],
            cwd=str(ROOT), stdout=log, stderr=subprocess.STDOUT, start_new_session=True,
        )
        started.append(name)
    return started


def stop_runs(names):
    for name in names:
        d = RUNS / name
        if d.is_dir():
            (d / "stop").touch()


def promote(run):
    src = RUNS / run / "best.json"
    if not src.exists():
        raise ValueError(f"у {run} ещё нет лучшего мозга")
    PROMOTE.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(src, PROMOTE)
    status = read_json(RUNS / run / "status.json") or {}
    info = {"run": run, "record": status.get("record"), "generation": status.get("generation"),
            "at": int(time.time() * 1000)}
    PROMOTE_INFO.write_text(json.dumps(info, ensure_ascii=False), encoding="utf-8")
    return info


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def send(self, code, body, ctype="application/json; charset=utf-8"):
        data = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}")

    def do_GET(self):
        if self.path in ("/", "/index.html"):
            return self.send(200, (ROOT / "panel" / "index.html").read_bytes(), "text/html; charset=utf-8")
        if self.path == "/api/state":
            return self.send(200, {
                "config": read_config(),
                "runs": list_runs(),
                "game": read_json(GAME),
                "promoted": read_json(PROMOTE_INFO),
                "now": int(time.time() * 1000),
            })
        self.send(404, {"error": "нет такого адреса"})

    def do_POST(self):
        try:
            data = self.body()
            if self.path == "/api/config":
                write_config(data)
                return self.send(200, {"ok": True})
            if self.path == "/api/config/reset":
                # значения по умолчанию живут в одном месте — в Config.java; панель их не дублирует
                CONFIG.unlink(missing_ok=True)
                subprocess.run([str(JAVA / "java"), "-cp", str(OUT), "naparnik.ConfigInit"], cwd=str(ROOT), check=True)
                return self.send(200, {"ok": True})
            if self.path == "/api/islands/start":
                count = max(1, min(int(data.get("count", 1)), os.cpu_count() or 4))
                return self.send(200, {"started": start_islands(data.get("prefix") or "остров", count)})
            if self.path == "/api/islands/stop":
                stop_runs(data.get("names") or [r["name"] for r in list_runs()])
                return self.send(200, {"ok": True})
            if self.path == "/api/islands/delete":
                for name in data.get("names", []):
                    d = RUNS / name
                    status = read_json(d / "status.json") or {}
                    if status.get("running") and time.time() * 1000 - status.get("updated", 0) < 120_000:
                        return self.send(409, {"error": f"{name} ещё работает — сначала останови"})
                    shutil.rmtree(d, ignore_errors=True)
                return self.send(200, {"ok": True})
            if self.path == "/api/promote":
                return self.send(200, promote(data["run"]))
            self.send(404, {"error": "нет такого адреса"})
        except Exception as e:  # ошибка обязана дойти до экрана, а не утонуть в консоли
            self.send(400, {"error": str(e)})


def main():
    HOME.mkdir(parents=True, exist_ok=True)
    build_trainer()
    if not CONFIG.exists():
        subprocess.run([str(JAVA / "java"), "-cp", str(OUT), "naparnik.ConfigInit"], cwd=str(ROOT), check=False)
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print(f"Панель напарников: http://127.0.0.1:{PORT}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        sys.exit(0)


if __name__ == "__main__":
    main()
