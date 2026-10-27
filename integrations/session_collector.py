#!/usr/bin/env python3
"""Local-only session collector. Persists metrics, never prompts or credentials."""
import argparse
import datetime as dt
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time

ROOT = Path(os.environ.get('CCC_USAGE_DASHBOARD_HOME', Path.home() / '.ccc-usage-dashboard'))
ID = re.compile(r'^[a-zA-Z0-9_-]{1,100}$')


def read_json(path, default=None):
    try:
        return json.loads(Path(path).read_text())
    except (OSError, ValueError):
        return default


def atomic_json(path, value):
    path = Path(path)
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as f:
            json.dump(value, f, allow_nan=False)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def number(value):
    return value if isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) else None


def epoch(value):
    if number(value) is not None:
        return value / 1000 if value > 1e12 else value
    try:
        return dt.datetime.fromisoformat(value.replace('Z', '+00:00')).timestamp()
    except (ValueError, TypeError, AttributeError):
        return None


def proc_info(pid):
    try:
        p = Path('/proc') / str(int(pid))
        # comm may contain spaces and parentheses: split after the last closing paren.
        fields = (p / 'stat').read_text().rsplit(') ', 1)[1].split()
        if fields[0] == 'Z':
            return None
        return {'pid': int(pid), 'parent': int(fields[1]), 'start': fields[19],
                'comm': (p / 'comm').read_text().strip(),
                'tty': os.readlink(p / 'fd/0').startswith(('/dev/pts/', '/dev/tty'))}
    except (OSError, ValueError, IndexError):
        return None


def tool_parent(tool):
    pid = os.getppid()
    for _ in range(12):
        info = proc_info(pid)
        if not info:
            break
        if info['comm'] == tool:
            return info
        pid = info['parent']
    return None


def claude_parent():
    return tool_parent('claude')


def codex_hook():
    try:
        data = json.load(sys.stdin)
        proc = tool_parent('codex')
        if not proc or not proc['tty']:
            return 0
        path = data.get('transcript_path')
        path = Path(path).resolve() if path else None
        codex_root = Path(os.environ.get('CODEX_HOME', Path.home() / '.codex')) / 'sessions'
        if path and not path.is_relative_to(codex_root.resolve()):
            return 0
        if path and path.exists():
            with path.open() as f:
                meta = json.loads(f.readline()).get('payload') or {}
            if isinstance(meta.get('source'), dict) and 'subagent' in meta['source']:
                return 0
        sid = path.name[-42:-6] if path and path.name.startswith('rollout-') else data.get('session_id', '')
        if not isinstance(sid, str) or not ID.fullmatch(sid):
            return 0
        atomic_json(ROOT / 'sessions/codex-processes' / (str(proc['pid']) + '.json'), {
            'pid': proc['pid'], 'procStart': proc['start'], 'sessionId': sid,
            'transcriptPath': str(path) if path else None,
            'cwd': data.get('cwd'), 'model': data.get('model'),
            'event': data.get('hook_event_name'), 'observedAt': time.time()})
    except Exception:
        pass  # Advisory metrics hook must not steer or block a session.
    return 0


def normalized_windows(limits, observed, origin):
    windows = []
    for key, label, duration in [('five_hour', '5-hour', 300), ('seven_day', 'Weekly', 10080)]:
        w = (limits or {}).get(key) or {}
        percent = number(w.get('used_percentage', w.get('utilization')))
        reset = epoch(w.get('resets_at'))
        if percent is not None and percent >= 0 and reset is not None:
            windows.append({'window': key, 'label': label, 'windowDurationMins': duration,
                            'usedPercent': percent, 'remainingPercent': max(0, 100 - percent),
                            'resetsAt': reset, 'observedAt': observed, 'origin': origin})
    return windows


