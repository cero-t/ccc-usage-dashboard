import contextlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import session_collector as sc


class SnapshotTests(unittest.TestCase):
    def test_exact_footer_percentage_and_input_only_context(self):
        data = {'session_id': 'abc-123', 'context_window': {'used_percentage': 8.5, 'context_window_size': 1000000,
                'current_usage': {'input_tokens': 1000, 'cache_creation_input_tokens': 4000,
                                  'cache_read_input_tokens': 80000, 'output_tokens': 9000}},
                'rate_limits': {'five_hour': {'used_percentage': 23, 'resets_at': 2000}},
                'prompt': 'must not persist', 'secret': 'must not persist', 'transcript_path': '/private'}
        proc = {'pid': 12, 'start': '123', 'tty': True}
        snap = sc.footer_snapshot(data, proc, 1000)
        self.assertEqual(snap['contextTokens'], 85000)
        self.assertEqual(snap['contextUsedPercent'], 8.5)
        self.assertEqual(snap['windows'][0]['usedPercent'], 23)
        self.assertNotIn('must not persist', json.dumps(snap))
        self.assertNotIn('transcript_path', snap)

    def test_missing_context_is_not_zero_and_headless_is_excluded(self):
        data = {'session_id': 'abc', 'context_window': {'current_usage': None, 'used_percentage': None}}
        self.assertIsNone(sc.footer_snapshot(data, {'pid': 12, 'start': '1', 'tty': True}, 1)['contextUsedPercent'])
        self.assertIsNone(sc.footer_snapshot(data, {'pid': 12, 'start': '1', 'tty': False}, 1))
        self.assertIsNone(sc.footer_snapshot({'session_id': '../escape'}, {'tty': True}, 1))

    def test_atomic_write_is_private_and_replaces_complete_state(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / 'state.json'
            sc.atomic_json(p, {'one': 1})
            sc.atomic_json(p, {'two': 2})
            self.assertEqual(sc.read_json(p), {'two': 2})
            self.assertEqual(p.stat().st_mode & 0o777, 0o600)
            self.assertEqual(len(list(p.parent.iterdir())), 1)

    def test_footer_failure_to_capture_still_preserves_stdin_and_output(self):
        raw = b'{"session_id":"abc"}'
        proc = {'pid': 12, 'start': '1', 'tty': True}
        stdin = type('Input', (), {'buffer': io.BytesIO(raw)})()
        with patch.object(sc.sys, 'stdin', stdin), patch.object(sc, 'claude_parent', return_value=proc), \
             patch.object(sc, 'atomic_json', side_effect=OSError), patch.object(sc.subprocess, 'run') as run:
            run.return_value.returncode = 7
            self.assertEqual(sc.capture(['/bin/cat']), 7)
            run.assert_called_once_with(['/bin/cat'], input=raw)

    def test_repeated_footer_timer_does_not_refresh_observation(self):
        data = {'session_id': 'abc', 'context_window': {'used_percentage': 2}, 'rate_limits': {'seven_day': {'used_percentage': 9, 'resets_at': 2000}}}
        proc = {'pid': 12, 'start': '1', 'tty': True}
        with tempfile.TemporaryDirectory() as tmp, patch.object(sc, 'ROOT', Path(tmp)), \
             patch.object(sc, 'claude_parent', return_value=proc), patch.object(sc.subprocess, 'run') as run:
            run.return_value.returncode = 0
            for stamp in [1000, 1010]:
                stdin = type('Input', (), {'buffer': io.BytesIO(json.dumps(data).encode())})()
                with patch.object(sc.sys, 'stdin', stdin), patch.object(sc.time, 'time', return_value=stamp):
                    sc.capture(['footer'])
            result = sc.read_json(Path(tmp) / 'sessions/snapshots/abc.json')
            self.assertEqual(result['observedAt'], 1000)
            self.assertEqual(result['capturedAt'], 1010)
            self.assertEqual(result['windows'][0]['observedAt'], 1000)

    def test_codex_hook_uses_exact_transcript_and_ignores_daemon_and_subagent(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(sc, 'ROOT', Path(tmp)):
            home = Path(tmp) / 'codex'
            path = home / 'sessions' / 'rollout-2026-10-10T00-00-00-12345678-1234-1234-1234-123456789abc.jsonl'
            path.parent.mkdir(parents=True)
            path.write_text(json.dumps({'payload': {'source': 'cli'}}) + '\n')
            data = {'session_id': 'hook-session', 'transcript_path': str(path), 'hook_event_name': 'Stop', 'prompt': 'private'}
            with patch.dict(sc.os.environ, {'CODEX_HOME': str(home)}):
                for tty in [False, True]:
                    stdin = type('Input', (), {'read': lambda _: json.dumps(data)})()
                    with patch.object(sc.sys, 'stdin', stdin), patch.object(sc, 'tool_parent', return_value={'pid':12,'start':'1','tty':tty}):
                        self.assertEqual(sc.codex_hook(), 0)
                    result = sc.read_json(Path(tmp) / 'sessions/codex-processes/12.json')
                    if not tty:
                        self.assertIsNone(result)
                    else:
                        self.assertEqual(result['sessionId'], '12345678-1234-1234-1234-123456789abc')
                        self.assertNotIn('private', json.dumps(result))
                path.write_text(json.dumps({'payload': {'source': {'subagent': {}}}}) + '\n')
                data['hook_event_name'] = 'UserPromptSubmit'
                stdin = type('Input', (), {'read': lambda _: json.dumps(data)})()
                with patch.object(sc.sys, 'stdin', stdin), patch.object(sc, 'tool_parent', return_value={'pid':12,'start':'1','tty':True}):
                    sc.codex_hook()
                self.assertEqual(sc.read_json(Path(tmp) / 'sessions/codex-processes/12.json')['event'], 'Stop')


class RolloutTests(unittest.TestCase):
    def test_codex_last_usage_and_footer_baseline_not_cumulative(self):
        state = {}
        sc.TranscriptReader.apply(state, {'type': 'event_msg', 'timestamp': '2026-10-10T00:00:00Z',
            'payload': {'type': 'token_count', 'info': {'model_context_window': 258400,
            'last_token_usage': {'total_tokens': 173998}, 'total_token_usage': {'total_tokens': 9999999}}}}, 'codex')
        self.assertEqual(state['contextTokens'], 173998)
        self.assertEqual(state['contextUsedPercent'], 66)
        sc.TranscriptReader.apply(state, {'type': 'compacted', 'timestamp': '2026-10-10T00:01:00Z'}, 'codex')
        self.assertIsNone(state['contextUsedPercent'])
        self.assertIsNone(state['contextTokens'])

    def test_partial_jsonl_retried_and_post_compaction_metadata_replaces_old(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'session.jsonl'
            reader = sc.TranscriptReader()
            record = {'type': 'assistant', 'message': {'model': 'claude-example', 'usage': {'input_tokens': 100}}}
            text = json.dumps(record)
            path.write_text(text[:20])
            self.assertEqual(reader.read(path, 'claude'), {})
            path.write_text(text + '\n')
            self.assertEqual(reader.read(path, 'claude')['contextTokens'], 100)
            with path.open('a') as f:
                f.write(json.dumps({'type': 'system', 'subtype': 'compact_boundary'}) + '\n')
            self.assertIsNone(reader.read(path, 'claude')['contextTokens'])
            with path.open('a') as f:
                record['message']['usage']['input_tokens'] = 20
                f.write(json.dumps(record) + '\n')
            self.assertEqual(reader.read(path, 'claude')['contextTokens'], 20)

    def test_quota_windows_are_latest_per_account_not_summed(self):
        with tempfile.TemporaryDirectory() as tmp:
            collector = sc.Collector(Path(tmp), Path(tmp))
            rows = [{'source': 'claude', 'windows': sc.normalized_windows({'five_hour': {'used_percentage': pct, 'resets_at': 9000}}, stamp, 'footer')}
                    for pct, stamp in [(20, 10), (30, 20), (90, 5)]]
            self.assertEqual(collector.claude_windows(rows)[0]['usedPercent'], 30)

    def test_pid_reuse_and_headless_registry_are_excluded(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            path = home / '.claude/sessions'; path.mkdir(parents=True)
            for pid in [12, 13]:
                (path / f'{pid}.json').write_text(json.dumps({'pid': pid, 'sessionId': f'sid-{pid}', 'procStart': 'old'}))
            collector = sc.Collector(home / 'dashboard', home)
            with patch.object(sc, 'proc_info', side_effect=lambda pid: {'pid': int(pid), 'comm': 'claude', 'tty': pid != 13, 'start': 'new', 'parent': 1}):
                result = collector.collect(1000)
            self.assertEqual(result['sessions'], [])

    def test_exited_session_preserves_last_reading_and_end_time(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            sc.atomic_json(root / 'sessions/state.json', {'sessions': [{'source': 'claude', 'sessionId': 'old', 'contextUsedPercent': 12}], 'claudeWindows': []})
            collector = sc.Collector(root, root)
            with patch.object(sc, 'proc_info', return_value=None):
                result = collector.collect(1000)
                self.assertEqual(result['sessions'][0]['status'], 'ended')
                self.assertEqual(result['sessions'][0]['contextUsedPercent'], 12)
                self.assertEqual(collector.collect(1100)['sessions'][0]['endedAt'], 1000)


if __name__ == '__main__':
    unittest.main()
