import json
import unittest
from unittest.mock import patch

from make_setup_qr import payload, create_document


class SetupQrTest(unittest.TestCase):
    def test_openrouter_free_models_only(self):
        key = 'dummy-test-key-not-a-real-secret'
        for model in ('google/gemma-4-26b-a4b-it:free', 'openrouter/free'):
            self.assertEqual(json.loads(payload('openrouter', model, key))['model'], model)
        for model in ('google/paid-model', '../model:free', 'google/model:free?x=1'):
            with self.assertRaises(ValueError):
                payload('openrouter', model, key)

    def test_payload_matches_app_contract(self):
        key = 'dummy-test-key-not-a-real-secret'
        for provider in ('openai', 'gemini'):
            self.assertEqual(json.loads(payload(provider, 'test-model', key)), {
                'type': 'computer-noob-helper.config', 'version': 1,
                'provider': provider, 'model': 'test-model', 'api_key': key})

    def test_invalid_configuration_rejected(self):
        for args in [('chatgpt', 'model', 'x' * 24),
                     ('gemini', '../model', 'x' * 24),
                     ('openai', 'model', 'x' * 24 + '\n'),
                     ('gemini', 'model', 'short')]:
            with self.assertRaises(ValueError):
                payload(*args)

    def test_offline_document_contains_svg_without_plaintext_key(self):
        key = 'dummy-test-key-not-a-real-secret'
        with patch('socket.socket', side_effect=AssertionError('network prohibited')):
            document = create_document('gemini', 'test-model', key)
        self.assertIn('<svg', document)
        self.assertIn('<path', document)
        self.assertIn("default-src 'none'", document)
        self.assertNotIn('<script', document)
        self.assertNotIn(key, document)
        self.assertNotIn('<?xml', document)


if __name__ == '__main__':
    unittest.main()
