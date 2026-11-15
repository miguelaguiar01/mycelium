"""Drive a Minecraft dedicated server from scripts.

Downloads the server jar named by Mojang's version manifest, starts it in a run
directory, sends console commands over stdin and waits for each batch to finish.
Used to build fixture and scenario worlds reproducibly. Standard library only.

The jar and run directories live under .mc/ at the repo root, which is not
checked in.
"""

from __future__ import annotations

import glob
import hashlib
import json
import os
import queue
import re
import subprocess
import threading
import time
import urllib.request
from pathlib import Path

MC_VERSION = "26.3"
MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

REPO_ROOT = Path(__file__).resolve().parents[2]
MC_HOME = REPO_ROOT / ".mc"


def _fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=120) as response:
        return response.read()


def ensure_server_jar(version: str = MC_VERSION) -> Path:
    """Return the cached server jar for `version`, downloading it if needed."""
    jar = MC_HOME / "server" / version / "server.jar"
    meta_path = jar.with_name("version.json")
    if not meta_path.exists():
        manifest = json.loads(_fetch(MANIFEST_URL))
        entry = next((v for v in manifest["versions"] if v["id"] == version), None)
        if entry is None:
            raise RuntimeError(f"version {version} is not in Mojang's manifest")
        meta_path.parent.mkdir(parents=True, exist_ok=True)
        meta_path.write_bytes(_fetch(entry["url"]))
    download = json.loads(meta_path.read_text())["downloads"]["server"]
    if not jar.exists() or hashlib.sha1(jar.read_bytes()).hexdigest() != download["sha1"]:
        data = _fetch(download["url"])
        if hashlib.sha1(data).hexdigest() != download["sha1"]:
            raise RuntimeError("server.jar does not match the manifest's SHA-1")
        jar.write_bytes(data)
    return jar


def find_java(major: int = 25) -> str:
    """Find a `java` binary of the given major version.

    MC_JAVA_HOME wins; otherwise the newest matching SDKMAN install is used.
    """
    home = os.environ.get("MC_JAVA_HOME")
    if home:
        return str(Path(home) / "bin" / "java")
    candidates = sorted(glob.glob(str(Path.home() / f".sdkman/candidates/java/{major}.*")))
    if not candidates:
        raise RuntimeError(
            f"no Java {major} found; install one with SDKMAN or set MC_JAVA_HOME"
        )
    return str(Path(candidates[-1]) / "bin" / "java")


DEFAULT_PROPERTIES = {
    # Nothing connects to these servers; keep them off the network.
    "server-ip": "127.0.0.1",
    "server-port": "25599",
    "online-mode": "false",
    "enable-status": "false",
    "enable-rcon": "false",
    "enable-query": "false",
    "level-name": "world",
    "level-seed": "mycelium",
    "sync-chunk-writes": "true",
    "pause-when-empty-seconds": "-1",
    "max-tick-time": "-1",
    "view-distance": "4",
    "simulation-distance": "4",
    "spawn-protection": "0",
}


class Server:
    """A running dedicated server. Use as a context manager."""

    def __init__(self, run_dir: Path, properties: dict[str, str] | None = None,
                 version: str = MC_VERSION, memory: str = "2G"):
        self.run_dir = Path(run_dir)
        self.properties = {**DEFAULT_PROPERTIES, **(properties or {})}
        self.version = version
        self.memory = memory
        self.log: list[str] = []
        self._lines: queue.Queue[str | None] = queue.Queue()
        self._marker = 0
        self._process: subprocess.Popen | None = None

    @property
    def world_dir(self) -> Path:
        return self.run_dir / self.properties["level-name"]

    def __enter__(self) -> "Server":
        self.start()
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        if exc_type is None:
            self.stop()
        else:
            self.kill()

    def start(self, timeout: float = 600) -> None:
        jar = ensure_server_jar(self.version)
        self.run_dir.mkdir(parents=True, exist_ok=True)
        # Running the server requires accepting Mojang's EULA (https://aka.ms/MinecraftEULA).
        (self.run_dir / "eula.txt").write_text("eula=true\n")
        (self.run_dir / "server.properties").write_text(
            "".join(f"{key}={value}\n" for key, value in sorted(self.properties.items()))
        )
        # The jar unpacks about 130 library files on start. One shared folder keeps them out of every run folder.
        self._process = subprocess.Popen(
            [find_java(), f"-Xmx{self.memory}", f"-DbundlerRepoDir={jar.parent / 'bundle'}", "-jar", str(jar), "nogui"],
            cwd=self.run_dir, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True, bufsize=1,
        )
        threading.Thread(target=self._pump, daemon=True).start()
        self._wait_for(re.compile(r"Done \(.*\)! For help"), timeout)

    def _pump(self) -> None:
        assert self._process and self._process.stdout
        for line in self._process.stdout:
            self._lines.put(line.rstrip("\n"))
        self._lines.put(None)

    def _wait_for(self, pattern: re.Pattern, timeout: float) -> list[str]:
        deadline = time.monotonic() + timeout
        seen: list[str] = []
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(f"server did not print {pattern.pattern!r}")
            try:
                line = self._lines.get(timeout=remaining)
            except queue.Empty:
                continue
            if line is None:
                raise RuntimeError("server exited:\n" + "\n".join(self.log[-40:]))
            self.log.append(line)
            seen.append(line)
            if pattern.search(line):
                return seen

    def run(self, *commands: str, timeout: float = 300) -> list[str]:
        """Send console commands and return the log lines they produced.

        Commands run in order on the server thread, so a trailing marker line
        tells us every command before it has finished.
        """
        assert self._process and self._process.stdin
        self._marker += 1
        marker = f"mycelium-marker-{self._marker}"
        for command in commands:
            self._process.stdin.write(command.strip() + "\n")
        self._process.stdin.write(f"say {marker}\n")
        self._process.stdin.flush()
        lines = self._wait_for(re.compile(re.escape(marker)), timeout)
        return lines[:-1]

    def wait_ticks(self, ticks: int) -> None:
        """Let the world run for about `ticks` game ticks (20 per second)."""
        time.sleep(ticks / 20)
        self.run()

    def sprint(self, ticks: int, timeout: float = 600) -> None:
        """Run exactly `ticks` game ticks as fast as possible. A frozen world is frozen again afterwards."""
        assert self._process and self._process.stdin
        self._process.stdin.write(f"tick sprint {ticks}\n")
        self._process.stdin.flush()
        self._wait_for(re.compile("Sprint completed"), timeout)

    def save(self) -> list[str]:
        return self.run("save-all flush")

    def stop(self, timeout: float = 300) -> None:
        if not self._process or self._process.poll() is not None:
            return
        assert self._process.stdin
        self._process.stdin.write("stop\n")
        self._process.stdin.flush()
        self._process.wait(timeout=timeout)
        while (line := self._lines.get(timeout=timeout)) is not None:
            self.log.append(line)

    def kill(self) -> None:
        if self._process and self._process.poll() is None:
            self._process.kill()
            self._process.wait()

    def errors(self) -> list[str]:
        """Log lines at WARN or ERROR level, or that carry a stack trace."""
        pattern = re.compile(r"/(WARN|ERROR|FATAL)\]|Exception|^\s+at ")
        return [line for line in self.log if pattern.search(line)]
