"""Execute the app's Wi-Fi preparation against multiple supplicant layouts; no network changes."""
import pathlib,tempfile,subprocess,os
root=pathlib.Path(__file__).resolve().parents[1]
script=(root/'build/station-scripts/home-wifi.sh').read_text()
with tempfile.TemporaryDirectory() as tmp:
 b=pathlib.Path(tmp);bin=b/'bin';bin.mkdir();data=b/'data';data.mkdir();proc=b/'proc';proc.mkdir();pid=proc/'400';pid.mkdir()
 def tool(name,text):
  p=bin/name;p.write_text('#!/bin/sh\n'+text);p.chmod(0o755)
 tool('id','echo 0\n')
 tool('wpa_cli','case "$3" in status) echo id=7;; add_network) echo 8;; list_networks) printf "network id / ssid / bssid / flags\\n7\\told-network\\tany\\t[CURRENT]\\n";; *) echo OK;; esac\n')
 tool('start-stop-daemon','echo invoked > "'+str(b/'started')+'"\n')
 candidates=['/bkupgrade/data/wpa_supplicant.conf','/data/wpa_supplicant.conf','/data/misc/wifi/wpa_supplicant.conf','/etc/wpa_supplicant.conf','/etc/wpa_supplicant/wpa_supplicant.conf']
 adapted=script.replace('/data/quasar',str(data)).replace('/proc/',str(proc)+'/')
 mapped=[]
 for i,path in enumerate(candidates):
  file=b/('candidate-'+str(i));mapped.append(file);adapted=adapted.replace(path,str(file))
 env=os.environ|{'PATH':str(bin)+':'+os.environ['PATH']}
 for case in ('active-process-path','mini-fallback','controller-only'):
  import shutil
  for f in mapped:f.unlink(missing_ok=True)
  shutil.rmtree(data/'alicebridge-backup',ignore_errors=True)
  (pid/'cmdline').write_bytes(b'/usr/sbin/wpa_supplicant\x00-iwlan0\x00-c'+str(b/'active.conf').encode()+b'\x00')
  active=b/'active.conf';active.unlink(missing_ok=True)
  if case=='active-process-path':active.write_text('synthetic original config')
  if case=='mini-fallback':mapped[1].write_text('synthetic original config')
  r=subprocess.run(['sh'],input=adapted,text=True,capture_output=True,env=env)
  assert r.returncode==0,(case,r.stderr)
  assert (b/'started').exists(),case
  backup=data/'alicebridge-backup/wpa_supplicant.conf'
  if case=='controller-only':assert not backup.exists() and (data/'alicebridge-backup/wifi-profiles.txt').exists()
  else:assert backup.read_text()=='synthetic original config' and backup.stat().st_mode & 0o777==0o600
  print('PASS: home Wi-Fi',case)
