"""Compile and run OnlineItemProbe twice on a prepared disposable Paper/Folia server."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--server", required=True)
    parser.add_argument("--java-home", required=True)
    parser.add_argument("--plugin-jar", default="PaperCore/target/TNE-Paper-0.1.5.4.jar")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    test_root = (repo / ".testserver").resolve()
    server = (repo / args.server).resolve()
    if not server.is_relative_to(test_root) or server == test_root:
        raise ValueError("Server must be a dedicated directory inside .testserver")
    properties = dict(line.strip().split("=", 1) for line in (server / "server.properties").read_text().splitlines()
                      if "=" in line and not line.startswith("#"))
    if properties.get("server-ip") != "127.0.0.1" or properties.get("online-mode") != "false":
        raise ValueError("Server must use server-ip=127.0.0.1 and online-mode=false")
    if properties.get("white-list") != "false":
        raise ValueError("Set white-list=false explicitly on this disposable loopback test server")
    installed = list((server / "plugins").glob("TNE-*.jar"))
    if len(installed) != 1:
        raise ValueError("Expected exactly one installed TNE JAR")
    incompatible = {"SaveProbe", "LifecycleProbe", "ItemRegression", "InventoryAudit", "DeleteOrderProbe",
                    "FoliaSchedulerProbe", "ReceiptLoadProbe"}
    if any(path.stem in incompatible for path in (server / "plugins").glob("*.jar")):
        raise ValueError("Remove other server-stopping probes before running this test")
    artifact = (repo / args.plugin_jar).resolve()
    java_home = Path(args.java_home)
    java = java_home / "bin" / "java.exe"
    classes = Path(tempfile.mkdtemp(prefix="online-probe-", dir=test_root))
    classpath = os.pathsep.join([str(artifact)] + [str(path) for path in (server / "libraries").rglob("*.jar")])
    subprocess.run([str(java_home / "bin" / "javac.exe"), "--release", "21", "-cp", classpath, "-d", str(classes),
                    str(repo / "tests/storage/OnlineItemProbe.java"), str(repo / "tests/support/ProbeScheduler.java")], check=True)
    shutil.copyfile(repo / "tests/storage/online-item-plugin.yml", classes / "plugin.yml")
    probe_jar = classes.parent / (classes.name + ".jar")
    subprocess.run([str(java_home / "bin" / "jar.exe"), "--create", "--file", str(probe_jar), "-C", str(classes), "."], check=True)
    shutil.copyfile(artifact, installed[0])
    shutil.copyfile(probe_jar, server / "plugins/OnlineItemProbe.jar")
    server_jars = [server / name for name in ("paper.jar", "folia.jar") if (server / name).exists()]
    if len(server_jars) != 1:
        raise ValueError("Expected one paper.jar or folia.jar")
    for phase in (1, 2):
        log_path = server / ("online-item-validation-" + str(phase) + ".log")
        client_path = server / ("online-client-" + str(phase) + ".log")
        with log_path.open("w", encoding="utf-8") as log, client_path.open("w", encoding="utf-8") as client_log:
            process = subprocess.Popen([str(java), "-Xms256M", "-Xmx1024M", "-Dterminal.jline=false", "-Dterminal.ansi=false",
                                        "-jar", str(server_jars[0]), "--nogui"], cwd=server, stdout=log, stderr=subprocess.STDOUT)
            client = subprocess.Popen([sys.executable, str(repo / "tests/storage/raw-client.py"), "--port", properties["server-port"],
                                       "--timeout", "100"], cwd=server, stdout=client_log, stderr=subprocess.STDOUT)
            try:
                result = process.wait(timeout=120)
                client_result = client.wait(timeout=10)
            finally:
                for child in (process, client):
                    if child.poll() is None:
                        child.kill()
                        child.wait(timeout=10)
        output = log_path.read_text(encoding="utf-8", errors="replace")
        marker = "ONLINE_ITEM_PROBE_RESULT phase=" + str(phase) + " failures=0"
        if result or client_result or marker not in output:
            raise RuntimeError("Online item test failed; inspect " + str(log_path) + " and " + str(client_path))
        if "Error occurred while disabling TheNewEconomy" in output:
            raise RuntimeError("TNE shutdown failed; inspect " + str(log_path))
        for line in output.splitlines():
            if "[OnlineItemProbe]" in line:
                print(line, flush=True)


if __name__ == "__main__":
    main()
