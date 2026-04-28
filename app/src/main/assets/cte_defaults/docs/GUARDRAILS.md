# Guardrails

Power-user does not mean reckless. Defaults err safe; every guardrail is
a flag the user can flip with intent.

## Network

| Guardrail | Default | Where |
|---|---|---|
| `routing.killSwitch` — force all requests local-only | `false` | `configs/routing.json` |
| Refuse to bind llama-server beyond `127.0.0.1` | enforced | `scripts/start_llama.sh` |
| Per-provider daily token cap | set per provider | `configs/triggers.json` |
| Honor 429 Retry-After | `true` | `configs/routing.json` |
| Circuit breaker (2 failures → 60s open) | on | `configs/routing.json` |

## Payload

| Guardrail | Default | Notes |
|---|---|---|
| Redact `{{clipboard.text}}` over N chars on cloud | 256 | per-trigger override via `redact_payload: false` |
| Redact `{{selection.text}}` over N chars on cloud | 256 | same |
| PII regex pre-flight (SSN, card, email) | warn_and_strip | `routing.guardrails.piiPatterns` |
| Plugin pre-prompt hook (`cte.beforePrompt`) can `req.cancel()` | available | see `plugins/example_resolver.kts.tmpl` |

## Storage

| Guardrail | Default | Notes |
|---|---|---|
| API keys in `EncryptedSharedPreferences` (AES256-GCM) | enforced | KeyVault.kt |
| Real keys never in JSON | enforced | `validate_configs.sh` greps before push |
| `runs/` capture (full prompts+responses) | **off** | Settings → AI → Diagnostics |
| Event log size cap | 5 MiB → rotate, keep 7 | `scripts/rotate_logs.sh` |
| `runs/` keepLast | 50 | `routing.monitoring.runsKeepLast` |

## Process

| Guardrail | Default | Notes |
|---|---|---|
| Plugin failures isolated, logged, skipped | enforced | PluginLoader |
| Atomic config writes with .bak rotation | enforced | TriggerConfigStore |
| Schema validation on every load | enforced | TriggerConfigMigrator |
| Auto-shutdown llama-server after 10 min idle + screen off | on | LlamaServerService |

## User-visible warnings

The IME's suggestion strip shows a small badge when:

- A trigger was redirected by the killSwitch.
- A redaction occurred in the outgoing payload.
- A plugin failed on this boot.
- A daily token cap is at >80%.
- The local llama-server is unreachable.

## What we do NOT guard against

- Keystroke logging by other apps (Android sandboxing handles that).
- A malicious cloud provider returning prompt-injected output. Mitigation:
  outputs from cloud are always treated as text, never executed.
- The user pasting their own secrets into a prompt and bypassing the redact
  threshold. The PII regex catches common shapes; nothing catches everything.
