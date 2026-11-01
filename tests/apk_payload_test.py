"""Check final APK payload names and byte hashes; AAPT transparently expands .gz assets."""
import hashlib,json,pathlib,zipfile
root=pathlib.Path(__file__).resolve().parents[1]
with zipfile.ZipFile(root/'app/build/outputs/apk/debug/app-debug.apk') as apk:
    for name in ['aarch64-k3.10','armv7sf-k3.2','zsh-aarch64-k3.10','zsh-armv7sf-k3.2']:
        manifest=json.loads(apk.read('assets/entware/'+name+'.json'))
        data=apk.read('assets/entware/'+name+'.bin')
        assert data[:2]==b'\x1f\x8b' and hashlib.sha256(data).hexdigest()==manifest['archiveSha256'],name
    assert 'assets/scripts/install-zsh-offline.sh' in apk.namelist()
print('PASS: final APK contains all 4 compressed offline bundles with original byte hashes')
