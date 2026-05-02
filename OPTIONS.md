# Integration Options: Obsidian Plugin Ecosystem ↔ FlorisBoard CTE Keyboard

_Generated 2026-04-29. Phone-only architecture — no PC bridge._

---

## Constraints

The keyboard runs entirely on the phone. It cannot call Obsidian plugins directly (no REST API, no plugin IPC). Integration means **convention alignment** — the keyboard reads/writes files in ways Obsidian plugins understand, or its behavior adapts based on app context.

Three relationship types:
- **A → B**: Keyboard output matches plugin conventions
- **B → A**: Keyboard reads vault content to inform AI behavior
- **C**: Shared data model (both read same frontmatter/files)

---

## Tier 1: Directly Integratable

These require no external dependencies. The keyboard controls its own output format and can detect app context.

### Templater — Medium Integration

**Relationship:** A → B (keyboard writes Templater-compatible syntax)

The keyboard's AI output can emit valid Templater tokens (`<% tp.date.now() %>`, `{{title}}`, `{{date}}`) into note bodies. When the user later opens the note in Obsidian on PC or phone, Templater processes them.

**What it needs:**
- AI system prompts include Templater token reference
- `StripRenderer` / `InlineRenderer` trained not to escape `<% %>` delimiters
- `/draft` trigger prompt includes `"Use Templater tokens for dates and titles"`

**Limitations:**
- Templater processes tokens on open/create — keyboard can't trigger execution
- Tokens are one-shot: once written, they're live or dead depending on Obsidian side

### Dataview — Medium Integration

**Relationship:** C (shared frontmatter schema)

Both the keyboard and Dataview read/write YAML frontmatter. If the AI understands the vault's frontmatter conventions (`tags:`, `status:`, `aliases:`, `type:`), it can write notes that Dataview queries index properly.

**What it needs:**
- AI prompt includes vault frontmatter schema
- `ObsidianBridge.readFrontmatter()` informs AI of existing note structure before editing
- `/draft` trigger reads `Frontmatter Fields.md` conventions

### Linter — Medium Integration

**Relationship:** A → B (keyboard output respects Linter rules)

The keyboard produces markdown. If its output matches Linter conventions (heading order, trailing newlines, frontmatter format, list spacing), observations stay consistent without manual cleanup.

**What it needs:**
- AI system prompt includes Linter formatting rules
- Particularly: heading hierarchy, YAML formatting, bullet spacing

### Tasks — Low-Medium Integration

**Relationship:** A → B (keyboard emits valid task lines)

Keyboard can parse and emit Obsidian Tasks-compatible syntax:
```markdown
- [ ] Fix login bug 📅 2026-05-02 ⏫ #bug
```

Voice input or text trigger: `/task fix login bug high` → emits formatted task with priority.

**What it needs:**
- New trigger definition in `triggers.json` (e.g., `/task`, `/todo`)
- AI prompt with Tasks plugin syntax reference
- Optionally: read existing tasks from current file for context

### Kanban — Low-Medium Integration

**Relationship:** A → B (keyboard emits Kanban-compatible markdown)

Same pattern as Tasks — keyboard inserts valid board entries. Raw markdown that Obsidian Kanban plugin renders.

**What it needs:**
- `/kanban add "task"` trigger
- Knowledge of board column format (`## Column Name` → `- [ ] item`)
- No phone-side `kanban-tools.py` (the Python CLI is PC-only)

---

## Tier 2: Possible via SAF File Access

`ObsidianBridge.kt` uses Storage Access Framework to read vault files on-device. Already coded. Requires user to pick vault folder once.

### Periodic Notes / Calendar — Medium Integration

**Relationship:** B → A (keyboard reads vault date structure)

Keyboard checks conventions:
- Does `daily/2026-05-02.md` exist?
- What's in the frontmatter? (mood, focus, todos)
- Injects as context: `"Today's note focus is {{frontmatter.focus}}"`

**What it needs:**
- SAF vault access configured (one-time user action)
- AI prompt references daily note context
- `ObsidianBridge.readFrontmatter("daily/2026-05-02.md")` wired into CteEngine context

### QuickAdd — Low Integration

**Relationship:** A → B (keyboard writes to inbox that QuickAdd reads)

QuickAdd captures to files like `00 - Inbox/capture.md`. The keyboard's voice `/capture` trigger can write to the same file.

**What it needs:**
- SAF vault access
- `/capture` trigger writes to `Inbox/YYYY-MM-DD-HHmm.md` (QuickAdd convention)
- Metadata includes source app, timestamp

---

## Tier 3: Interesting but PC-Only

These are mature automation tools in the vault that require the PC environment.

| Plugin/Tool | Why PC-Only | Relationship to Keyboard |
|---|---|---|
| **REST API** | Requires Obsidian running on PC with plugin active. Vault has `rest-api.ps1` + configured Bearer token. | No phone integration path. Keep as PC-side automation for vault management. |
| **Obsidian Git** | Git-based sync. CatCloud already handles phone vault sync. | None. |
| **kanban-tools.py** | Python + REST API. 809-line CLI with full CRUD + move/archive. PC-only. | Would need Kotlin rewrite as phone-side skill. Large effort. |
| **rest-api.ps1** | PowerShell + REST API. inbox/search/read/list ops. PC-only. | Conceptually interesting — the keyboard already has OkHttp (same HTTP patterns), but targeting phone-vault SAF, not PC API. |

---

## Tier 4: No Integration Path

These plugins are pure Obsidian UI/internal features with no shared data surface.

| Plugin | Why No Integration |
|---|---|
| **BRAT** | Beta plugin installer. External to keyboard. |
| **ChatGPT MD / Copilot / Gemini Scribe / Local GPT / Smart Connections** | AI assistants inside Obsidian. The keyboard IS an AI assistant everywhere. Overlapping purpose, not complementary. |
| **Excalidraw / Advanced Canvas** | Visual drawing tools. Keyboard produces text. |
| **Hover Editor / Commander / Homepage** | Pure Obsidian UI chrome. No text/file surface to share. |
| **Style Settings / Minimal Theme** | CSS customization. No keyboard relevance. |
| **Omnisearch** | Full-text search inside Obsidian. Keyboard has no UI to display results meaningfully. |
| **Advanced Tables / Outliner / Natural Language Dates** | These are editing aids within Obsidian. The keyboard's AI already handles formatting. |
| **Spaced Repetition / Readwise / Importer / Zotero / Pandoc / PDF++** | Academic/reference workflows. No integration surface with a text-entry keyboard. |

---

## Summary: What Actually Integrates

```
Strong integration surface:
  Templater     ──  keyboard writes tokens, Obsidian processes them
  Dataview      ──  shared frontmatter conventions
  Linter        ──  keyboard output respects formatting rules
  Tasks         ──  keyboard emits valid task syntax
  Kanban        ──  keyboard emits valid board entries

Moderate (with SAF vault access):
  Periodic Notes ──  keyboard reads daily note context
  QuickAdd       ──  keyboard writes to shared inbox
  Calendar       ──  keyboard aware of vault date structure

PC-only (existing vault automation):
  REST API       ──  keep as vault management tool (not keyboard integration)
  kanban-tools   ──  PC-side, would need Kotlin port for phone use
```

The integration pattern is consistent: the keyboard **aligns its output format** with what Obsidian plugins expect to read. No IPC, no plugin calls, no PC bridge. Just shared conventions and vault-aware AI prompts.
