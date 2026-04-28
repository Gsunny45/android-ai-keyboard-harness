# Architecture (Skeleton View)

This is the runtime view of how config files map to the 5-layer engine
described in the project's [[architecture]] note.

## File-to-layer map

| Skeleton path | Layer | Loaded by |
|---|---|---|
| `configs/triggers.json` | L2 Orchestration | TriggerConfigStore |
| `configs/triggers.schema.json` | L2 (validation) | TriggerConfigMigrator |
| `configs/personas.json` | L2 | PersonaMerger |
| `configs/skills.json` | L2/L3 | SkillEngine |
| `configs/routing.json` | L4 | ProviderRouter |
| `configs/keys.example.json` | — (docs only) | n/a; real keys via KeyVault |
| `profiles/*.triggers.json` | L2 | AppProfileManager |
| `plugins/*.kts` | L2/L3 (extension) | PluginLoader |
| `evals/golden/*.json` | L0 (test) | EvalRunner |
| `scripts/*.sh` | L4 (local), CI | shell |
| `logs/events.jsonl` | L2-L4 telemetry | EventLogger |
| `runs/*.jsonl` | L2-L5 forensics | RunCapture (opt-in) |

## Boot sequence

```
1. Read VERSION                              ← migrator's lookup key
2. Load configs/triggers.json                ← validate vs schema
3. Migrate v1→v2 if needed                   ← write .bak first
4. Load configs/{personas,skills,routing}.json
5. Merge appProfiles[*].extends              ← profile JSON files
6. eval plugins/*.kts in lex order           ← failure isolated, logged
7. KeyVault.preload()                        ← decrypt prefs once
8. ProviderRouter.warmHealthChecks()         ← async, non-blocking
9. EventLogger.open(logs/events.jsonl)
10. CTE ready
```

## Hot-reload

Settings → AI → "Reload config" reruns steps 2–9 atomically; the IME holds
old state until step 9 completes, then swaps. A failed reload keeps the
prior config.

## Atomicity

`TriggerConfigStore` writes to `*.tmp` then `rename()` over the live file.
Companion `*.bak` slot keeps the previous version recoverable from Settings
→ AI → Diagnostics → "Restore previous config".
