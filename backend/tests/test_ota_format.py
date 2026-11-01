import tempfile,unittest,sys,tarfile,io,lzma
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent.parent/'scripts'))
from ota_format import validate_ota

def entry(name,data=b'',checksum=None):
 name=name.encode()+b'\0'; fields=[1,0o100644,0,0,1,0,len(data),0,0,0,0,len(name),sum(data) if checksum is None else checksum]
 header=b'070702'+b''.join(f'{v:08X}'.encode() for v in fields)
 return header+name+b'\0'*(-(110+len(name))%4)+data+b'\0'*(-len(data)%4)
class FormatTest(unittest.TestCase):
 def test_mini_xz_tar_checks_inner_cpio_and_xz_footer(self):
  with tempfile.TemporaryDirectory() as root:
   file=Path(root)/'update.zip'
   def packed(payload):
    with tarfile.open(file,'w:xz') as archive:
     member=tarfile.TarInfo('./yandex_io_ota_enc.swu');member.size=len(payload)
     archive.addfile(member,io.BytesIO(payload))
   packed(entry('sw-description',b'firmware')+entry('rootfs',b'payload')+entry('TRAILER!!!'))
   self.assertEqual(validate_ota(file),'xz-tar-swupdate-cpio')
   valid=file.read_bytes();file.write_bytes(valid[:-8])
   with self.assertRaises(ValueError):validate_ota(file)
   packed(entry('sw-description',b'firmware',0)+entry('TRAILER!!!'))
   with self.assertRaises(ValueError):validate_ota(file)
   file.write_bytes(lzma.compress(b'not an OTA archive'))
   with self.assertRaises(ValueError):validate_ota(file)
 def test_cpio_crc_and_truncation(self):
  with tempfile.TemporaryDirectory() as root:
   file=Path(root)/'update.zip';valid=entry('sw-description',b'firmware')+entry('rootfs',b'payload')+entry('TRAILER!!!')
   file.write_bytes(valid);self.assertEqual(validate_ota(file),'swupdate-cpio')
   file.write_bytes(valid[:-20])
   with self.assertRaises(ValueError):validate_ota(file)
   file.write_bytes(entry('sw-description',b'firmware',0)+entry('TRAILER!!!'))
   with self.assertRaises(ValueError):validate_ota(file)
   file.write_bytes(entry('other',b'payload')+entry('TRAILER!!!'))
   with self.assertRaises(ValueError):validate_ota(file)
