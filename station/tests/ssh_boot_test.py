"""Exercise both generated SSH startup scripts without launching SSH or touching system files."""
import pathlib,subprocess,tempfile,os
root=pathlib.Path(__file__).resolve().parents[1]
for station1 in [False,True]:
 source=(root/('src/main/assets/scripts/install-ssh-station1.sh' if station1 else 'src/main/assets/scripts/install-ssh.sh')).read_text()
 script=source.split("<<'INIT'\n",1)[1].split('\nINIT',1)[0]
 with tempfile.TemporaryDirectory() as tmp:
  b=pathlib.Path(tmp);base=b/'ssh';base.mkdir();proc=b/'proc';proc.mkdir();calls=b/'calls';bb=b/'busybox'
  bb.write_text('#!/bin/sh\nc=$1;shift; "$c" "$@"\n');bb.chmod(0o755)
  loader=base/'ld-musl-aarch64.so.1';loader.write_text('#!/bin/sh\necho started >> "$CALLS"\necho 33333 > "'+str(base/'dropbear.pid')+'"\n');loader.chmod(0o755)
  script=script.replace('/system/bin/busybox-smp',str(bb)).replace('/data/dropbear' if station1 else '/data/quasar/alicebridge-ssh',str(base)).replace('/proc/',str(proc)+'/')
  # Only test PID decisions: emulate liveness; never send signals to host processes.
  script=script.replace('if $BB kill -0 "$pid"','if alive "$pid"').replace('if kill -0 "$pid"','if alive "$pid"')
  prefix='alive(){ [ "$1" = 22222 ]; }; export CALLS;\n'
  for case in ['absent','dead','reused','invalid','already-running']:
   calls.unlink(missing_ok=True);(base/'dropbear.pid').unlink(missing_ok=True)
   if case!='absent':(base/'dropbear.pid').write_text('bad' if case=='invalid' else '11111' if case=='dead' else '22222')
   (proc/'22222').mkdir(exist_ok=True);(proc/'22222'/'cmdline').write_bytes((str(base/'dropbear')+'\0-E\0').encode() if case=='already-running' else b'unrelated\0')
   r=subprocess.run(['sh','-s','start'],input=prefix+script,text=True,capture_output=True,env=os.environ|{'CALLS':str(calls)})
   assert r.returncode==0,(station1,case,r.stderr)
   assert calls.exists()==(case!='already-running'),(station1,case)
   print('PASS: SSH boot', 'Station1' if station1 else 'Mini/Lite/Midi',case)
