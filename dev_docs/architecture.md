# Architecture

`codex-usage-dashboard` is a local append-oriented pipeline:

```text
Codex OTLP logs
  -> OTLP receiver
  -> otel_log_records
  -> annotate job
  -> annotated_events
  -> JSON API + static dashboard

codex app-server rateLimits/read
  -> usage job
  -> usage_samples
  -> JSON API + static dashboard
```

## Receive Path

Implemented by:

- `http/OtlpHttpResource`
- `otel/OtlpGrpcReceiver`
- `otel/RawLogStore`

Supported inputs:

- OTLP/gRPC on `:4317`
- OTLP/HTTP protobuf on `:4318/v1/logs`
- gzip-compressed OTLP/HTTP protobuf

Traces and metrics are accepted and discarded. Logs are converted mechanically
from protobuf to JSON and appended to `otel_log_records(record_json)`.

OTLP/HTTP JSON is intentionally not implemented. JSON requests return `415` so
an exporter configured for JSON cannot appear healthy while dropping data.

## Annotate Job

Implemented by `jobs/AnnotateJob`, scheduled every 60 seconds by default.

The job reads `otel_log_records` after cursor `annotate_log_id`, parses raw JSON,
and keeps rows that contain either:

- `attributes.input_token_count`
- `attributes.error.message`

It enriches kept rows from Codex local SQLite:

- `~/.codex/state_5.sqlite`, table `threads`
- `~/.codex/logs_2.sqlite`, table `logs`

The derived row is appended to `annotated_events`; raw rows are never mutated.

If a per-row annotate exception occurs, the pass stops before that row and leaves
the cursor at the last successfully processed raw row. The failing row is retried
on the next pass so parser/DB bugs remain replayable without manual cursor rewind.

## Usage Job

Implemented by `jobs/UsageJob`, scheduled every 60 seconds by default.

Usage is not in Codex SQLite. The job launches:

```sh
codex app-server --listen stdio://
```

and sends JSON-RPC:

```text
initialize
initialized
account/rateLimits/read
```

Each returned primary/secondary usage window is appended to `usage_samples`.

## Tables

Owned database:

```text
data/codex-usage-dashboard.sqlite
```

Tables:

- `otel_log_records`: raw OTLP log records as JSON
- `annotated_events`: parsed token/error rows plus enrichment and credits
- `usage_samples`: point-in-time rate-limit snapshots
- `cursor`: forward cursor state

Important indexes:

- `idx_annotated_events_source`: raw-to-derived join
- `idx_annotated_events_event_epoch`: dashboard time filtering
- `idx_annotated_events_credit_epoch`: credit time filtering
- `idx_usage_samples_window_sampled`: usage history by window/time

## Dashboard

The dashboard is a static `index.html` served from:

```text
src/main/resources/META-INF/resources/
```

It uses vendored Apache ECharts and calls JSON endpoints under `/api`.

Dashboard time-series endpoints accept:

```text
range=15m|30m|1h|3h|6h|12h|1d|3d|1w|30d|6mo
grain=1m|5m|30m|1h|12h|1d
```

Default:

```text
range=6h
grain=5m
```

## Security Posture

The default bind address is `127.0.0.1`.

This is deliberate. The dashboard exposes local Codex metadata, and
`/api/events/{id}/raw` returns the raw OTLP record behind a derived row. Raw OTLP
can include account identifiers, email addresses, host names, conversation ids,
and request/error metadata.

LAN access should be an explicit user choice via:

```sh
QUARKUS_HTTP_HOST=0.0.0.0
QUARKUS_GRPC_SERVER_HOST=0.0.0.0
```
