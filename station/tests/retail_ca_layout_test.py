"""Exercise the generated mount probe using synthetic sysfs/MTD; never mounts the host."""
from pathlib import Path
import tempfile,os,subprocess
root=Path(__file__).resolve().parents[1]
original=(root/'build/retail-ca-tests/mount.sh').read_text()
for case in ['already-attached','attach-needed','missing-system','wrong-volume','missing-ca','bootenv-missing']:
 with tempfile.TemporaryDirectory() as tmp:
  base=Path(tmp);bin=base/'bin';bin.mkdir();sys=base/'sys';sys.mkdir();mounts=base/'mounts';mounts.write_text('ramfs / ramfs rw 0 0\n')
  mtd=base/'mtd';mtd.write_text('dev: size erasesize name\nmtd5: 00100000 00001000 "'+('other' if case=='missing-system' else 'system')+'"\n')
  factory=base/'factory-version';factory.write_text('0.6.1.4.123.20241111.factory-6\n')
  target=base/'mounted';q=target/'system/vendor/quasar';(q/'certs').mkdir(parents=True);(q/'version_full').write_text('0.369.1.50.retail\n')
  if case!='missing-ca':(q/'certs/ca-certificates.crt').write_text('fixture-ca')
  calls=base/'calls'
  def tool(name,text):
   file=bin/name;file.write_text('#!/bin/sh\n'+text+'\n');file.chmod(0o755)
  tool('id','echo 0')
  tool('fw_printenv','exit 1' if case=='bootenv-missing' else "printf 'upgrade_step=2\\nboot_to_recovery=0\\n'")
  tool('sha256sum','exec shasum -a 256 "$@"')
  tool('mount','echo mount >> "$CALLS"')
  tool('umount','echo umount >> "$CALLS"')
  def add_ubi():
   (sys/'ubi7').mkdir();(sys/'ubi7/mtd_num').write_text('5\n');(sys/'ubi7_0').mkdir();(sys/'ubi7_0/name').write_text('other\n' if case=='wrong-volume' else 'rootfs\n')
  if case!='attach-needed':add_ubi()
  tool('ubiattach','echo attach >> "$CALLS"\nmkdir -p "$SYS/ubi7" "$SYS/ubi7_0"\necho 5 > "$SYS/ubi7/mtd_num"\necho rootfs > "$SYS/ubi7_0/name"')
  script=original.replace('/sys/class/ubi',str(sys)).replace('/proc/mtd',str(mtd)).replace('/proc/mounts',str(mounts)).replace('cat /system/vendor/quasar/version_full','cat '+str(factory)).replace('/mnt/alicebridge-retail-ca',str(target))
  result=subprocess.run(['/bin/sh'],input=script,text=True,capture_output=True,env=os.environ|{'PATH':str(bin)+':/usr/bin:/bin','CALLS':str(calls),'SYS':str(sys)})
  log=calls.read_text() if calls.exists() else ''
  if case in ['already-attached','attach-needed']:
   assert result.returncode==0,(case,result.stdout,result.stderr)
   assert 'AB_SYSTEM_MTD=5' in result.stdout and 'AB_SYSTEM_UBI=ubi7' in result.stdout
   assert ('attach' in log)==(case=='attach-needed')
  else:
   assert result.returncode!=0,(case,result.stdout,result.stderr)
   if case=='missing-ca':assert 'umount' in log,log
   else:assert 'mount' not in log,log
  print('PASS retail CA layout:',case)
