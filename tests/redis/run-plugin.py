"""Full shaded-plugin Redis smoke/regression test on fresh loopback Paper/Folia servers."""
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
JAVA_HOME = Path(sys.argv[1])
REDIS_PORT = int(sys.argv[2])
TEST_ROOT = ROOT / ".testserver"
SERVERS = [
    ("paper", "paper263", "PaperCore", "Paper", 25725, "ecc00000-0000-4000-8000-000000000001"),
    ("folia", "folia263", "Folia", "Folia", 25726, "ecc00000-0000-4000-8000-000000000002"),
]


def prepare(kind, template, module, artifact, port, server_id):
    with socket.socket() as connection:
        if connection.connect_ex(("127.0.0.1", port)) == 0:
            raise RuntimeError(f"Test port already in use: {port}")
    target = TEST_ROOT / f"redis-plugin-{kind}"
    if target.exists():
        raise RuntimeError(f"Fresh test directory required: {target}")
    target.mkdir()
    source = TEST_ROOT / template
    for directory in ("cache", "libraries", "versions"):
        subprocess.run(["cmd", "/c", "mklink", "/J", str(target / directory), str(source / directory)], check=True, capture_output=True)
    shutil.copy2(source / f"{kind}.jar", target / f"{kind}.jar")
    (target / "eula.txt").write_text("eula=true\n")
    (target / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nlevel-name=world\n"
        "level-type=minecraft:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\n"
        'generator-settings={"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1}],"features":false,"lakes":false,"structure_overrides":[]}\n'
        "spawn-protection=0\nmax-players=2\nenable-rcon=false\nenable-query=false\n"
    )
    plugins = target / "plugins"
    config = plugins / "TheNewEconomy"
    config.mkdir(parents=True)
    template_config = TEST_ROOT / "paper263/plugins/TheNewEconomy"
    for filename in ("config.yml", "data.yml", "messages.yml"):
        shutil.copy2(template_config / filename, config / filename)
    shutil.copytree(template_config / "currency", config / "currency")
    settings = (config / "config.yml").read_text()
    settings = re.sub(r"(?m)^  ServerID: .*", f"  ServerID: {server_id}", settings)
    settings = settings.replace("DefaultRegion: TNE_SYSTEM", "DefaultRegion: world")
    settings = re.sub(r"(?ms)(    Account:.*?      Enabled:) true", r"\1 false", settings)
    (config / "config.yml").write_text(settings)
    data = (config / "data.yml").read_text()
    data = re.sub(r"(?ms)(  Sync:.*?    Enabled:) false", r"\1 true", data, count=1)
    data = data.replace("Token: CHANGE_ME", "Token: pr23-local-integration-shared-token-only")
    data = data.replace("Host: localhost", "Host: 127.0.0.1").replace("Port: 6379", f"Port: {REDIS_PORT}")
    data = data.replace("User: user", "User: ''").replace("Password: password", "Password: ''").replace("Index: 1", "Index: 9")
    (config / "data.yml").write_text(data)
    shutil.copy2(ROOT / module / "target" / f"TNE-{artifact}-0.1.5.4.jar", plugins)
    shutil.copy2(TEST_ROOT / "redis-plugin-probe/PluginSyncProbe.jar", plugins)
    return target


def launch(kind, target, phase):
    output = (target / f"integration-{phase}.log").open("w", encoding="utf-8")
    process = subprocess.Popen(
        [str(JAVA_HOME / "bin/java.exe"), "-Xms256M", "-Xmx1024M", "-XX:ActiveProcessorCount=4", "-Dterminal.jline=false", "-Dterminal.ansi=false", "-jar", f"{kind}.jar", "--nogui"],
        cwd=target, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT, text=True,
    )
    return {"process": process, "output": output, "log": target / f"integration-{phase}.log", "target": target, "kind": kind}


def log(server):
    return server["log"].read_text(encoding="utf-8", errors="replace")


