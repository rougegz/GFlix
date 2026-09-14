#!/usr/bin/env python3
"""Global rewrite gate: runs every slice check. Exit 0 = green."""
import subprocess, sys, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
checks = ["tests/check_ext_core.py", "tests/check_android_runtime.py"]
failed = False
for check in checks:
    print(f"== {check} ==")
    proc = subprocess.run([sys.executable, str(ROOT / check)], capture_output=True, text=True)
    print(proc.stdout.strip())
    if proc.stderr.strip():
        print(proc.stderr.strip())
    if proc.returncode != 0:
        failed = True
if failed:
    print("FAIL verify_rewrite")
    sys.exit(1)
print("PASS verify_rewrite")
