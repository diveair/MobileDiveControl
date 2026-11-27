"""Wired ADB transport for the Depth Lab Android build. Standard library only."""
import json
import os
from pathlib import Path
import queue
import shutil
import socket
import struct
import subprocess
import threading
import time

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com.mobiledivecontrol.depthlab"
APK = ROOT / "app/build/outputs/apk/depthLab/app-depthLab.apk"
REMOTE = "localabstract:divecontrol_depth_lab"
FRAME = struct.Struct("!cI")
PORT = 47391


def adb_path():
    candidates = [
        ROOT / "tmp/android-sdk/platform-tools/adb.exe",
        Path(os.environ.get("LOCALAPPDATA", ".")) / "Android/Sdk/platform-tools/adb.exe",
    ]
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        candidates.insert(0, Path(sdk) / "platform-tools/adb.exe")
    return next((str(p) for p in candidates if p.is_file()), shutil.which("adb"))


def adb(*args, timeout=20):
    binary = adb_path()
    if not binary:
        raise RuntimeError("Android platform-tools are missing. Install them or set ANDROID_HOME.")
    run = subprocess.run([binary, "-d", *args], capture_output=True, text=True, timeout=timeout,
                         creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    if run.returncode:
        raise RuntimeError((run.stderr.strip() or run.stdout.strip() or "ADB command failed.").splitlines()[-1])
    return run.stdout.strip()


def receive(sock, size):
    data = bytearray()
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise ConnectionError("USB session closed.")
        data.extend(chunk)
    return bytes(data)


class Controller:
    def __init__(self):
        self.events = queue.Queue()
        self.keys = queue.Queue()
        self.stop = threading.Event()
        self.lock = threading.Lock()
        self.depth_mm = 0
        self.missing = False
        self.housing_depth = False
        self.thread = None

    def set_depth(self, meters, missing=False, housing_depth=False):
        if not 0 <= meters <= 90:
            raise ValueError("Choose a depth from 0 to 90 m.")
        with self.lock:
            self.depth_mm = round(meters * 1000)
            self.missing = missing
            self.housing_depth = housing_depth

    def start(self, reinstall=False):
        if self.thread and self.thread.is_alive():
            return
        self.stop.clear()
        self.keys = queue.Queue()
        self.thread = threading.Thread(target=self.run, args=(reinstall,), daemon=True)
        self.thread.start()

    def run(self, reinstall):
        mapped = False
        try:
            # Bind the PC's loopback exclusively before owning the fixed reverse mapping.
            with socket.socket() as server:
                if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
                    server.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
                server.bind(("127.0.0.1", PORT))
                server.listen(1)
                server.settimeout(0.35)
                serial = adb("get-serialno")
                if adb("shell", "settings", "get", "global", "adb_wifi_enabled") == "1":
                    raise RuntimeError("Turn off Wireless debugging on the phone and keep USB debugging enabled.")
                for key in ("service.adb.tcp.port", "persist.adb.tcp.port"):
                    port = adb("shell", "getprop", key)
                    if port.isdigit() and int(port) > 0:
                        raise RuntimeError("ADB is in TCP mode. Switch the handset back to USB debugging.")
                installed = adb("shell", "pm", "list", "packages", PACKAGE).splitlines()
                if reinstall or "package:" + PACKAGE not in installed:
                    if not APK.is_file():
                        raise RuntimeError("Build the lab APK with gradlew.bat :app:assembleDepthLab first.")
                    self.events.put(("status", "Installing Depth Lab on " + serial))
                    if "Success" not in adb("install", "-r", "-g", str(APK), timeout=150):
                        raise RuntimeError("The phone did not confirm the lab installation.")
                adb("reverse", REMOTE, "tcp:" + str(PORT))
                mapped = True
                result = adb("shell", "am", "start", "-n", PACKAGE + "/com.mobiledivecontrol.MainActivity")
                if "Error" in result:
                    raise RuntimeError(result)
                self.events.put(("status", "Waiting for the phone. Open Depth Lab and finish its welcome screen."))
                deadline = time.monotonic() + 120
                while not self.stop.is_set():
                    if time.monotonic() >= deadline:
                        raise TimeoutError("Phone did not connect. Unlock it, enable Bluetooth and finish the lab welcome screen.")
                    try:
                        sock, _ = server.accept()
                    except socket.timeout:
                        continue
                    with sock:
                        sock.settimeout(3)
                        if receive(sock, 7) != b"DCLAB2\n":
                            raise RuntimeError("Click Update lab APK to install the matching controller version.")
                        self.events.put(("connected", serial))
                        self.stream(sock)
                    break
        except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
            if not self.stop.is_set():
                self.events.put(("error", str(error)))
        finally:
            if mapped:
                try:
                    adb("reverse", "--remove", REMOTE, timeout=4)
                except (OSError, RuntimeError, subprocess.SubprocessError):
                    pass
            self.events.put(("closed", None))

    def stream(self, sock):
        def exchange(command, value):
            sock.sendall(FRAME.pack(command, value))
            reply, actual = FRAME.unpack(receive(sock, FRAME.size))
            if reply != command:
                raise RuntimeError("Depth Lab returned an unexpected acknowledgment.")
            return actual

        while not self.stop.is_set():
            try:
                key = self.keys.get_nowait()
            except queue.Empty:
                key = None
            if key is not None:
                exchange(b"K", key)
            with self.lock:
                depth, missing, housing_depth = self.depth_mm, self.missing, self.housing_depth
            if housing_depth:
                exchange(b"R", 0)
            elif missing:
                exchange(b"P", 0)
            else:
                exchange(b"D", depth)
            size = exchange(b"S", 0)
            if not 0 < size <= 4096:
                raise RuntimeError("Invalid phone status frame.")
            self.events.put(("sample", json.loads(receive(sock, size))))
            self.stop.wait(0.2)
