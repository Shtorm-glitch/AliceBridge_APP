#!/usr/bin/env python3
"""Relocate only fixed-size package path strings in pinned upstream ARM64 executables.

All eight embedded, unsigned kernel module images carry the bootstrap path. Keeping the
replacement exactly the same length preserves ELF section sizes, offsets and relocations.
Matching source edits are in third_party/dfroot/{jni/bootstrap.c,lkm/dfroot.c}.
"""
import hashlib
from pathlib import Path
import sys
import zipfile

APK_SHA256 = '9e193ffdaada8577e14a04fb19403256b3fa4991fae1331c7956e083c098a7b1'
root = Path(__file__).resolve().parents[1]
apk = Path(sys.argv[1])
assert hashlib.sha256(apk.read_bytes()).hexdigest() == APK_SHA256, 'Unexpected DFRoot APK'
old, new = b'/data/user_de/0/df.root/', b'/data/user_de/0/ab.root/'
assert len(old) == len(new)
with zipfile.ZipFile(apk) as archive:
    for name, count in [('libbootstrap.so', 2), ('libdfroot.so', 8)]:
        data = archive.read('lib/arm64-v8a/' + name)
        assert data[:4] == b'\x7fELF'
        assert data.count(old) == count, 'Unexpected upstream layout'
        assert b'~Module signature appended~' not in data, 'Signed modules cannot be relocated'
        updated = data.replace(old, new)
        assert len(updated) == len(data) and old not in updated
        destination = root / 'app/src/main/jniLibs/arm64-v8a' / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(updated)
        print(name, hashlib.sha256(updated).hexdigest())
