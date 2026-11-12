#!/usr/bin/env python3
"""needed.py DIRS FILE...: prints "name path" for every shared library the FILEs need, directly or through each other,
as a loader searching DIRS (colon-separated, in order) finds it. What ldd says, without running the files, so it works
for arm64 files on any machine. Exits 1, naming them, if some aren't in DIRS."""
import os
import re
import subprocess
import sys

dirs = sys.argv[1].split(':')
todo = sys.argv[2:]
read = set()
found = {}
missing = []
while todo:
    path = todo.pop()
    real = os.path.realpath(path)
    if real in read:
        continue
    read.add(real)
    dynamic = subprocess.run(['readelf', '-d', '-W', path], capture_output=True, text=True, check=True).stdout
    for name in re.findall(r'\(NEEDED\)\s+Shared library: \[([^\]]+)\]', dynamic):
        if name in found:
            continue
        hit = next((os.path.join(d, name) for d in dirs if os.path.exists(os.path.join(d, name))), None)
        if hit is None:
            missing.append(f'{name} (needed by {path})')
            continue
        found[name] = hit
        todo.append(hit)

for name in sorted(found):
    print(name, found[name])
if missing:
    print('unresolved:', *sorted(set(missing)), sep='\n  ', file=sys.stderr)
    sys.exit(1)
