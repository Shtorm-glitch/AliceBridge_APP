"""Run the generated Mini3 CA/Wi-Fi patch on temporary files, with no mounts or device writes."""
from pathlib import Path
import tempfile,os,subprocess,stat
root=Path(__file__).resolve().parents[1]
original=(root/'build/retail-ca-tests/write.sh').read_text()
bundle=(root/'src/main/assets/retail-ca/lite2-bundle.pem').read_bytes()
for case in ['existing-wifi','missing-wifi','corrupt-candidate','symlink-ca','symlink-wifi']:
 with tempfile.TemporaryDirectory() as tmp:
  b=Path(tmp);tools=b/'bin';tools.mkdir();retail=b/'retail';q=retail/'system/vendor/quasar/certs';q.mkdir(parents=True);stage=b/'stage';stage.mkdir();data=b/'data';data.mkdir()
  ca=q/'ca-certificates.crt';ca.write_bytes(b'original CA');outside=b/'outside';outside.write_bytes(b'untouched')
  (stage/'ca.new').write_bytes(b'bad' if case=='corrupt-candidate' else bundle)
  (stage/'wifi.new').write_text('ctrl_interface=/var/run/wpa_supplicant\nupdate_config=0\nnetwork={\n ssid="new-network"\n psk="fixture-password"\n}\n')
  wifi=data/'wpa_supplicant.conf'
  if case=='existing-wifi':wifi.write_text('ctrl_interface=/var/run/wpa_supplicant\nupdate_config=1\nnetwork={\n ssid="existing-network"\n psk="existing-password"\n}\n')
  if case=='symlink-ca':ca.unlink();ca.symlink_to(outside)
  if case=='symlink-wifi':wifi.symlink_to(outside)
  def tool(name,text):
   p=tools/name;p.write_text('#!/bin/sh\n'+text+'\n');p.chmod(0o755)
  tool('sha256sum','exec shasum -a 256 "$@"');tool('chown','exit 0');tool('sync','exit 0')
  script=original.replace('/mnt/alicebridge-retail-ca',str(retail)).replace('/bkupgrade/data/local/tmp/alicebridge-retail-ca',str(stage)).replace('/bkupgrade/data/quasar',str(data/'quasar')).replace('/bkupgrade/data/wpa_supplicant.conf',str(wifi))
  result=subprocess.run(['/bin/sh'],input=script,text=True,capture_output=True,env=os.environ|{'PATH':str(tools)+':/usr/bin:/bin'})
  if case in ['existing-wifi','missing-wifi']:
   assert result.returncode==0,(case,result.stdout,result.stderr)
   assert ca.read_bytes()==bundle and ca.with_name(ca.name+'.orig').read_bytes()==b'original CA'
   assert 'update_config=0' in wifi.read_text() and 'update_config=1' not in wifi.read_text()
   assert ('existing-network' in wifi.read_text())==(case=='existing-wifi')
   assert stat.S_IMODE(wifi.stat().st_mode)==0o600
   assert (data/'quasar/activate_adb_quasmodrom').is_file() and (data/'quasar/activate_network_adb').is_file()
  else:
   assert result.returncode!=0,(case,result.stdout,result.stderr)
   assert outside.read_bytes()==b'untouched'
   if case=='corrupt-candidate':assert ca.read_bytes()==b'original CA'
  print('PASS retail CA write:',case)
