#!/usr/bin/env python3
"""Package the pinned Android adb executable with app-specific environment names.

Only two equal-length getenv key literals are relocated. This leaves ELF offsets
and instructions unchanged and keeps the parent environment untouched.
"""
import hashlib
from pathlib import Path
import sys

source = Path(sys.argv[1])
data = source.read_bytes()
assert hashlib.sha256(data).hexdigest() == '47ea035fa5ed57f6149a2b025bbbd4b21584c355c05d0400416804715e4c12de'
for old, new in [(b'HOME\0', b'ABHM\0'), (b'TMPDIR\0', b'AB_TMP\0')]:
    assert len(old) == len(new) and data.count(old) == 1
    data = data.replace(old, new)
output = Path(__file__).resolve().parents[1] / 'app/src/main/jniLibs/arm64-v8a/libadb.so'
output.write_bytes(data)
print(hashlib.sha256(data).hexdigest(), output.name)
