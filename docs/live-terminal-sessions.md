# Live terminal sessions and subscription windows

This fork adds a read-only `/api/sessions` endpoint and a local Python collector.
The dashboard shows current terminal-session context separately from cumulative
usage/cost charts, plus account-wide 5-hour and weekly subscription windows.

## Linux / WSL setup

Requires Python 3.9+ and the normal dashboard JVM/native runtime. The collector
uses only the Python standard library. It reads Linux `/proc` to verify terminal
processes and their start identities; Windows-native and headless sessions are
not collected.

Run the collector as the same user as Claude Code and Codex:

```sh
python3 integrations/session_collector.py collect
# Or through PM2:
pm2 start integrations/session_collector.py --name ccc-session-collector \
  --interpreter python3 -- collect
pm2 save
```

Use the same `CCC_USAGE_DASHBOARD_HOME` for collector and dashboard when overriding
the default `~/.ccc-usage-dashboard`.

### Claude footer capture

Wrap the existing `statusLine.command` in `~/.claude/settings.json`, preserving
its original command as the argument to `/bin/sh -c`. For example:

```json
{
  "statusLine": {
    "type": "command",
    "command": "python3 /path/to/ccc-usage-dashboard/integrations/session_collector.py capture -- /bin/sh -c 'YOUR ORIGINAL FOOTER COMMAND'",
    "refreshInterval": 10
  }
}
```

Back up the original settings first. Quote the original command for the shell
with `shlex.quote` rather than interpolating unescaped text. Preserve all other
status-line options, existing telemetry settings, and hooks. Claude reloads the
changed command; its normal footer output still comes from the original script.
Snapshots are atomic, owner-readable files containing allowlisted metrics only.
Capture errors do not affect the footer. There is no network request on this
capture path.

The collector verifies `~/.claude/sessions/*.json` registry entries against the
live PID, process start identity, and terminal stdin. Idle sessions remain visible;
exits and crashes move sessions to a recently ended section for 24 hours. Context
percentage/capacity comes from the official footer. For a session not yet captured,
transcript metadata may show current input tokens while capacity and percentage
remain unknown. Prompt text, message content, and credentials are never persisted
by this collector.

### Codex identity hooks

Merge this handler into `SessionStart`, `UserPromptSubmit`, and `Stop` in
`~/.codex/hooks.json`, preserving existing handlers:

```json
{
  "hooks": {
    "SessionStart": [{"hooks": [{
      "type": "command",
      "command": "python3 /path/to/ccc-usage-dashboard/integrations/session_collector.py codex-hook",
      "timeout": 2
    }]}]
  }
}
```

Review and trust these metric hooks in Codex's `/hooks` UI. They produce no output
or conversation instructions. For new interactive terminals, launch `codex --no-daemon` so the hook runs
under the terminal process rather than a shared daemon. The normal terminal UI
and authentication remain the same; background app-server/SDK commands can keep
using the shared daemon. A user launcher may add this flag only for interactive
launches, with a backup and opt-out. Shared-daemon sessions without an explicit
resume ID cannot be reliably assigned to terminal PIDs and are not guessed.

The hook associates the exact rollout path with a
terminal process; explicit resumed-session IDs and open rollout handles provide
additional identity sources. No heuristic picks the newest global rollout.

Codex context uses the latest `token_count.info.last_token_usage.total_tokens`,
never cumulative totals. Its percentage matches the v0.161 footer's 12,000-token
baseline calculation. A compaction clears the reading until subsequent token
usage arrives. Rollout metadata is version-sensitive and may require adjustment
on a Codex upgrade.

## Freshness and scope

Claude account limits use the newest reported `rate_limits` snapshot or the
existing Claude usage cache, one value per account window. Reports from multiple
sessions are never added together. This collector does not fetch OAuth credentials
or call an undocumented subscription endpoint. Idle refresh timers do not make an
unchanged reading fresh. Unknown/expired windows show `Unknown`, with reset times
and countdowns only for an unexpired reported window.

Codex subscription usage still comes from upstream's app-server quota poller.
The API adds Unix observation timestamps so viewers in other timezones see correct
ages. Context/session state refreshes every 5 seconds; countdowns update every
second. Historical chart range selectors do not filter live sessions.

Only reading is exposed over HTTP. All metrics ingestion happens through local
files owned by the user. Keep the unauthenticated dashboard on localhost and use
Tailscale Serve for tailnet access, as described in the original service guide.

## Validation

```sh
python3 -m unittest discover -s integrations -p 'test_*.py' -v
JAVA_HOME=/path/to/jdk25 ./mvnw test package
```

Compare session values with actual terminal footers. Check simultaneous sessions,
idle/resume/exit, PID reuse, compaction, missing context, quota expiry, and collector
failure. The Java endpoint has explicit not-started and unavailable states; the UI
shows collector and metric freshness instead of treating unknown readings as zero.

Sources: [Claude status line](https://code.claude.com/docs/en/statusline),
[Codex hooks](https://learn.chatgpt.com/docs/hooks),
[Codex v0.161 context formula](https://github.com/openai/codex/blob/rust-v0.161.0/codex-rs/protocol/src/protocol.rs).
