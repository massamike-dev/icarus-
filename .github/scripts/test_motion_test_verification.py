import importlib.util
from pathlib import Path
import unittest
spec = importlib.util.spec_from_file_location('verification', Path(__file__).with_name('verify-motion-test.py'))
v = importlib.util.module_from_spec(spec); spec.loader.exec_module(v)
class Isolation(unittest.TestCase):
    def setUp(self):
        self.receiver = v.PACKAGE + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
        self.test_receiver = v.PACKAGE + '.test.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
        self.baseline = dict(package=v.PACKAGE, versionCode=46, versionName='1.6.15', debuggable='false', permissions=[self.receiver], signerCertificateSha256=['existing'])
        self.preview = dict(self.baseline, package=v.PACKAGE+'.test', versionCode=52, versionName='1.6.21-test', permissions=[self.test_receiver])
    def test_private_receiver_rename(self):
        v.verify_upgrade(self.baseline, self.preview)
    def test_rejects_other_permissions_and_identity_changes(self):
        for change in [dict(permissions=[self.test_receiver,'android.permission.NEW']),dict(package=v.PACKAGE),dict(signerCertificateSha256=['other']),dict(debuggable='true'),dict(versionCode=51)]:
            with self.subTest(change=change), self.assertRaises(ValueError):
                v.verify_upgrade(self.baseline,dict(self.preview,**change))
    def test_receiver_must_be_signature_protected(self):
        for level in ['normal','dangerous','signature','0x00000002']:
            xml = f'<manifest xmlns:android="http://schemas.android.com/apk/res/android"><permission android:name="{self.test_receiver}" android:protectionLevel="{level}"/><uses-permission android:name="{self.test_receiver}"/></manifest>'
            if level in ['signature','0x00000002']: self.assertEqual(v.permissions(xml),[self.test_receiver])
            else:
                with self.assertRaises(ValueError): v.permissions(xml)
        with self.assertRaises(ValueError):
            v.permissions(f'<manifest xmlns:android="http://schemas.android.com/apk/res/android"><uses-permission android:name="{self.test_receiver}"/></manifest>')
if __name__ == '__main__': unittest.main()
