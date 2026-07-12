# ccc-usage-dashboard

**English** | [日本語](README.ja.md)

**CCC (Codex and Claude Code) Usage Dashboard** is a local, single-binary usage,
cost, quota, and attribution dashboard for Codex and Claude Code.

It shows Codex and Claude Code on separate dashboard tabs, with usage history,
token totals, estimated USD cost, and attribution breakdowns. Codex USD cost is
estimated from credits at 1000 credits = $40; Claude Code USD cost is calculated
from Claude Code telemetry using the Claude API pricing table.

## Dashboard Preview

The screenshots below show a 12-hour range at 5-minute grain.

### Codex

![Codex dashboard charts for a 12-hour range at 5-minute grain](docs/assets/codex-dashboard-12h-5m.jpg)

### Claude Code

![Claude Code dashboard charts for a 12-hour range at 5-minute grain](docs/assets/claude-dashboard-12h-5m.jpg)

## Requirements

- Codex tab: Codex OTLP export configured (see
  [Configure Codex OTLP](#configure-codex-otlp))
- Codex usage % gauges: Codex CLI installed and signed in
- Claude Code tab: Claude Code telemetry configured (see
  [Configure Claude Code OTLP](#configure-claude-code-otlp))

The dashboard can run with only one tool enabled. Set
`CCC_USAGE_DASHBOARD_CODEX_ENABLED=false` on machines that do not use Codex, or
`CCC_USAGE_DASHBOARD_CLAUDE_ENABLED=false` on machines that do not use Claude
Code.

## Quick Start

The prebuilt binary is **macOS only** (Apple Silicon). Download it from the
[Releases](../../releases) page, then run it:

```sh
unzip ccc-usage-dashboard-macos-arm64.zip
chmod +x ccc-usage-dashboard
./ccc-usage-dashboard
```

Release binaries through `v0.2.0` are not signed or notarized. If macOS blocks
one of these binaries with "Apple could not verify" and you trust the downloaded
file, remove the quarantine attribute and run it again:

```sh
xattr -d com.apple.quarantine ccc-usage-dashboard
./ccc-usage-dashboard
```

This workaround is not required for `v0.3.0` or later releases, which are
signed and notarized.

On another platform, or want to build it yourself? Build from source — see
[`dev_docs/development.md`](dev_docs/development.md).

Open:

```text
http://127.0.0.1:4318/
```

Use the top-bar tabs to switch between Codex and Claude Code. The range selector
supports relative windows, the current Codex 5h usage window, and a custom
from/to range.

Most charts and usage tables have a local **Cost / Tokens** toggle. The toggle is
per panel, so you can compare one breakdown by USD cost while keeping another in
raw token counts.

By default the app listens only on localhost:

- OTLP gRPC: `127.0.0.1:4317`
- HTTP dashboard and OTLP/HTTP protobuf: `127.0.0.1:4318`

The local database is created at:

```text
~/.ccc-usage-dashboard/data/ccc-usage-dashboard.sqlite
```

The config file is `~/.ccc-usage-dashboard/config/application.properties`, and logs are written under
`~/.ccc-usage-dashboard/logs/`. On the first v0.3 start, an existing
`./data/codex-usage-dashboard.sqlite` is copied safely when the new database does
not exist. See [Application paths and legacy migration](docs/application-paths.md)
for precedence, overrides, backup, and rollback details.

## Configure Codex OTLP

The basic setup points Codex's OTLP log export directly at this dashboard's gRPC
receiver. Edit `~/.codex/config.toml`:

```toml
[otel]
exporter = { otlp-grpc = { endpoint = "http://127.0.0.1:4317" } }
```

Restart Codex after changing the config. New Codex activity should start filling
the dashboard within about a minute.

Keep this dashboard process running while you use Codex or Claude Code to capture
new OTLP events. Events emitted while it is stopped are not backfilled. To keep it
running automatically in the background, see
[Running as a service](docs/running-as-a-service.md).

## Configure Claude Code OTLP

Claude Code sends OTLP log/events to the same local gRPC receiver. Token and cost
charts are populated from Claude Code telemetry.

For persistent setup, edit `~/.claude/settings.json` and merge these entries into
the existing `env` object:

```json
{
  "env": {
    "CLAUDE_CODE_ENABLE_TELEMETRY": "1",
    "OTEL_LOGS_EXPORTER": "otlp",
    "OTEL_EXPORTER_OTLP_PROTOCOL": "grpc",
    "OTEL_EXPORTER_OTLP_ENDPOINT": "http://127.0.0.1:4317"
  }
}
```

Restart Claude Code, or start a new session, after changing the settings file.

For a one-off session, set the same values in the shell before launching
`claude`:

```sh
export CLAUDE_CODE_ENABLE_TELEMETRY=1
export OTEL_LOGS_EXPORTER=otlp
export OTEL_EXPORTER_OTLP_PROTOCOL=grpc
export OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4317
claude
```

Claude Code cost is calculated from the token counts in its telemetry. If Claude
Code also reports its own `cost_usd`, the dashboard keeps that value separately
for comparison.

For the full Claude Code telemetry configuration surface, see the
[Claude Code Monitoring docs](https://code.claude.com/docs/en/monitoring-usage).

## Already Exporting OTLP Elsewhere?

This dashboard listens on the standard OTLP ports (gRPC `:4317`, HTTP `:4318`) so
Codex and Claude Code can point at it directly. If you already run an
[OpenTelemetry Collector](https://opentelemetry.io/docs/collector/) on those ports,
keep your tools pointed at the collector, fan out a copy to this dashboard, and
move the dashboard off the standard ports.

Put persistent overrides in `~/.ccc-usage-dashboard/config/application.properties`:

```properties
# ~/.ccc-usage-dashboard/config/application.properties
quarkus.grpc.server.port=14317
quarkus.http.port=14318
```

Then have the collector send OTLP/HTTP protobuf to `http://127.0.0.1:14318/v1/logs`
(or gRPC to `:14317`).

## LAN Access

The dashboard is local-only by default. It has **no authentication**, and the
raw-log drill-down (`/api/events/{id}/raw`) returns verbatim OTLP records that can
include working directories, conversation ids, host names, and account metadata.
Exposing it on your LAN gives **every device on the network unauthenticated read
access to that data** — only do this on a trusted network.

To intentionally expose it on your LAN:

```sh
QUARKUS_HTTP_HOST=0.0.0.0 \
QUARKUS_GRPC_SERVER_HOST=0.0.0.0 \
./ccc-usage-dashboard
```

Then open `http://<machine-ip>:4318/` from another device.

For remote access without exposing the unauthenticated dashboard to every device
on your LAN, you can keep the app bound to localhost and reach it over
[Tailscale Serve](docs/running-as-a-service.md#secure-remote-access-with-tailscale-serve)
instead.

## Configuration

The installed application's config file is
`~/.ccc-usage-dashboard/config/application.properties`. Create it when you need
to override a default. See
[Application paths and legacy migration](docs/application-paths.md) for the full
precedence and migration rules. Useful properties:

```properties
# Tool-specific ingestion flags
ccc-usage-dashboard.codex.enabled=true
ccc-usage-dashboard.claude.enabled=true

# Ports / bind addresses
quarkus.http.host=127.0.0.1
quarkus.http.port=4318
quarkus.grpc.server.host=127.0.0.1
quarkus.grpc.server.port=4317

# Optional custom database
quarkus.datasource.jdbc.url=jdbc:sqlite:/absolute/path/custom.sqlite?journal_mode=WAL&busy_timeout=10000

# Local telemetry retention
ccc-usage-dashboard.retention.every=1h
ccc-usage-dashboard.retention.otel-log-records=14d
ccc-usage-dashboard.retention.annotated-events=365d
ccc-usage-dashboard.retention.usage-samples=365d

# Codex local data directory
codex.db.dir=/Users/you/.codex
codex.bin=codex

# Receive-time drop filter for noisy OTLP log records
ccc-usage-dashboard.ingest.drop-event-kinds=^response\\..+\\.delta$

# Advanced polling / batch tuning
ccc-usage-dashboard.annotate.every=60s
ccc-usage-dashboard.annotate.batch-size=500
ccc-usage-dashboard.usage.every=60s
```

Every property can still be overridden with its Quarkus/MicroProfile environment
variable form. Existing `CODEX_USAGE_DASHBOARD_*` names remain supported during
the v0.3 transition; the corresponding `CCC_USAGE_DASHBOARD_*` name wins when
both are present.

Set `CCC_USAGE_DASHBOARD_CODEX_ENABLED=false` on machines without Codex. This
ignores Codex OTLP logs and skips Codex usage polling, so the app will not try
to launch `codex`. Set `CCC_USAGE_DASHBOARD_CLAUDE_ENABLED=false` to ignore
Claude Code OTLP logs. Disabled tools are also hidden from the dashboard tool
switcher.

Retention is enforced independently for each kind of local data:

- Raw OTLP log records: the original Codex and Claude Code OTLP log payloads used
  by the raw event drill-down. These are usually the largest records. The
  configured retention age is a cleanup threshold, not a guarantee that every
  older record disappears immediately.
- Dashboard history: parsed token, cost, trigger, and error data used by charts
  and tables.
- Codex usage samples: periodic Codex rate-limit percentage snapshots.

Set any retention value to `0` or `disabled` to keep that data type indefinitely.
Deleting old raw OTLP records does not remove already-built chart history, but
raw event drill-down for those older events will no longer be available. SQLite
may reuse freed pages without immediately shrinking the database file; run
`VACUUM` manually if you need to compact the file on disk.

By default, receive-time ingestion drops high-volume Codex streaming delta records
whose `event.kind` matches `^response[.].+[.]delta$`. Those rows do not carry token
usage and can be much noisier than completed request records. Set
`ccc-usage-dashboard.ingest.drop-event-kinds` to an empty value if you need to
store every received OTLP log record.

## OTLP Support

Supported:

- OTLP/gRPC on `:4317`
- OTLP/HTTP protobuf on `:4318/v1/logs`
- gzip-compressed OTLP/HTTP protobuf bodies
- Claude Code OTLP log `api_request` records with token and `cost_usd` fields

Dashboard charts are based on OTLP logs. Metrics and traces may be accepted by
the receiver, but they are not shown in dashboard charts.

Not supported:

- OTLP/HTTP JSON (`Content-Type: application/json`)

OTLP/HTTP JSON requests return `415 Unsupported Media Type` so misconfigured
exporters do not appear to succeed while silently dropping data. Make sure any
OTLP/HTTP exporter sends protobuf (`encoding: proto`), not JSON.

## Development Docs

Developer-facing notes live in [`dev_docs/`](dev_docs/). Start with:

- [`dev_docs/architecture.md`](dev_docs/architecture.md)
- [`dev_docs/development.md`](dev_docs/development.md)
- [`dev_docs/token-accounting.md`](dev_docs/token-accounting.md)

## License

Licensed under the [Apache License, Version 2.0](LICENSE). See [`NOTICE`](NOTICE)
for attribution of bundled third-party components (Apache ECharts).
