import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from unittest.mock import patch
from http.server import ThreadingHTTPServer
from server import Agent, Jobs, handler, validate, focus_regions


class ServerTest(unittest.TestCase):
    def test_focus_regions_require_image_and_valid_normalized_boxes(self):
        good = {'label': '오류 창', 'box': [0.1, 0.2, 0.7, 0.8]}
        bad = [{'label': 'x', 'box': [-1, 0, 1, 1]},
               {'label': 'x', 'box': [0.8, 0, 0.2, 1]},
               {'label': 'x', 'box': [0, float('nan'), 1, 1]}]
        self.assertEqual([good], focus_regions({'focus_regions': [good] + bad}, True))
        self.assertEqual([], focus_regions({'focus_regions': [good]}, False))

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.agent = Agent(self.tmp.name, 'https://model.invalid', 'secret', 'vision', writes=True)

    def tearDown(self):
        self.tmp.cleanup()

    def test_workspace_boundaries(self):
        self.agent.tool('create_file', {'filename': 'fix.txt', 'content': 'advice'})
        self.assertEqual('advice', self.agent.tool('read_file', {'filename': 'fix.txt'}))
        for name in ['../secret', '/etc/passwd', '.env']:
            with self.assertRaises(ValueError):
                self.agent.tool('read_file', {'filename': name})
        with self.assertRaises(FileExistsError):
            self.agent.tool('create_file', {'filename': 'fix.txt', 'content': 'overwrite'})
        Path(self.tmp.name, 'link').symlink_to('/etc/passwd')
        with self.assertRaises(ValueError):
            self.agent.tool('read_file', {'filename': 'link'})
        self.agent.writes = False
        with self.assertRaises(ValueError):
            self.agent.tool('create_file', {'filename': 'new.txt', 'content': 'x'})

    def test_validation(self):
        for data in [{'request_id': '../x', 'schema_version': 1},
                     {'request_id': 'x', 'schema_version': 1, 'allow_pc': 'true'},
                     {'request_id': 'x', 'schema_version': 1, 'image_base64': 'invalid!'}]:
            with self.assertRaises(ValueError):
                validate(data)

    def test_model_tool_loop_and_authorization(self):
        class Response:
            def __init__(self, message): self.message = message
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def read(self, limit): return json.dumps({'choices': [{'message': self.message}]}).encode()
        call = {'role': 'assistant', 'content': None, 'tool_calls': [{'id': 'call-1', 'type': 'function',
                'function': {'name': 'create_file', 'arguments': '{"filename":"answer.txt","content":"fixed"}'}}]}
        final = {'role': 'assistant', 'content': '{"advice":"완료","steps":["확인하세요"]}'}
        with patch('server.urllib.request.build_opener') as opener:
            opener.return_value.open.side_effect = [Response(call), Response(final)]
            result = self.agent.run({'goal': 'write report', 'allow_pc': False})
            self.assertFalse(Path(self.tmp.name, 'answer.txt').exists())
            self.assertEqual('failed', result['actions'][0]['status'])
            opener.return_value.open.side_effect = [Response(call), Response(final)]
            result = self.agent.run({'goal': 'write report', 'allow_pc': True})
            self.assertEqual('fixed', Path(self.tmp.name, 'answer.txt').read_text())
            self.assertEqual('completed', result['actions'][0]['status'])

    def test_free_speech_without_camera_reaches_model(self):
        spoken = '인터넷이 자꾸 끊기는데 어디부터 살펴보면 좋을까?'
        data = validate({'schema_version': 1, 'request_id': 'voice-one',
                         'goal': spoken, 'text': '', 'width': 0, 'height': 0})
        with patch('server.urllib.request.build_opener') as opener:
            opener.return_value.open.return_value.__enter__.return_value.read.return_value = json.dumps({
                'choices': [{'message': {'content': json.dumps({
                    'advice': '연결 상태를 확인하세요', 'steps': ['Wi-Fi 연결을 확인하세요']})}}]
            }).encode()
            self.agent.run(data)
            request = opener.return_value.open.call_args.args[0]
            payload = json.loads(request.data)
            content = payload['messages'][1]['content']
            self.assertEqual(1, len(content))
            self.assertEqual(spoken, json.loads(content[0]['text'])['goal'])
            self.assertNotIn('tools', payload)

    def test_http_job_and_duplicate(self):
        count = []
        self.agent.run = lambda data: (count.append(data) or {'advice': '확인', 'steps': ['다음']})
        jobs = Jobs(str(Path(self.tmp.name, 'jobs.db')), self.agent)
        server = ThreadingHTTPServer(('127.0.0.1', 0), handler(jobs, 'token'))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        url = 'http://127.0.0.1:%d/vision' % server.server_port
        def send(data, token='token'):
            return urllib.request.urlopen(urllib.request.Request(url, json.dumps(data).encode(),
                   {'Authorization': 'Bearer ' + token, 'Idempotency-Key': 'one'}))
        data = {'schema_version': 1, 'request_id': 'one', 'text': 'error'}
        try:
            with self.assertRaises(urllib.error.HTTPError) as error:
                send(data, 'wrong')
            self.assertEqual(401, error.exception.code)
            error.exception.close()
            with send(data) as response:
                self.assertEqual(202, response.status)
            for _ in range(100):
                if jobs.get('one')['status'] == 'completed': break
                time.sleep(.01)
            with send(data): pass
            self.assertEqual(1, len(count))
            with urllib.request.urlopen(urllib.request.Request(url + '/one', headers={'Authorization': 'Bearer token'})) as response:
                self.assertEqual('확인', json.load(response)['advice'])
            with self.assertRaises(urllib.error.HTTPError) as conflict:
                send({**data, 'text': 'different'})
            conflict.exception.close()
            jobs.pool.shutdown()
            jobs.db.close()
            restarted = Jobs(str(Path(self.tmp.name, 'jobs.db')), self.agent)
            self.assertEqual('completed', restarted.submit(data)['status'])
            restarted.pool.shutdown()
            restarted.db.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == '__main__':
    unittest.main()
