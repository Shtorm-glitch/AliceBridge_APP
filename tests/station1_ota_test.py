"""Test the actual Station 1 OTA blocker on regular sandbox files, including shell invocation."""
import pathlib,tempfile,subprocess,os,hashlib
root=pathlib.Path(__file__).resolve().parents[1]
source=(root/'app/src/main/assets/scripts/station1-disable-ota.sh').read_text()
original=b'mkdir -p /cache/recovery\necho "boot-recovery\\n--update_package=$1" > /cache/recovery/command\nreboot recovery\n'
assert hashlib.sha256(original).hexdigest()=='5438af4718ab9bbc588b5f64c6b0ceb0732777c56bf6819be22d56befb8a5eb2'
with tempfile.TemporaryDirectory() as tmp:
 b=pathlib.Path(tmp);system=b/'system';data=b/'data';system.mkdir();data.mkdir();script=system/'device_ota_update.sh';backup=data/'station1-device-ota-update.sh'
 bb=b/'bb';bb.write_text('#!/bin/sh\nc=$1;shift\ncase "$c" in sha256sum) exec shasum -a 256 "$@";; id) echo 0;; *) exec "$c" "$@";; esac\n');bb.chmod(0o755)
 gp=b/'getprop';gp.write_text('#!/bin/sh\necho tulip\n');gp.chmod(0o755)
 mount=b/'mount';mount.write_text('#!/bin/sh\nexit 0\n');mount.chmod(0o755)
 mounts=b/'mounts';mounts.write_text('dummy /system ext4 ro 0 0\n')
 adapted=source.replace('/system/bin/busybox-smp',str(bb)).replace('/system/bin/mount',str(mount)).replace('/system/vendor/quasar/device_ota_update.sh',str(script)).replace('/data/quasar/alicebridge-backup',str(data)).replace('/proc/mounts',str(mounts)).replace('/system/bin/sh "$file"','/bin/sh "$file"').replace('\n    sync\n','\n    true\n')
 env=os.environ|{'PATH':str(b)+':'+os.environ['PATH']}
 for case in ('valid','idempotent','unknown-script','bad-backup'):
  if case!='idempotent':script.write_bytes(original);backup.unlink(missing_ok=True)
  if case=='unknown-script':script.write_text('echo do-not-modify\n')
  if case=='bad-backup':backup.write_text('unverified')
  before=script.read_bytes();r=subprocess.run(['sh'],input=adapted,text=True,capture_output=True,env=env)
  if case in ('valid','idempotent'):
   assert r.returncode==0 and 'AB_STATION1_OTA_BLOCKED' in r.stdout,(case,r.stderr)
   assert backup.read_bytes()==original
   # Exercise the installed script through sh; root-readable chmod 000 alone is insufficient.
   blocked=subprocess.run(['sh',str(script)],capture_output=True)
   assert blocked.returncode==1 and b'OTA blocked' in blocked.stderr
  else:assert r.returncode!=0 and script.read_bytes()==before,(case,r.stderr)
  print('PASS: Station 1 OTA',case)
