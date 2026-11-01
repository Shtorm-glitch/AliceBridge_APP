#!/usr/bin/env python3
"""Build static ARM agents and an offline-signed manifest. Private key never leaves this host."""
import argparse,base64,hashlib,json,os,pathlib,re,subprocess,tempfile
p=argparse.ArgumentParser();p.add_argument('--version',required=True);p.add_argument('--build',type=int,required=True);p.add_argument('--key',required=True);args=p.parse_args()
if not re.fullmatch(r'\d+\.\d+\.\d+',args.version) or args.build<1:raise SystemExit('Invalid version/build')
root=pathlib.Path(__file__).resolve().parent;out=root/'dist';folder=out/args.version;folder.mkdir(parents=True,exist_ok=True)
key=pathlib.Path(args.key).resolve();artifacts=[]
for arch in ['arm64','arm']:
 env=os.environ.copy();env.update(GOOS='linux',GOARCH=arch,CGO_ENABLED='0');env['GOARM']='7'
 target=folder/('agent-'+arch)
 subprocess.run(['go','build','-trimpath','-ldflags',f'-s -w -X main.version={args.version} -X main.buildNumber={args.build}','-o',str(target),'./cmd/alicebridge-agent'],cwd=root,env=env,check=True)
 artifacts.append(dict(arch=arch,file=args.version+'/'+target.name,sha256=hashlib.sha256(target.read_bytes()).hexdigest(),size=target.stat().st_size))
payload=dict(schema=1,protocol=1,version=args.version,build=args.build,min_app_code=1000013,min_kernel='3.2',profiles=['linux','station1','android-runtime'],artifacts=artifacts,changes=['Fix installation on firmware with symlinked /data or /data/quasar. Managed agent directory and file symlinks remain refused; directory ownership and private permissions are verified.'])
raw=json.dumps(payload,ensure_ascii=False,separators=(',',':')).encode()
with tempfile.TemporaryDirectory() as tmp:
 source=pathlib.Path(tmp)/'payload';signature=pathlib.Path(tmp)/'signature';source.write_bytes(raw)
 subprocess.run(['openssl','dgst','-sha256','-sign',str(key),'-out',str(signature),str(source)],check=True)
 envelope=dict(payload=base64.b64encode(raw).decode(),signature=base64.b64encode(signature.read_bytes()).decode())
 (out/'manifest.json').write_text(json.dumps(envelope,indent=2)+'\n')
(out/'release-metadata.json').write_text(json.dumps(payload,indent=2)+'\n')
print(json.dumps(payload,indent=2))
