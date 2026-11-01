import io,json,sys,tempfile,unittest,zipfile,zlib
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parent.parent/'scripts'))
import firmware_fetch as fw
class Response(io.BytesIO):
    def __init__(self,data):super().__init__(data);self.headers={'Content-Length':str(len(data))}
class FetchTest(unittest.TestCase):
    def setUp(self):
        # Unit fixtures use tiny files; available host disk is not their subject.
        disk=patch.object(fw.shutil,'disk_usage',return_value=SimpleNamespace(free=4*1024**3))
        disk.start();self.addCleanup(disk.stop)
    def test_low_disk_space_is_refused_before_publication(self):
        with tempfile.TemporaryDirectory() as base,patch.object(fw,'request',side_effect=lambda url:Response(b'{"version":"1.20250721"}' if url.endswith('metadata.json') else b'payload')),patch.object(fw.shutil,'disk_usage',return_value=SimpleNamespace(free=0)):
            with self.assertRaisesRegex(ValueError,'Less than 2 GiB'):
                fw.fetch(base,'yandexmini','1.20250721')
            self.assertFalse((Path(base)/'firmware/yandexmini/1.20250721/update.zip').exists())

    def zip_bytes(self):
        out=io.BytesIO()
        with zipfile.ZipFile(out,'w',compression=zipfile.ZIP_STORED) as z:z.writestr('payload',b'x'*(1024*1024+64))
        return out.getvalue()
    def test_cdn_fallback_and_valid_archive(self):
        data=self.zip_bytes();meta={'version':'1.20250721','crc32':zlib.crc32(data)&0xffffffff,'downloadUrl':'https://quasar.s3.yandex.net/test/update.zip'};calls=[]
        def request(url):
            calls.append(url)
            return Response(json.dumps(meta).encode() if url.endswith('metadata.json') else b'broken' if url.startswith('https:') else data)
        with tempfile.TemporaryDirectory() as base,patch.object(fw,'request',side_effect=request):result=fw.fetch(base,'yandexmini','1.20250721')
        self.assertEqual(result['format'],'zip');self.assertEqual(result['source'],fw.MIRROR+'/yandexmini/1.20250721/update.zip');self.assertEqual(len(calls),4)
    def test_encrypted_ota_requires_reference_crc(self):
        data=b'EncryptedOTA'+b'\x01'*(1024*1024);meta={'version':'1.20250721','crc32':zlib.crc32(data)&0xffffffff}
        def request(url):return Response(json.dumps(meta).encode() if url.endswith('metadata.json') else data)
        with tempfile.TemporaryDirectory() as base,patch.object(fw,'request',side_effect=request):
            result=fw.fetch(base,'plum','1.20250721');self.assertEqual(result['format'],'opaque-ota-crc32-verified');self.assertTrue(result['metadata_crc32_verified'])
        meta['crc32']=-1
        with tempfile.TemporaryDirectory() as base,patch.object(fw,'request',side_effect=request):
            with self.assertRaises(ValueError):fw.fetch(base,'plum','1.20250721')
            self.assertFalse((Path(base)/'firmware/plum/1.20250721/verified.json').exists())
    def test_crc_mismatch_never_publishes_image(self):
        data=self.zip_bytes();meta={'version':'1.20250721','crc32':123}
        def request(url):return Response(json.dumps(meta).encode() if url.endswith('metadata.json') else data)
        with tempfile.TemporaryDirectory() as base,patch.object(fw,'request',side_effect=request):
            with self.assertRaises(ValueError):fw.fetch(base,'yandexmini','1.20250721')
            self.assertFalse((Path(base)/'firmware/yandexmini/1.20250721/update.zip').exists())
    def test_source_allowlist(self):
        for url in ['https://quasar.s3.yandex.net/a.zip','https://cdn.svc.cdn.yandex.net/a.zip',fw.MIRROR+'/a/update.zip']:self.assertTrue(fw.safe_source(url))
        for url in ['http://127.0.0.1/a.zip','https://quasar.s3.yandex.net.evil/a.zip','https://user@quasar.s3.yandex.net/a.zip','file:///etc/passwd']:self.assertFalse(fw.safe_source(url))
if __name__=='__main__':unittest.main()
