# codex-usage-dashboard

**English** | [日本語](README_ja.md)

`codex-usage-dashboard` is a local, single-binary dashboard for Codex usage,
credit estimates, and attribution.

It receives Codex OTLP logs, enriches them with local Codex metadata, polls the
current Codex usage window, and visualizes them in a dashboard.

## Requirements

- **Codex CLI**, installed and signed in
  - The dashboard still runs without it (credits and events come from OTLP), but
    the usage % gauges stay empty
- Codex set up to export OTLP (see [Configure Codex OTLP](#configure-codex-otlp))

## Quick Start

The prebuilt binary is **macOS only** (Apple Silicon). Download it from the
[Releases](../../releases) page, then run it:

```sh
chmod +x codex-usage-dashboard
./codex-usage-dashboard
```

On another platform, or want to build it yourself? Build from source — see
[`dev_docs/development.md`](dev_docs/development.md).

Open:

```text
http://127.0.0.1:4318/
```

By default the app listens only on localhost:

- OTLP gRPC: `127.0.0.1:4317`
- HTTP dashboard and OTLP/HTTP protobuf: `127.0.0.1:4318`

The local database is created at:

```text
data/codex-usage-dashboard.sqlite
```

relative to the directory where you run the binary.

## Configure Codex OTLP

The basic setup points Codex's OTLP log export directly at this dashboard's gRPC
receiver. Edit `~/.codex/config.toml`:

```toml
[otel]
exporter = { otlp-grpc = { endpoint = "http://127.0.0.1:4317" } }
```

Restart Codex after changing the config. New Codex activity should start filling
the dashboard within about a minute.

### Already exporting Codex OTLP elsewhere?

This dashboard listens on the standard OTLP ports (gRPC `:4317`, HTTP `:4318`) so
Codex can point at it directly. If you already run an
[OpenTelemetry Collector](https://opentelemetry.io/docs/collector/) on those ports,
keep Codex pointed at the collector, fan out a copy to this dashboard, and move
the dashboard off the standard ports.

Quarkus reads a `.env` file from the working directory, so put the overrides
there:

```sh
# .env
QUARKUS_GRPC_SERVER_PORT=14317
QUARKUS_HTTP_PORT=14318
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
./codex-usage-dashboard
```

Then open `http://<machine-ip>:4318/` from another device.

## Configuration

Useful environment variables:

```sh
# Ports / bind addresses
QUARKUS_HTTP_HOST=127.0.0.1
QUARKUS_HTTP_PORT=4318
QUARKUS_GRPC_SERVER_HOST=127.0.0.1
QUARKUS_GRPC_SERVER_PORT=4317

# Local storage
QUARKUS_DATASOURCE_JDBC_URL='jdbc:sqlite:data/codex-usage-dashboard.sqlite?journal_mode=WAL&busy_timeout=10000'

# Codex local state used for enrichment
CODEX_STATE5_PATH="$HOME/.codex/state_5.sqlite"
CODEX_LOGS2_PATH="$HOME/.codex/logs_2.sqlite"
CODEX_BIN=codex
```

## OTLP Support

Supported:

- OTLP/gRPC on `:4317`
- OTLP/HTTP protobuf on `:4318/v1/logs`
- gzip-compressed OTLP/HTTP protobuf bodies

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
