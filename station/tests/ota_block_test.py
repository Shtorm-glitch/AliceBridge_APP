"""Exercise the app's pinned noupdate adaptation on regular sandbox files only."""
import pathlib
import subprocess
import tempfile

root = pathlib.Path(__file__).resolve().parents[1]
scripts = root / 'build/station-scripts'
with tempfile.TemporaryDirectory(prefix='alicebridge-noupdate-') as tmp:
    base = pathlib.Path(tmp)
    q = base / 'quasar'
    q.mkdir()
    backup = base / 'backup'
    mounts = base / 'mounts'
    mounts.write_text('rootfs / rootfs ro,relatime 0 0\n')
    log = base / 'mount-log'
    original_json = '{"tasks":[{"cmd":"maind --mediad"},{"cmd":"maind --platformd"},{"cmd":"maind --updatesd"}]}\n'
    (q / 'launch.json').write_text(original_json)
    applier = q / 'device_ota_update.sh'
    key = q / 'ota_script.pub'
    applier.write_text('#!/bin/sh\necho original\n')
    key.write_text('original-key\n')

    def adapt(script):
        return (script.replace('/system/vendor/quasar', str(q))
                .replace('/data/opt/backup', str(backup))
                .replace('/proc/mounts', str(mounts)))

    def run(script):
        prefix = ('id(){ echo 0; }; getprop(){ :; }; sync(){ :; }; jq(){ return 1; }; '
                  f'mount(){{ printf "%s\\n" "$*" >> "{log}"; }};\n')
        return subprocess.run(['sh'], input=prefix + adapt(script), text=True,
                              capture_output=True, timeout=20)

    primary = (scripts / 'ota.sh').read_text()
    inspect = (scripts / 'inspect.sh').read_text().split('ab_analytics=1')[0]
    result = run(primary)
    assert result.returncode == 0, (result.stdout, result.stderr)
    assert 'AB_NOUPDATE_VERIFIED=1' in result.stdout
    assert (backup / 'ota_script.pub.orig').read_text() == 'original-key\n'
    assert (backup / 'device_ota_update.sh.orig').read_text() == '#!/bin/sh\necho original\n'
    assert (q / 'launch.json').read_text() == original_json
    assert 'AB_OTA=1' in run(inspect).stdout
    assert 'remount,ro /' in log.read_text()
    result = run(primary)
    assert result.returncode == 0, (result.stdout, result.stderr)
    assert (backup / 'ota_script.pub.orig').read_text() == 'original-key\n'
    print('PASS: noupdate key/stub readback, retained original backups, JSON left intact without parser, mount restored')

    applier.write_text('# altered outside AliceBridge\n')
    assert 'AB_OTA=0' in run(inspect).stdout
    key.unlink()
    result = run(primary)
    assert result.returncode != 0
    assert applier.read_text() == '# altered outside AliceBridge\n'
    print('PASS: drift detected; missing key fails before modifying installer')

    unrelated = base / 'unrelated'
    unrelated.write_text('do not change\n')
    key.symlink_to(unrelated)
    result = run(primary)
    assert result.returncode != 0
    assert unrelated.read_text() == 'do not change\n'
    print('PASS: symlink target rejected without touching unrelated files')
