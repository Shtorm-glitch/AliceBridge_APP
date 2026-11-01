"""Exercise the shipped installer's free-space parser with real BusyBox layouts."""
import pathlib,re,subprocess
source=(pathlib.Path(__file__).resolve().parents[1]/'app/src/main/assets/yse/yse').read_text()
parser=re.search(r"free=\$\(df -Pk.*?\| awk '([^']+)'\)",source).group(1)
for value,expected in [
 ('Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/block/by-name/UDISK\n 1301152 208232 1008776 17% /data\n','1008776'),
 ('Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/block/by-name/UDISK 1301152 208232 1008776 17% /data\n','1008776'),
 ('Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/x 100 100 0 100% /data\n','0'),
 ('df: /data: error\n','')]:
 actual=subprocess.check_output(['awk',parser],input=value,text=True).strip()
 assert actual==expected,(actual,expected)
print('PASS: YAP free-space parser: wrapped, POSIX, full, unavailable')
