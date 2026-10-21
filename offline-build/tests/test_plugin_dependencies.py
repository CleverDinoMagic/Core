from pathlib import Path
import tempfile
import unittest
import zipfile

from tools.validate_plugin import validate_public_plugin

class PublicPluginTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.jar = Path(self.temp.name) / 'plugin.jar'

    def write_jar(self, descriptor, class_bytes=b'public class content'):
        with zipfile.ZipFile(self.jar, 'w') as archive:
            archive.writestr('plugin.yml', descriptor)
            archive.writestr('example/Plugin.class', class_bytes)

    def test_public_dependencies_allowed(self):
        self.write_jar('name: Show\ndepend: [Core, ProtocolLib]\ndescription: Audio disabled\n')
        validate_public_plugin(self.jar)

    def test_private_dependency_rejected_in_yaml_forms(self):
        for value in ('depend: [Audio, Core]', 'depend:\n- Cosmetics\n- Core', 'softdepend: Audio'):
            with self.subTest(value=value):
                self.write_jar('name: Show\n' + value + '\nmain: example.Plugin\n')
                with self.assertRaisesRegex(ValueError, 'private plugin dependency'):
                    validate_public_plugin(self.jar)

    def test_private_class_references_rejected_without_descriptor_dependency(self):
        for value in (b'network/palace/audio/Audio', b'network/palace/cosmetics/Cosmetics'):
            with self.subTest(value=value):
                self.write_jar('name: Show\ndepend: [Core]\n', b'constant pool ' + value)
                with self.assertRaisesRegex(ValueError, 'private API reference'):
                    validate_public_plugin(self.jar)
