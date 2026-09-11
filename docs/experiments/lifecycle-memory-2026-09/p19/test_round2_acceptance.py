import json
import pathlib
import tempfile
import unittest
import dossier


class Round2AcceptanceTest(unittest.TestCase):
    def test_missing_profiles_cannot_accept_control(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaisesRegex(ValueError,'missing profile'):
                dossier.validate_round2(pathlib.Path(root))

    def test_failed_profile_cannot_accept_control(self):
        with tempfile.TemporaryDirectory() as root:
            folder=pathlib.Path(root)/'profiles/none';folder.mkdir(parents=True)
            (folder/'result.json').write_text(json.dumps({'valid':False}))
            with self.assertRaisesRegex(ValueError,'invalid profile'):
                dossier.validate_round2(pathlib.Path(root))
