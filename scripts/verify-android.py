#!/usr/bin/env python3
"""Build and verify Hermes Pocket; installs only on an explicitly named emulator."""
import argparse
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "apps" / "android"
PACKAGE = "com.everyonesoftware.hermespocket"

def run(*args, capture=False):
    return subprocess.run(args, cwd=ANDROID, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None,
                          stderr=subprocess.STDOUT).stdout or ""

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="Optional task-owned emulator serial; never a personal device")
    args = parser.parse_args()
    if args.serial:
        if not args.serial.startswith("emulator-"):
            parser.error("Only a task-owned emulator may receive test fixtures.")
        qemu = run("adb", "-s", args.serial, "shell", "getprop", "ro.kernel.qemu", capture=True).strip()
        if qemu != "1":
            parser.error("Target is not an Android emulator.")
    run("bash", "gradlew", ":app:testDebugUnitTest", ":app:lintDebug", ":app:assembleDebug",
        ":app:assembleDebugAndroidTest", "--console=plain", "--max-workers=2")
    if args.serial:
        adb = ("adb", "-s", args.serial)
        run(*adb, "install", "-r", str(ANDROID / "app/build/outputs/apk/debug/app-debug.apk"))
        run(*adb, "install", "-r", str(ANDROID / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
        output = run(*adb, "shell", "am", "instrument", "-w", "-r",
                     f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner", capture=True)
        print(output)
        match = re.search(r"OK \((\d+) tests?\)", output)
        if not match or int(match.group(1)) < 1 or any(s in output for s in
                ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed", "shortMsg=")):
            raise SystemExit("Instrumentation did not report passing nonzero tests.")
        print(f"Verified {match.group(1)} instrumented tests on {args.serial}.")
    return 0

if __name__ == "__main__":
    try:
        sys.exit(main())
    except (subprocess.CalledProcessError, FileNotFoundError) as error:
        sys.exit(str(error))
