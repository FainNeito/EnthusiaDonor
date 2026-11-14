"""Launch an isolated real Java receiver, run the proxy suite, and stop it."""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import queue
import threading

parser = argparse.ArgumentParser()
parser.add_argument("--nginx", required=True)
parser.add_argument("--gson", required=True, type=Path)
parser.add_argument("--java", default="java")
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
work = root / "target" / "route-fixtures"
work.mkdir(parents=True, exist_ok=True)
classpath = os.pathsep.join([str(root / "target" / "classes"), str(args.gson.resolve())])
with (work / "receiver-stderr.log").open("w") as errors:
    receiver = subprocess.Popen(
        [args.java, "--class-path", classpath, str(Path(__file__).with_name("ReceiverFixture.java"))],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=errors, text=True,
        creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    try:
        startup = queue.Queue()
        threading.Thread(target=lambda: startup.put(receiver.stdout.readline()), daemon=True).start()
        port = startup.get(timeout=15).strip()
        if not port.isdecimal():
            raise RuntimeError("Fixture receiver failed to start; inspect receiver-stderr.log")
        result = subprocess.run([sys.executable, str(Path(__file__).with_name("test_route.py")),
                                 "--nginx", args.nginx, "--work", str(work), "--receiver-port", port])
    finally:
        if receiver.poll() is None:
            receiver.stdin.write("\n")
            receiver.stdin.flush()
            try:
                receiver.wait(timeout=5)
            except subprocess.TimeoutExpired:
                receiver.terminate()
                receiver.wait(timeout=5)
    raise SystemExit(result.returncode)