def wait(server, pattern, timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        text = log(server)
        if "SYNC_PLUGIN_FAILURE" in text:
            raise RuntimeError(f"Probe failure: {server['log']}")
        match = re.search(pattern, text)
        if match:
            return match
        if server["process"].poll() is not None:
            raise RuntimeError(f"Server exited early: {server['log']}")
        time.sleep(.1)
    raise TimeoutError(f"Missing {pattern!r}: {server['log']}")


def command(server, text):
    server["process"].stdin.write(text + "\n")
    server["process"].stdin.flush()


def stop(server):
    if server["process"].poll() is None:
        command(server, "stop")
        server["process"].wait(timeout=150)
    server["output"].close()


def report(server, expected, expected_receipts):
    deadline = time.monotonic() + 40
    while time.monotonic() < deadline:
        command(server, "syncprobe report")
        time.sleep(.25)
        reports = re.findall(r"SYNC_PLUGIN_REPORT balance=([\d.]+) pending=(\d+).*?receipts=(\d+)", log(server))
        if reports and float(reports[-1][0]) == expected and int(reports[-1][1]) == 0 and int(reports[-1][2]) == expected_receipts:
            return
    raise TimeoutError(f"Balance did not converge to {expected}: {server['log']}")


def compile_probe():
    build = TEST_ROOT / "redis-plugin-probe"
    classes = build / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    jars = [ROOT / "PaperCore/target/TNE-Paper-0.1.5.4.jar", *list((TEST_ROOT / "paper263/libraries").rglob("*.jar"))]
    subprocess.run([str(JAVA_HOME / "bin/javac.exe"), "--release", "21", "-cp", os.pathsep.join(map(str, jars)), "-d", str(classes), str(ROOT / "tests/redis/PluginSyncProbe.java")], check=True)
    shutil.copy2(ROOT / "tests/redis/plugin-sync.yml", classes / "plugin.yml")
    subprocess.run([str(JAVA_HOME / "bin/jar.exe"), "--create", "--file", str(build / "PluginSyncProbe.jar"), "-C", str(classes), "."], check=True)


def pause_test_redis(milliseconds):
    arguments = [b"CLIENT", b"PAUSE", str(milliseconds).encode()]
    payload = b"*3\r\n" + b"".join(b"$" + str(len(arg)).encode() + b"\r\n" + arg + b"\r\n" for arg in arguments)
    with socket.create_connection(("127.0.0.1", REDIS_PORT), timeout=3) as connection:
        connection.sendall(payload)
        if not connection.recv(1024).startswith(b"+OK"):
            raise RuntimeError("Dedicated test Redis did not accept CLIENT PAUSE")


running = []
try:
    compile_probe()
    directories = [prepare(*server) for server in SERVERS]
    running = [launch(server[0], target, "initial") for server, target in zip(SERVERS, directories)]
    for server in running:
        wait(server, r"Done \(")
        command(server, "syncprobe init")
        wait(server, r"SYNC_PLUGIN_INIT balance=0(?:\.0+)? pending=0")
    parallel_started = time.monotonic()
    for server in running:
        command(server, "syncprobe deposit 200")
    for server in running:
        wait(server, r"SYNC_PLUGIN_DEPOSITS_DONE count=200")
    for server in running:
        report(server, 400, 200)
    parallel_elapsed = time.monotonic() - parallel_started
    print(f"FULL_PLUGIN_PARALLEL paper=400 folia=400 deposits=200+200 elapsed_seconds={parallel_elapsed:.3f}", flush=True)
    pause_test_redis(1500)
    command(running[0], "syncprobe deposit 100")
    queued = wait(running[0], r"SYNC_PLUGIN_DEPOSITS_DONE count=100 balance=500(?:\.0+)? pending=(\d+)")
    if int(queued.group(1)) < 1:
        raise RuntimeError("Shutdown phase did not exercise a queued Redis update")
    stop(running[0])
    time.sleep(2)
    report(running[1], 500, 200)
    running[0] = launch("paper", directories[0], "restart")
    wait(running[0], r"Done \(")
    report(running[0], 500, 300)
    report(running[1], 500, 200)
    print("FULL_PLUGIN_RESTART paper=500 folia=500 paper_receipts=300 folia_receipts=200 failures=0", flush=True)
finally:
    for server in running:
        stop(server)

for directory in directories:
    for logfile in directory.glob("integration-*.log"):
        text = logfile.read_text(encoding="utf-8", errors="replace")
        bad = re.findall(r"(?m)^.*(?:ERROR|Exception|SEVERE|SYNC_PLUGIN_FAILURE).*$", text)
        if bad:
            raise RuntimeError(f"Server errors in {logfile}: {bad[:5]}")
print("FULL_PLUGIN_SYNC_RESULT checks=parallel+shutdown+restart failures=0", flush=True)
(TEST_ROOT / "redis-plugin-validation.json").write_text(json.dumps({
    "result": "passed",
    "paper": "26.3 build249",
    "folia": "26.3 build6",
    "parallel_deposits": "200+200",
    "parallel_balances": [400, 400],
    "parallel_elapsed_seconds": round(parallel_elapsed, 3),
    "queued_at_shutdown": int(queued.group(1)),
    "restart_balances": [500, 500],
    "restart_receipts": [300, 200],
    "storage": "separate YAML databases; shared SQL persistence not exercised",
    "logs": [str(path) for directory in directories for path in directory.glob("integration-*.log")],
}, indent=2))
