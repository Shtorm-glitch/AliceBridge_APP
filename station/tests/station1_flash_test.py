"""Run the exact app-generated flash script against temporary regular files, never block devices."""
import hashlib,os,pathlib,shutil,subprocess,tempfile
root=pathlib.Path(__file__).resolve().parents[1]
stock=root.parents[1]/'private/station1-root/boot.img';patched=root.parents[1]/'private/station1-root/boot-root.img'
assert hashlib.sha256(stock.read_bytes()).hexdigest()=='e1dbdbb6bcd5e3589fc4c0ca6220856c6d242c40376d406d47720e2fbd8ad8dd'
assert hashlib.sha256(patched.read_bytes()).hexdigest()=='5bb6c43f37ae658112120604dc84eeb358c742587a427e85c77c7d9d19559478'
script=(root/'build/station1-tests/flash.sh').read_text()
with tempfile.TemporaryDirectory() as tmp:
    base=pathlib.Path(tmp);partition=base/'partition';image=base/'image';sectors=base/'sectors';busybox=base/'bb';getprop=base/'getprop'
    busybox.write_text('''#!/bin/sh
command=$1;shift
case "$command" in
 sha256sum) exec shasum -a 256 "$@";;
 dd)
    dd "$@" || exit $?
    if [ "${CORRUPT_WRITE:-0}" = 1 ];then
        for arg in "$@";do case "$arg" in of=*) printf X | dd "$arg" bs=1 count=1 conv=notrunc 2>/dev/null;;esac;done
    fi;;
 *) exec "$command" "$@";;
esac
''');busybox.chmod(0o755)
    getprop.write_text('#!/bin/sh\nprintf "%s\\n" "${BOARD:-tulip}"\n');getprop.chmod(0o755)
    adapted=script.replace('/dev/block/mmcblk0p6',str(partition)).replace('/data/local/tmp/boot-root.img',str(image)).replace('/sys/class/block/mmcblk0p6/size',str(sectors)).replace('/system/bin/busybox-smp',str(busybox)).replace('\nsync\n','\ntrue\n')
    assert '/dev/block/' not in adapted and '/sys/class/block/' not in adapted
    for case in ('valid','wrong-board','wrong-size','unknown-stock','corrupt-download','failed-readback'):
        shutil.copyfile(stock,partition);shutil.copyfile(patched,image);sectors.write_text('32768\n')
        env=os.environ|{'PATH':str(base)+':'+os.environ['PATH'],'BOARD':'tulip','CORRUPT_WRITE':'0'}
        if case=='wrong-board':env['BOARD']='other'
        if case=='wrong-size':sectors.write_text('65536\n')
        if case=='unknown-stock':
            with partition.open('r+b') as f:f.write(b'X')
        if case=='corrupt-download':
            with image.open('r+b') as f:f.write(b'X')
        if case=='failed-readback':env['CORRUPT_WRITE']='1'
        before=partition.read_bytes();result=subprocess.run(['sh'],input=adapted,text=True,env=env,capture_output=True)
        if case in ('valid','unknown-stock'):assert result.returncode==0 and partition.read_bytes()==patched.read_bytes(),result.stderr
        elif case=='failed-readback':assert result.returncode!=0
        else:assert result.returncode!=0 and partition.read_bytes()==before,case
        print('PASS: Station 1 flash',case)
