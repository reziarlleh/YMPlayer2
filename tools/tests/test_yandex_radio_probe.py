import importlib.util
import json
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from io import BytesIO
from pathlib import Path

spec = importlib.util.spec_from_file_location('radio', Path(__file__).resolve().parents[1] / 'Probe-YandexRadio.py')
radio = importlib.util.module_from_spec(spec)
spec.loader.exec_module(radio)

class ProbeChecks(unittest.TestCase):
    def run_fixture(self, uid='123', fm_uid='123', open_collection=False):
        def fetch(origin, path, token=None):
            if origin == radio.MUSIC:
                return {'status': 200, 'shape': ['result']}, {'result': {'account': {'uid': uid}}}
            authenticated = token == 'private-token'
            if '/collection/' in path:
                if authenticated or open_collection:
                    return {'status': 200, 'shape': ['slugs']}, {'slugs': ['private-station-name']}
                return {'status': 401}, {}
            return {'status': 200}, {'uid': fm_uid} if authenticated and fm_uid else {}
        with patch.object(radio, 'fetch', side_effect=fetch):
            return radio.probe('private-token')

    def test_authenticated_collection_and_matching_uid(self):
        report = self.run_fixture()
        self.assertTrue(report['personalAccountIdentityVerified'])
        saved = json.dumps(report)
        for private in ('private-token', 'private-station-name', '123'):
            self.assertNotIn(private, saved)

    def test_wrong_account_never_full_success(self):
        self.assertFalse(self.run_fixture(fm_uid='456')['personalAccountIdentityVerified'])

    def test_no_identity_is_separate_from_readable_collection(self):
        report = self.run_fixture(fm_uid=None)
        self.assertTrue(report['existingTokenVerifiedForFmCollection'])
        self.assertFalse(report['personalAccountIdentityVerified'])

    def test_public_collection_does_not_prove_auth(self):
        self.assertFalse(self.run_fixture(open_collection=True)['existingTokenVerifiedForFmCollection'])

    def test_redirect_is_denied(self):
        self.assertIsNone(radio.NoRedirect().redirect_request(None,None,302,None,{},'https://other.example'))

    def test_http_error_body_values_not_in_report(self):
        error = HTTPError(radio.FM+'/account/about',401,'Unauthorized',{},BytesIO(b'{"message":"secret-test-value"}'))
        with patch('urllib.request.OpenerDirector.open', side_effect=error):
            info, _ = radio.fetch(radio.FM, '/account/about', 'private-token')
        self.assertEqual(info['status'],401)
        self.assertNotIn('secret-test-value', json.dumps(info))

if __name__ == '__main__':
    unittest.main(verbosity=2)
