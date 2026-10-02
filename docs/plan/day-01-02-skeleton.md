# Day 1–2 — Skeleton that opens a file and tells you what it did

> **Status: ✅ DONE.** Charter §7 "Day 1–2"; references §4.10, §5.1.

## Goal
LogSmith attaches to the platform's **own** text editor for `.log` / `.out` files and shows a
status line under it from the very first second — even before detection knows anything.

## Instructions (as executed)
1. `git init`, IntelliJ Platform plugin, **IntelliJ Platform Gradle Plugin 2.x** (never the
   deprecated 1.x `gradle-intellij-plugin`).
2. `plugin.xml` declares **only** `com.intellij.modules.platform` as a hard dependency, so the
   plugin loads in every JetBrains IDE, not just IDEA.
3. Register an editor attachment (no `FileEditorProvider` replacement) for `.log` / `.out`:
   listen for file opens via `FileEditorManagerListener`, then
   `FileEditorManager.addBottomComponent(fileEditor, component)` to pin a status strip under
   the platform text editor. Never wrap or replace the text editor itself.
4. Files the platform opens **without** a text editor (binary `a.out`, over-cap files) are
   left completely alone — check `fileEditor.textEditor` before attaching.
5. Status line ships immediately with `Format: <unknown>` and the tooltip
   *"detection runs after the first 200 lines are indexed."* The failure state is visible
   from day one (§5.1).

## Verification
- `.\gradlew.bat test` — platform test for the real attach path.
- Manual: install `build\distributions\logsmith-0.1.0.zip` via
  Settings → Plugins → ⚙ → Install Plugin from Disk, open a `.log`, see the status line.

## Rules that carry forward
- The editor is not wrapped or replaced; the plugin is a guest in the platform's editor.
- Every state — including failure — must render visibly in the strip (§5.1).
