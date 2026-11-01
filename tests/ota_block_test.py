"""Exercise app-generated OTA scripts on regular sandbox files, including Midi's symlink."""
import os,pathlib,subprocess,tempfile
root=pathlib.Path(__file__).resolve().parents[1]
scripts=root/'build/station-scripts'
with tempfile.TemporaryDirectory() as tmp:
 b=pathlib.Path(tmp);q=b/'quasar';q.mkdir();bk=b/'backup';bk.mkdir();binpath=b/'bin';binpath.mkdir();missing=b/'missing'
 sh=q/'device_ota_update.sh';key=q/'ota_script.pub';target=binpath/'yandex-apply-ota';link=binpath/'apply_packed_ota';link.symlink_to(target)
 def adapt(s):
  for old,new in [('/system/vendor/quasar',q),('/data/quasar/alicebridge-backup',bk),('/usr/bin/apply_packed_ota',link),('/bkupgrade/ota',missing)]:s=s.replace(old,str(new))
  return s
 def script(name,start):
  s=(scripts/name).read_text();s=s[s.index(start):];return 'set -eu\n'+adapt(s).replace('\nsync\n','\ntrue\n')
 primary=script('ota.sh','ab_count=0');fallback=script('ota-fallback.sh','ab_q=')
 def run(s):return subprocess.run(['sh'],input=s,text=True,capture_output=True)
 for f in [sh,key,target]:f.write_text('# original\n');f.chmod(0o755)
 r=run(primary);assert r.returncode==0,(r.stdout,r.stderr)
 assert sh.stat().st_mode&0o777==0 and target.stat().st_mode&0o777==0
 assert '755 '+str(link) in (bk/'ota-modes.txt').read_text()
 r=run(primary);assert r.returncode==0,(r.stdout,r.stderr)
 print('PASS: Midi symlink target disabled and repeat preserves original mode')
 # Harness runs unprivileged; restore read access that device UID 0 already has.
 sh.chmod(0o600)
 before_sh=sh.read_bytes();before_key=key.read_bytes()
 r=run(fallback);assert r.returncode==0,(r.stdout,r.stderr)
 assert (bk/'device_ota_update.sh.before-fallback').read_bytes()==before_sh
 assert (bk/'ota_script.pub.before-fallback').read_bytes()==before_key
 assert sh.read_text()=='#!/bin/sh\n# AliceBridge OTA blocked\nexit 0\n',repr(sh.read_text())
 assert 'BEGIN RSA PUBLIC KEY' in key.read_text()
 # Already disabled chmod targets coexist with fallback; do not require mutable JSON.
 inspect=adapt((scripts/'inspect.sh').read_text().split('        ab_analytics=1')[0].split('ab_analytics=1')[0])
 r=run(inspect);assert r.returncode==0 and 'AB_OTA=1' in r.stdout,(r.stdout,r.stderr)
 r=run(fallback);assert r.returncode==0 and (bk/'device_ota_update.sh.before-fallback').read_bytes()==before_sh
 sh.write_text('# changed outside AliceBridge\n')
 r=run(inspect);assert 'AB_OTA=0' in r.stdout,r.stdout
 print('PASS: fallback backups, exact stub, repeat, inspection and drift rejection')

 sh.write_text('# original untouched\n');key.unlink()
 r=run(fallback);assert r.returncode!=0 and sh.read_text()=='# original untouched\n'
 print('PASS: missing OTA key fails before modifying installer')
