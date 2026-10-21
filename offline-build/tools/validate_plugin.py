"""Reject packaged dependencies on the unavailable private Palace plugins."""
import re
import zipfile

PRIVATE_NAMES = {'Audio', 'Cosmetics'}
PRIVATE_PACKAGES = (b'network/palace/audio/', b'network/palace/cosmetics/',
                    b'network.palace.audio.', b'network.palace.cosmetics.')

def validate_public_plugin(path):
    with zipfile.ZipFile(path) as archive:
        descriptor = archive.read('plugin.yml').decode('utf-8')
        for block in re.findall(r'(?m)^(?:depend|softdepend):[^\n]*(?:\n[ \t]+[^\n]*|\n-[^\n]*)*', descriptor):
            unavailable = PRIVATE_NAMES.intersection(re.findall(r'[\w-]+', block))
            if unavailable:
                raise ValueError(str(path) + ': private plugin dependency: ' + ', '.join(sorted(unavailable)))
        for name in archive.namelist():
            if name.endswith('.class'):
                data = archive.read(name)
                if any(package in data for package in PRIVATE_PACKAGES):
                    raise ValueError(str(path) + ': private API reference in ' + name)
