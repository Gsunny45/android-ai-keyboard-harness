# Power-User Trajectory

Three layers of customization. Each one is further from the user but more
powerful. Master them in order.

## L1 — JSON tweaking

What you do in your first week. Edit `configs/*.json` and `profiles/*.json`.

**Reach for L1 when** you want a new trigger, a new persona, a new app
profile, or to tune routing rules.

**Hot-reload:** Settings → AI → Reload config.

**Patterns**

- *Trigger inherits from a built-in by extension:* duplicate the trigger
  block, change the name, tweak `system_template`.
- *App-specific override:* add the trigger inside the appropriate
  `profiles/<package>.triggers.json` `triggers_override` map. Per-app wins.
- *Persona swap mid-session:* type the persona name preceded by space to
  switch (e.g. `professional how do I phrase…`). Voice supported.

## L2 — Skill composition

What you do in your first month. Build chains in `configs/skills.json`.

**Reach for L2 when** one model call isn't enough — you need draft → fix →
align, or branch-and-pick.

**Patterns**

- *Linear chain:* `chain_next: ["a", "b", "c"]` — output of each becomes
  `chain.previous` of the next.
- *Conditional branch:* set `on_error: "fallback_provider"` so a step
  silently retries on a different model.
- *Ad-hoc chain:* `/chain a > b > c` typed at runtime. Useful for
  experimenting before you commit to a `skills.json` entry.
- *ToT inside a skill:* set `pipeline: tot, branches: 3`. The skill's
  output is the FINAL of the best branch.

## L3 — Plugins (Kotlin Script)

What you do once L1+L2 stop fitting. Drop `.kts` files in `plugins/`.

**Reach for L3 when** you need code, not data. Examples:

- A resolver that reads a SQLite DB the user maintains.
- A pipe that pretty-prints JSON, or sorts a list, or runs a regex.
- A pre-prompt hook that blocks cloud sends matching a corporate policy.
- A post-response hook that strips a known boilerplate phrase the model
  keeps adding.

**Constraints**

- Plugins run in a sandboxed scripting context. No file writes outside
  the app's data directory. No network calls (use a trigger for that).
- A plugin that throws on load is **logged and skipped**, not crashed.
  Check `logs/events.jsonl` for `kind:"plugin"` rows.
- Plugins **cannot** override built-in resolvers. They can only add
  namespaces (e.g. `mycorp.*`).

## Naming conventions for plugins

| Prefix | Use |
|---|---|
| `00_*.kts` | system-level extensions you wrote |
| `10_*.kts` | Obsidian extensions |
| `20_*.kts` | work / corporate extensions |
| `local_*.kts` | not committed (gitignored), throwaway experiments |

## Anti-patterns

- **Don't put logic in `system_template` that L3 should own.** A 600-char
  template is a code smell; that's what skills + plugins are for.
- **Don't bypass the killSwitch in plugins.** If you need to send to cloud
  during a killSwitch, the user wants to know — surface a toast, don't
  silently route.
- **Don't capture runs by default in production.** It's a forensics tool,
  not a feature.
- **Don't redact-disable per trigger** without strong reason. The 256-char
  defaults exist because a big paste is the most common privacy leak.

## When the IME starts feeling magic

You'll know L1 fluency when adding a trigger feels like adding a calendar
entry. L2 fluency when you instinctively reach for chains over prompts.
L3 fluency when you stop thinking "I wish the keyboard could…" because
you can just write it.
