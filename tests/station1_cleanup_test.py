"""Execute cleanup against sandbox files: retain backups on failed verification and pending OTA."""
import os,pathlib,shutil,subprocess,tempfile
root=pathlib.Path(__file__).resolve().parents[1]
script=(root/'build/station1-tests/cleanup.sh').read_text()
with tempfile.TemporaryDirectory() as tmp:
 b=pathlib.Path(tmp);bb=b/'bb';gp=b/'getprop';uid=b/'id'
 bb.write_text('#!/bin/sh\nc=$1; shift\ncase "$c" in sha256sum) exec shasum -a 256 "$@";; *) exec "$c" "$@";; esac\n');bb.chmod(0o755)
 gp.write_text('#!/bin/sh\ncase "$1" in ro.board.platform) echo tulip;; *) echo 20261013;; esac\n');gp.chmod(0o755)
 uid.write_text('#!/bin/sh\necho 0\n');uid.chmod(0o755)
 partition=b/'partition';image=b/'image';backup=b/'backup';cache=b/'cache';cache.mkdir();rec=cache/'recovery';rec.mkdir()
 ota=cache/'update_2.369.5.46.4778391386.20261013.52.zip'
 adapted=script.replace('/system/bin/busybox-smp',str(bb)).replace('/dev/block/mmcblk0p6',str(partition)).replace('/data/local/tmp/alicebridge-station1/boot-before.img',str(backup)).replace('/data/local/tmp/boot-root.img',str(image)).replace('/cache/',str(cache)+'/')
 assert '/dev/block/' not in adapted and '/data/local/' not in adapted
 env=os.environ|{'PATH':str(b)+':'+os.environ['PATH']}
 for case in ('valid','wrong-active-boot','corrupt-backup','pending-ota','failed-ota','already-cleaned'):
  shutil.copyfile(root/'private/station1-root/boot-root.img',partition);shutil.copyfile(partition,image);shutil.copyfile(root/'private/station1-root/boot.img',backup)
  ota.write_text('old installed archive');(rec/'last_install').write_text(str(ota)+'\n'+('0' if case=='failed-ota' else '1')+'\n');(rec/'command').unlink(missing_ok=True)
  if case=='wrong-active-boot':partition.write_text('unverified')
  if case=='corrupt-backup':backup.write_text('bad')
  if case=='pending-ota':(rec/'command').write_text('--update_package='+str(ota))
  if case=='already-cleaned':image.unlink();backup.unlink();ota.unlink()
  result=subprocess.run(['sh'],input=adapted,text=True,capture_output=True,env=env)
  if case in ('wrong-active-boot','corrupt-backup'):assert result.returncode!=0 and backup.exists() and image.exists() and ota.exists(),case
  else:
   assert result.returncode==0 and not backup.exists() and not image.exists(),(case,result.stderr)
   assert ota.exists() or case=='already-cleaned',case
  assert (root/'private/station1-root/boot.img').exists()
  print('PASS: cleanup',case)
