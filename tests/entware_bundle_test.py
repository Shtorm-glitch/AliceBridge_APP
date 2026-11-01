"""Check packaged offline payloads without executing any firmware or install script."""
import hashlib, io, json, pathlib, tarfile
root=pathlib.Path(__file__).resolve().parents[1]
for arch in ('aarch64-k3.10','armv7sf-k3.2','zsh-aarch64-k3.10','zsh-armv7sf-k3.2'):
    folder=root/'app/src/main/assets/entware'
    manifest=json.loads((folder/(arch+'.json')).read_text())
    data=(folder/(arch+'.bin')).read_bytes()
    assert hashlib.sha256(data).hexdigest()==manifest['archiveSha256']
    with tarfile.open(fileobj=io.BytesIO(data)) as tar:
        assert all(not n.startswith('/') and '..' not in n.split('/') for n in tar.getnames())
        assert hashlib.sha256(tar.extractfile('opkg').read()).hexdigest()==manifest['opkgSha256']
        assert hashlib.sha256(tar.extractfile('locale-archive').read()).hexdigest()==manifest['localeSha256']
        names={p['Package'] for p in manifest['packages']}
        expected={'zsh','libc','libncursesw'} if arch.startswith('zsh-') else {'opkg','entware-opt','curl','ca-bundle','libpthread','libc'}
        assert expected<=names
        for p in manifest['packages']:
            assert hashlib.sha256(tar.extractfile('packages/'+p['Filename']).read()).hexdigest()==p['SHA256sum']
    print('PASS:',arch,len(names),'verified offline packages')
# Installation entry points must remain inside explicit service actions.
source=(root/'app/src/main/java/dev/alice/bridge/BridgeService.java').read_text()
monitor=source.split('private void checkStation()',1)[1].split('private void findHomeStation(',1)[0]
assert 'YseClient.ensure' not in monitor and 'SshClient.ensure' not in monitor and 'YseClient.installZsh' not in monitor
assert source.count('YseClient.ensure')==1 and source.count('SshClient.ensure')==1
print('PASS: monitoring only inspects YSE/SSH, no automatic installer calls')
