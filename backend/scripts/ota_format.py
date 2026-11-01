"""Read-only integrity checks for ZIP and SWUpdate CPIO payloads; never extracts code."""
import zipfile
import lzma
import tarfile

def validate_ota(path):
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            broken = archive.testzip()
            if broken: raise ValueError('Повреждён ZIP: ' + broken)
        return 'zip'
    with open(path, 'rb') as f:
        magic = f.read(6)
    if magic == b'\xfd7zXZ\x00':
        try:
            # Consume the entire XZ stream so a damaged footer/CRC cannot be ignored by TAR.
            with lzma.open(path, 'rb') as stream:
                total = 0
                for block in iter(lambda: stream.read(1048576), b''):
                    total += len(block)
                    if total > 2 * 1024**3: raise ValueError('Слишком большой распакованный OTA')
            found = False
            with tarfile.open(path, 'r:xz') as archive:
                for index, member in enumerate(archive):
                    if index > 10000: raise ValueError('Слишком много файлов TAR')
                    if member.isfile() and member.name.removeprefix('./') == 'yandex_io_ota_enc.swu':
                        if found: raise ValueError('Два SWUpdate образа в TAR')
                        with archive.extractfile(member) as payload: _validate_cpio(payload)
                        found = True
            if not found: raise ValueError('Не найден SWUpdate внутри TAR/XZ')
            return 'xz-tar-swupdate-cpio'
        except (lzma.LZMAError, tarfile.TarError, EOFError) as error:
            raise ValueError('Повреждён OTA TAR/XZ: ' + str(error)) from error
    with open(path, 'rb') as f:
        return _validate_cpio(f)

def _validate_cpio(f):
    entries = 0; description = False
    while True:
        header = f.read(110)
        if len(header) != 110 or header[:6] not in (b'070701', b'070702'):
            raise ValueError('Повреждён или неизвестен формат OTA/CPIO')
        try: fields = [int(header[6+i*8:14+i*8], 16) for i in range(13)]
        except ValueError: raise ValueError('Повреждён заголовок CPIO')
        size, namesize, checksum = fields[6], fields[11], fields[12]
        if namesize < 1 or namesize > 65536: raise ValueError('Некорректное имя CPIO')
        name = f.read(namesize)
        if len(name) != namesize or not name.endswith(b'\0'): raise ValueError('Обрезанное имя CPIO')
        f.read(-(110 + namesize) % 4)
        if name == b'TRAILER!!!\0':
            if size or not entries or not description: raise ValueError('Не найден sw-description в SWUpdate OTA')
            if any(f.read()): raise ValueError('Данные после конца CPIO')
            return 'swupdate-cpio'
        description |= name == b'sw-description\0'
        remaining = size; actual = 0
        while remaining:
            block = f.read(min(1048576, remaining))
            if not block: raise ValueError('Обрезан файл внутри CPIO')
            if header[:6] == b'070702': actual = (actual + sum(block)) & 0xffffffff
            remaining -= len(block)
        if header[:6] == b'070702' and actual != checksum: raise ValueError('Контрольная сумма CPIO не совпала')
        f.read(-size % 4); entries += 1
        if entries > 100000: raise ValueError('Слишком много файлов CPIO')
