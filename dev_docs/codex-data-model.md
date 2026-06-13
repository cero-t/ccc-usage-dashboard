# Observed Codex Data Model

This document records the Codex-local signals the Java dashboard depends on.
These files are private Codex implementation details, so treat this as an
observed contract, not a stable upstream API.

Validated locally on 2026-06-09 against:

- `~/.codex/sqlite/logs_2.sqlite`
- `~/.codex/sqlite/state_5.sqlite`
- legacy layout under `~/.codex/`
- `data/codex-usage-dashboard.sqlite`

## OTLP Completion Records

Token-bearing completion rows are OTLP log records with:

```text
attributes.event.name = codex.sse_event
attributes.event.kind = response.completed
attributes.input_token_count
attributes.cached_token_count
attributes.output_token_count
attributes.reasoning_token_count
attributes.tool_token_count
attributes.conversation.id
attributes.model
attributes.originator
resource_attributes.host.name
```

Important absence:

- OTLP completion rows did not include `turn.id`, `turn_id`,
  `submission.id`, or `submission_id`.
- `attributes.conversation.id` is the only stable conversation/thread key seen
  on the completion event itself.

Therefore, a completion row can join directly to `state_5.threads.id` by
`conversation.id`, but it cannot directly join to a Codex turn/submission.

## `logs_2.sqlite`

Observed schema:

```sql
CREATE TABLE logs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts INTEGER NOT NULL,
  ts_nanos INTEGER NOT NULL,
  level TEXT NOT NULL,
  target TEXT NOT NULL,
  feedback_log_body TEXT,
  module_path TEXT,
  file TEXT,
  line INTEGER,
  thread_id TEXT,
  process_uuid TEXT,
  estimated_bytes INTEGER NOT NULL DEFAULT 0
);
```

`submission.id` and `turn.id` are not table columns. They appear inside
`feedback_log_body`, usually as span context, for example:

```text
submission_dispatch{otel.name="op.dispatch.user_input" submission.id="..."}
turn{otel.name="session_task.turn" thread.id=... turn.id=... model=...}
run_sampling_request{turn_id=... model=... cwd=...}
```

Observed cardinality in the 2026-06-09 snapshot:

| relationship | result |
|---|---:|
| complete `(thread_id, submission_id, turn_id)` triples | 153 |
| `submission_id == turn_id` | 153 / 153 |
| submissions with multiple turns | 0 |
| turns with multiple submissions | 0 |
| max turns per thread | 3 |

So, within the observed data, `submission_id` and `turn_id` are equivalent for a
user-input turn, and both are many-to-one under `thread_id`.

## OTLP-To-Turn Correlation

`logs_2` and OTLP can be correlated by `thread_id` / `conversation.id` and time,
but this is low coverage for token-bearing completion rows.

Observed on the 2026-06-09 snapshot:

| time window around OTLP completion | no turn candidate | one candidate | multiple candidates |
|---|---:|---:|---:|
| +/- 0.1s | 106 | 5 | 0 |
| +/- 10s | 106 | 5 | 0 |
| +/- 30s | 105 | 6 | 0 |
| +/- 60s | 102 | 7 | 2 |
| +/- 300s | 89 | 17 | 5 |

The turn/submission IDs are UUIDv7-like, so their leading timestamp can be used
as a secondary heuristic: choose the latest turn in the same thread whose UUID
timestamp is before the completion. In the same snapshot this matched 21 / 111
completion rows. That improves coverage but is still incomplete, and it is a
heuristic rather than a direct join.

Do not compute credit-impacting service tier from a broad time-window join.

An interval-based join is more defensible than a point-in-time proximity join:

1. Build observed turn intervals per thread from `logs_2` rows that carry the
   same `(thread_id, submission_id, turn_id)`.
2. Use `min(ts + ts_nanos)` as `turn_observed_start`.
3. Use `max(ts + ts_nanos)` as `turn_observed_end`.
4. Assign an OTLP event to a turn only when `conversation.id = thread_id` and
   the OTLP event time falls inside exactly one observed turn interval, allowing
   only a small clock/export skew margin.

This relies on two assumptions:

- turns do not overlap within one thread;
- `logs_2` contains enough rows to make the observed interval cover the OTLP
  completion event.

When both hold, this is a reasonable medium-confidence attribution path. When
the event falls outside all observed intervals, or inside multiple intervals
after applying a wide margin, leave the turn unknown and avoid Fast surcharge.

## Service Tier Signals

OTLP completion rows do not export service tier.

Useful request-config carriers in `logs_2.feedback_log_body`:

```text
target = codex_core::client
service_tier: Some("priority")

target = codex_core::session::handlers
service_tier: Some(Some("priority"))
service_tier: Some(None)
```

Observed request-config rows were internally consistent per turn:

| source targets | complete rows | distinct turns | conflicts |
|---|---:|---:|---:|
| `codex_core::client`, `codex_core::session::handlers` | 60 | 60 | 0 |

These targets are high precision when they can be tied to the same turn.

Avoid using `codex_api::endpoint::responses_websocket` as the service-tier source
for credit calculation. It carried values such as `auto`, `default`, and
`priority`, but in the observed snapshot 98 / 137 turns had conflicting values
when grouped by turn.

Recommended confidence model:

| source | meaning | confidence |
|---|---|---:|
| `logs2_request_turn` | same-turn `client` / `session::handlers` request config | high |
| `logs2_observed_turn_interval` | OTLP event falls inside one non-overlapping observed turn interval, then request config | medium |
| `logs2_uuid_turn` | UUIDv7 time inferred turn, then request config | medium |
| `assumed_standard` | no turn-level tier; compute at standard rate | low |
| `legacy_thread_latest` | latest tier seen anywhere in thread | low |

Current implementation status: service-tier attribution is disabled.
`AnnotateJob` writes `service_tier = NULL` and passes `null` to `RateCard`, so
new annotated rows are computed at standard rates. The confidence model above is
for a future per-turn implementation.

For credit calculation, prefer high-confidence `priority`. If medium-confidence
interval attribution is used for estimated credits, keep `service_tier_source`
and confidence visible so the dashboard can distinguish strict and estimated
Fast surcharge. Missing tier should be computed as standard-rate credits and
exposed as low confidence.

## Trigger Accuracy Compared With Service Tier

Trigger classification has a stronger data shape than service-tier attribution:

- `user` trigger is a direct join: OTLP `conversation.id` exists in
  `state_5.threads.id`.
- `ambient` / `memory` are thread-level heuristics using signatures in
  `logs_2.feedback_log_body`.
- `background` is the fallback when no user thread or known signature is found.

Service tier is harder:

- the completion row lacks `turn_id` / `submission_id`;
- the reliable tier carrier is per turn, not per thread;
- some threads can contain multiple turns;
- the broader websocket tier payload is noisy for Fast/Standard accounting.

So trigger classification should generally be more accurate than service-tier
classification. A turn-level request-config tier has high precision when found,
but lower recall than trigger. The current thread-level latest-tier fallback is
less accurate than trigger and should not be used for Fast surcharge unless the
dashboard explicitly marks it as low confidence.