def footer_snapshot(data, proc, now):
    sid = data.get('session_id', '')
    if not isinstance(sid, str) or not ID.fullmatch(sid) or not proc or not proc['tty']:
        return None
    ctx = data.get('context_window') or {}
    usage = ctx.get('current_usage')
    pct = number(ctx.get('used_percentage'))
    capacity = number(ctx.get('context_window_size'))
    tokens = None
    if isinstance(usage, dict):
        counts = [number(usage.get(k)) for k in ('input_tokens', 'cache_creation_input_tokens', 'cache_read_input_tokens')]
        if all(n is not None and n >= 0 for n in counts):
            tokens = sum(counts)
    cost = data.get('cost') or {}
    return {'source': 'claude', 'sessionId': sid, 'pid': proc['pid'], 'procStart': proc['start'],
            'name': data.get('session_name'), 'cwd': (data.get('workspace') or {}).get('current_dir') or data.get('cwd'),
            'model': (data.get('model') or {}).get('display_name') or (data.get('model') or {}).get('id'),
            'contextUsedPercent': pct if pct is not None and pct >= 0 else None,
            'contextTokens': tokens, 'contextCapacity': capacity,
            'contextOrigin': 'Claude footer', 'observedAt': now,
            'sessionCostUsd': number(cost.get('total_cost_usd')),
            'durationMs': number(cost.get('total_duration_ms')),
            'windows': normalized_windows(data.get('rate_limits'), now, 'Claude footer')}


def capture(command):
    raw = sys.stdin.buffer.read()
    try:
        snapshot = footer_snapshot(json.loads(raw), claude_parent(), time.time())
        if snapshot:
            previous = read_json(ROOT / 'sessions' / 'snapshots' / (snapshot['sessionId'] + '.json'), {})
            # Timer ticks repeat cached server data. Track capture separately from data freshness.
            keys = ('procStart', 'pid', 'model', 'contextUsedPercent', 'contextTokens', 'contextCapacity', 'sessionCostUsd')
            same_windows = [{k: w.get(k) for k in ('window', 'usedPercent', 'resetsAt')} for w in snapshot['windows']] == [{k: w.get(k) for k in ('window', 'usedPercent', 'resetsAt')} for w in previous.get('windows', [])]
            if all(snapshot.get(k) == previous.get(k) for k in keys) and same_windows:
                snapshot['observedAt'] = previous.get('observedAt', snapshot['observedAt'])
                snapshot['windows'] = previous.get('windows', snapshot['windows'])
            snapshot['capturedAt'] = time.time()
            atomic_json(ROOT / 'sessions' / 'snapshots' / (snapshot['sessionId'] + '.json'), snapshot)
    except Exception:
        # A collector outage must never hide or change the user's terminal footer.
        pass
    return subprocess.run(command, input=raw).returncode


class TranscriptReader:
    """Incremental JSONL metadata reader. Content fields are never copied to state."""
    def __init__(self):
        self.cache = {}

    def read(self, path, source):
        try:
            stat = path.stat()
            key = str(path)
            old = self.cache.get(key)
            if old and old['inode'] == stat.st_ino and old['offset'] <= stat.st_size:
                state, offset = dict(old['state']), old['offset']
                if offset == stat.st_size:
                    return state
            else:
                state, offset = {}, 0
            with path.open('rb') as f:
                f.seek(offset)
                for line in f:
                    if not line.endswith(b'\n'):
                        break  # Retry incomplete records next pass.
                    offset += len(line)
                    try:
                        record = json.loads(line)
                        self.apply(state, record, source)
                    except (ValueError, TypeError, AttributeError):
                        continue
            self.cache[key] = {'inode': stat.st_ino, 'offset': offset, 'state': state}
            return state
        except OSError:
            return {}

    @staticmethod
    def apply(state, r, source):
        stamp = epoch(r.get('timestamp'))
        if source == 'codex':
            p = r.get('payload') or {}
            if r.get('type') == 'turn_context':
                state['model'] = p.get('model')
                state['cwd'] = p.get('cwd')
            if r.get('type') == 'compacted' or p.get('type') == 'context_compacted':
                for k in ('contextTokens', 'contextCapacity', 'contextUsedPercent'):
                    state[k] = None
                state['observedAt'] = stamp
                state['contextOrigin'] = 'Awaiting post-compaction usage'
            if r.get('type') == 'event_msg':
                kind = p.get('type')
                if kind in ('task_started', 'turn_started'):
                    state['status'] = 'busy'
                elif kind in ('task_complete', 'turn_complete', 'turn_aborted'):
                    state['status'] = 'idle'
                if kind == 'token_count':
                    info = p.get('info') or {}
                    usage = info.get('last_token_usage') or {}
                    tokens, cap = number(usage.get('total_tokens')), number(info.get('model_context_window'))
                    if tokens is not None and cap is not None and cap > 12000:
                        # Match codex-rs protocol TokenUsage footer calculation (v0.161).
                        remaining = math.floor(max(0, min(1, (cap - 12000 - max(0, tokens - 12000)) / (cap - 12000))) * 100 + .5)
                        state.update(contextTokens=tokens, contextCapacity=cap,
                                     contextUsedPercent=100 - remaining, observedAt=stamp,
                                     contextOrigin='Codex rollout · footer formula')
        else:
            if r.get('type') == 'system' and r.get('subtype') == 'compact_boundary':
                state.update(contextTokens=None, observedAt=stamp)
            if r.get('type') == 'custom-title':
                state['name'] = r.get('customTitle')
            if r.get('type') == 'assistant' and not r.get('isSidechain'):
                message = r.get('message') or {}
                usage = message.get('usage') or {}
                if usage.get('input_tokens') is not None:
                    state.update(model=message.get('model'), observedAt=stamp,
                                 contextTokens=sum(usage.get(k, 0) or 0 for k in ('input_tokens', 'cache_creation_input_tokens', 'cache_read_input_tokens')))
                # Capacity is deliberately unknown until the official footer reports it.
                state['contextOrigin'] = 'Transcript · awaiting footer capacity'


