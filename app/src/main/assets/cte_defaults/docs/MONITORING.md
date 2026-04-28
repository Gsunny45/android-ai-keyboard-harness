# Monitoring

Three observability surfaces, increasing in detail.

## 1. Suggestion-strip badges (always-on)

Tiny, glanceable. Emitted by the IME for the active request:

| Badge | Meaning |
|---|---|
| green dot | local provider healthy |
| yellow dot | one provider down; routed to fallback |
| red dot | all providers exhausted; request failed |
| `R` | response was retried after 429 |
| `K` | killSwitch active; forced local |
| `X` | redaction applied to payload |
| `$$` | trigger marked `budget: premium` (cloud) |

Tap the strip to see the last event in plain text.

## 2. Event log (`logs/events.jsonl`)

One JSON object per line. Schema:

```json
{
  "ts":          "2026-04-25T18:42:11Z",
  "kind":        "request" | "response" | "error" | "redact" | "killswitch" | "plugin",
  "trigger":     "/doc",
  "provider":    "local",
  "fallback_of": "anthropic",
  "latencyMs":   312,
  "inTokens":    180,
  "outTokens":   240,
  "ok":          true,
  "errorClass":  null,
  "extra":       { ... }
}
```

Bounded at 5 MiB. Rotated by `scripts/rotate_logs.sh`. Keep last 7 archives.

## 3. Run capture (`runs/*.jsonl`, opt-in)

Off by default. When on, captures the **fully resolved** prompt and the
streamed response for the last N requests. Useful for:

- Debugging "why did this trigger respond this way?"
- Building eval fixtures (`evals/golden/`).
- Auditing what the cloud actually saw post-redaction.

Each run file is one request; filename is `<ts>_<trigger>.jsonl`.

```json
{ "phase": "context",  "vars": { "vault.name": "MyVault", ... } }
{ "phase": "prompt",   "system": "...", "user": "..." }
{ "phase": "stream",   "delta": "Layered " }
{ "phase": "stream",   "delta": "context " }
{ "phase": "done",     "fullText": "...", "tokens": 240, "latencyMs": 312 }
```

## 4. llama-server `/metrics`

Prometheus-format text. Polled by `LlamaServerService` every 5s while the
keyboard is foreground. Sticky notification shows tok/sec.

```
# HELP llama_request_tokens_per_second
llama_request_tokens_per_second 14.3
```

## 5. Cost dashboard (Settings → AI → Cost)

Reads `logs/events.jsonl`. Aggregates last 7 days per provider. No data
ever leaves the device.

## Alert rules

The IME doesn't send alerts (no notification spam), but does:

- Auto-pause cloud calls when daily cap reached. Strip shows `$$!`.
- Surface a one-time toast when killSwitch flipped.
- Log to `events.jsonl` with `kind:"error"` on every plugin throw.
