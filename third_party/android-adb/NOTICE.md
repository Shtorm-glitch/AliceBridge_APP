Android ADB executable, supplied separately under Apache License 2.0.

Source of the packaged Android ARM64 build:
https://github.com/tytydraco/LADB/tree/60f48029cf9d8e0bc848ca41a7bd76694d4ab796/app/src/main/jniLibs

Only the separately licensed adb executable and its Apache license were copied;
the LADB application code was not incorporated.

Original SHA-256: 47ea035fa5ed57f6149a2b025bbbd4b21584c355c05d0400416804715e4c12de

Local modification: equal-length environment-name literals HOME → ABHM and
TMPDIR → AB_TMP, to use private application directories without changing the
parent process's standard environment. ELF offsets and instructions are unchanged.
Reproduce with tools/prepare_adb.py and the pinned upstream executable.

Patched SHA-256: 634cd83c05d5178d0076d79b8960c23dd17ab2092654a105faf34c75dacb8f80

The adb client/server is run as a separate process. Server IPC uses a filesystem
Unix socket inside AliceBridge's private directory. Keys are kept in its own
adb-home directory. kill-server addresses only that private server.