class Collector:
    def __init__(self, root=ROOT, home=Path.home()):
        self.root, self.home = root, home
        self.reader = TranscriptReader()
        self.previous = read_json(root / 'sessions' / 'state.json', {}).get('sessions', [])
        self.paths = {}
        self.path_scan = 0

    def refresh_paths(self, now):
        if now - self.path_scan < 30:
            return
        self.path_scan = now
        for tool, base in [('claude', self.home / '.claude/projects'), ('codex', self.home / '.codex/sessions')]:
            for p in base.rglob('*.jsonl'):
                if tool == 'claude' and 'subagents' not in p.parts:
                    self.paths[(tool, p.stem)] = p
                elif tool == 'codex' and p.name.startswith('rollout-'):
                    self.paths[(tool, p.name[-42:-6])] = p

    def collect(self, now=None):
        now = time.time() if now is None else now
        self.refresh_paths(now)
        rows = []
        for p in (self.home / '.claude/sessions').glob('*.json'):
            d = read_json(p, {})
            proc = proc_info(d.get('pid'))
            sid = d.get('sessionId')
            if not proc or proc['comm'] != 'claude' or not proc['tty'] or str(d.get('procStart')) != proc['start'] or not isinstance(sid, str) or not ID.fullmatch(sid):
                continue
            row = {'source': 'claude', 'sessionId': sid, 'pid': proc['pid'], 'procStart': proc['start'],
                   'cwd': d.get('cwd'), 'name': d.get('name'), 'status': d.get('status') or 'running',
                   'startedAt': epoch(d.get('startedAt')), 'lastSeenAt': now}
            path = self.paths.get(('claude', sid))
            if path:
                row.update(self.reader.read(path, 'claude'))
            snap = read_json(self.root / 'sessions/snapshots' / (sid + '.json'), {})
            if snap.get('procStart') == proc['start'] and snap.get('pid') == proc['pid']:
                transcript_observed = row.get('observedAt')
                transcript_name = row.get('name')
                row.update(snap)
                if transcript_observed and row.get('contextTokens') is not None:
                    row['observedAt'] = transcript_observed
                row['status'] = d.get('status') or 'running'
                # Do not overwrite an explicitly set registry name with absent footer name.
                row['name'] = snap.get('name') or d.get('name') or transcript_name
                # Initial capture of an old idle session is not a fresh quota response.
                if transcript_observed:
                    for window in row.get('windows') or []:
                        window['observedAt'] = min(window.get('observedAt') or now, max(transcript_observed, row.get('startedAt') or 0))
            rows.append(row)
        for p in Path('/proc').iterdir():
            if not p.name.isdigit():
                continue
            proc = proc_info(p.name)
            if not proc or proc['comm'] != 'codex' or not proc['tty']:
                continue
            try:
                args = (p / 'cmdline').read_bytes().decode(errors='replace').split('\0')
                if 'app-server' in args or 'exec' in args:
                    continue
                sid, path = None, None
                mapping = read_json(self.root / 'sessions/codex-processes' / (str(proc['pid']) + '.json'), {})
                if mapping.get('procStart') == proc['start'] and mapping.get('pid') == proc['pid']:
                    sid = mapping.get('sessionId')
                    path = Path(mapping['transcriptPath']) if mapping.get('transcriptPath') else self.paths.get(('codex', sid))
                # Open rollout FD is the strongest identity, including resumed/new sessions.
                for fd in (p / 'fd').iterdir():
                    try:
                        target = Path(os.readlink(fd))
                        if target.name.startswith('rollout-') and target.suffix == '.jsonl':
                            sid, path = target.name[-42:-6], target
                            break
                    except OSError:
                        continue
                if not sid and 'resume' in args:
                    for arg in args:
                        if re.fullmatch(r'[0-9a-f-]{36}', arg):
                            sid, path = arg, self.paths.get(('codex', arg))
                            break
                # Match current process with its own open rollout. Never choose newest global session.
                if not sid:
                    continue
                ticks = int(proc['start']) / os.sysconf('SC_CLK_TCK')
                uptime = float(Path('/proc/uptime').read_text().split()[0])
                row = {'source': 'codex', 'sessionId': sid, 'pid': proc['pid'], 'procStart': proc['start'],
                       'cwd': os.readlink(p / 'cwd'), 'startedAt': now - uptime + ticks,
                       'lastSeenAt': now, 'status': 'running'}
                if path:
                    row.update(self.reader.read(path, 'codex'))
                if mapping.get('sessionId') == sid and mapping.get('procStart') == proc['start']:
                    if mapping.get('event') == 'UserPromptSubmit':
                        row['status'] = 'busy'
                    elif mapping.get('event') == 'Stop':
                        row['status'] = 'idle'
                    row['model'] = row.get('model') or mapping.get('model')
                rows.append(row)
            except OSError:
                continue
        live = {(r['source'], r['sessionId']) for r in rows}
        for old in self.previous:
            if (old['source'], old['sessionId']) not in live:
                ended = dict(old, status='ended', endedAt=old.get('endedAt') or now)
                if now - ended['endedAt'] < 86400:
                    rows.append(ended)
        rows.sort(key=lambda r: (r.get('status') == 'ended', -(r.get('observedAt') or 0)))
        windows = self.claude_windows(rows)
        result = {'generatedAt': now, 'sessions': rows, 'claudeWindows': windows}
        self.previous = rows
        atomic_json(self.root / 'sessions/state.json', result)
        return result

    def claude_windows(self, rows):
        # Reported windows are account-wide. Select the newest report per window; never sum sessions.
        cache = read_json(self.home / '.claude.json', {}).get('cachedUsageUtilization') or {}
        windows = normalized_windows(cache.get('utilization'), epoch(cache.get('fetchedAtMs')), 'Claude usage cache')
        for row in rows:
            if row.get('source') == 'claude':
                windows.extend(row.get('windows') or [])
        latest = {}
        for w in windows:
            if (w.get('observedAt') or 0) >= (latest.get(w['window'], {}).get('observedAt') or 0):
                latest[w['window']] = w
        return list(latest.values())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['collect', 'capture', 'codex-hook'])
    parser.add_argument('--once', action='store_true')
    raw_args = sys.argv[1:]
    split = raw_args.index('--') if '--' in raw_args else len(raw_args)
    args = parser.parse_args(raw_args[:split])
    args.command = raw_args[split + 1:]
    if args.mode == 'codex-hook':
        return codex_hook()
    if args.mode == 'capture':
        command = args.command[1:] if args.command[:1] == ['--'] else args.command
        if not command:
            parser.error('capture requires the original footer command after --')
        return capture(command)
    collector = Collector()
    while True:
        try:
            collector.collect()
        except Exception as e:
            print('Session collector:', type(e).__name__, file=sys.stderr, flush=True)
            if args.once:
                return 1
        if args.once:
            return 0
        time.sleep(5)


if __name__ == '__main__':
    sys.exit(main())
