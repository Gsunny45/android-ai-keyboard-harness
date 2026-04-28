# Onboarding (Day 1 → Day 7)

## Day 1 — Get it running

1. **Phone prep**
   - Install Termux (F-Droid). Open. Run: `pkg install jq curl`.
   - Build/install `llama-server` from llama.cpp (arm64-v8a).
   - Place `gemma-3n-E2B-it-UD-IQ2_M.gguf` in `$HOME/models/`.

2. **Push the skeleton**
   - On desktop: `scripts/validate_configs.sh` — must exit 0.
   - On desktop: `scripts/adb_push_config.sh` (transfer copy only).
   - On phone: launch FlorisBoard → Settings → AI → "Reload config".

3. **Provision keys**
   - Settings → AI → Providers → tap a provider → paste key → Save.
   - Repeat for each provider you have. Local works without keys.

4. **Smoke test**
   - In any text field, type `/fix taht` — should rewrite to "that".
   - In Obsidian, type `/doc explain X` — should produce vault-aware output.

## Day 2 — Make it yours

- Edit `configs/personas.json`. Add a persona that matches your voice.
- Add one trigger to `configs/triggers.json` that you use daily.
- Re-push, reload.

## Day 3 — Wire voice

- Install whisper-to-input (sibling app).
- Long-press the mic key, speak `slash formal write a thank you note`.
- Confirm it rewrites. If the spoken→canonical mapping misses, edit
  `spoken-trigger-table` (per docs/POWER_USER.md).

## Day 4 — Build a skill chain

- In `configs/skills.json`, define a 2-step chain (e.g.
  `outline_then_expand`).
- Add a `/expand` trigger that calls `pipeline: skill, skill: outline_then_expand`.
- Re-push. Try it on a half-formed thought.

## Day 5 — Ad-hoc chains

- Type `/chain atomic_note > tone_align`.
- That runs them serially without you defining a skill upfront.

## Day 6 — Capture and harden

- Settings → AI → Diagnostics → enable "Capture runs (last 50)".
- After a few real uses, browse `runs/`. Anything bad → save as eval
  fixture in `evals/golden/`.

## Day 7 — Plugins

- Copy `plugins/example_resolver.kts.tmpl` → `00_my_resolver.kts`.
- Add one resolver for something you reference often (project name, repo
  URL, weather, calendar next event).
- Reload. Use it in a trigger via `{{mycorp.X}}`.

## When something breaks

- Strip badge red? → Settings → AI → Diagnostics → live event log.
- Local server unreachable? → Termux: `~/cte/scripts/start_llama.sh`.
- Bad config blocking boot? → Settings → AI → "Restore previous config".
- Total brick? → ADB push the skeleton again; it ships defaults.
