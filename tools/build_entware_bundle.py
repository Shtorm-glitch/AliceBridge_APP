#!/usr/bin/env python3
"""Build offline Entware bootstrap assets from official HTTPS package repositories."""
import concurrent.futures, gzip, hashlib, io, json, pathlib, re, tarfile, time, urllib.request
ROOT=pathlib.Path(__file__).resolve().parents[1]
CACHE=ROOT/'private/entware-build'
OUT=ROOT/'app/src/main/assets/entware'
def get(url,path):
    if path.exists(): return path.read_bytes()
    for attempt in range(4):
        try:
            with urllib.request.urlopen(url,timeout=90) as response: data=response.read()
            path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data);return data
        except Exception:
            if attempt==3:raise
            time.sleep(2)
def build(arch,kind="entware"):
    prefix="" if kind=="entware" else kind+"-"
    base='https://bin.entware.net/'+arch+'/'
    cache=CACHE/arch;cache.mkdir(parents=True,exist_ok=True)
    metadata=gzip.decompress(get(base+'Packages.gz',cache/'Packages.gz')).decode()
    packages={}
    for block in metadata.split('\n\n'):
        fields={line.split(': ',1)[0]:line.split(': ',1)[1] for line in block.splitlines() if ': ' in line and not line.startswith(' ')}
        if 'Package' in fields:packages[fields['Package']]=fields
    providers={alias.strip():name for name,p in packages.items() for alias in p.get('Provides','').split(',') if alias.strip()}
    pending=['entware-opt','curl','ca-bundle','libpthread'] if kind=='entware' else ['zsh'];selected={}
    while pending:
        name=pending.pop()
        if name in selected:continue
        p=packages[name];selected[name]=p
        for dep in p.get('Depends','').split(','):
            if not dep.strip():continue
            choices=[re.split(r'\s|\(',a.strip())[0] for a in dep.split('|')]
            resolved=next((a if a in packages else providers[a] for a in choices if a in packages or a in providers),None)
            if not resolved:raise ValueError((name,dep))
            pending.append(resolved)
    def download(p):
        data=get(base+p['Filename'],cache/p['Filename'])
        if hashlib.sha256(data).hexdigest()!=p['SHA256sum']:raise ValueError('SHA256 '+p['Filename'])
        return ('packages/'+p['Filename'],data,0o644)
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:files=list(pool.map(download,selected.values()))
    opkg=get(base+'installer/opkg',cache/'opkg')
    files.append(('opkg',opkg,0o755))
    locale=get('https://bin.entware.net/other/locale-archive.2.27',CACHE/'locale-archive.2.27')
    files.append(('locale-archive',locale,0o644))
    manifest={'repository':base,'architecture':arch,'opkgSha256':hashlib.sha256(opkg).hexdigest(),'packages':[{k:p[k] for k in ('Package','Version','Filename','SHA256sum') }|{'License':p.get('License','See package copyright')} for p in sorted(selected.values(),key=lambda p:p['Package'])]}
    manifest['localeSha256']=hashlib.sha256(locale).hexdigest()
    files.append(('manifest.json',json.dumps(manifest,indent=2).encode(),0o644))
    archive=io.BytesIO()
    with tarfile.open(fileobj=archive,mode='w') as tar:
        for name,data,mode in sorted(files):
            info=tarfile.TarInfo(name);info.size=len(data);info.mode=mode;info.mtime=0;tar.addfile(info,io.BytesIO(data))
    compressed=gzip.compress(archive.getvalue(),mtime=0)
    OUT.mkdir(parents=True,exist_ok=True);(OUT/(prefix+arch+'.bin')).write_bytes(compressed)
    manifest['archiveSha256']=hashlib.sha256(compressed).hexdigest()
    (OUT/(prefix+arch+'.json')).write_text(json.dumps(manifest,indent=2)+'\n')
    print(kind,arch,len(selected),'packages',len(compressed),'bytes',manifest['archiveSha256'],flush=True)
for architecture in ['aarch64-k3.10','armv7sf-k3.2']:
    for kind in ['entware','zsh']:build(architecture,kind)
