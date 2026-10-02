# Day 5–6 — Highlighting that never makes things worse

> **Status: ✅ DONE.** Charter §7 "Day 5–6"; references R4, R6, §5.4.

## Goal
A line-oriented lexer colours the platform's own editor, and it can never damage the file or
an unmatched file's rendering.

## Instructions (as executed)
1. Custom `EditorHighlighter` over the **existing document** — timestamps, threads, levels,
   loggers and messages get distinct attributes.
2. **Level colour ramp:** ERROR red, WARN amber, INFO default, DEBUG dim
   (`LogSmithTokenTypes` + `LogSmithTokenColorProvider`).
3. **R4:** no file-system mutation of any kind — highlighting is read-only markup. The
   writability regression test proves the file can still be typed into and saved after
   LogSmith attaches.
4. **R6:** if detection failed, **install no highlighter at all** — the platform renders —
   plus a status-bar warning. Never grey out a file that was fine before.
5. Typing re-lexes only the edited line, not the whole document.

## Verification
- `.\gradlew.bat test`:
  - R4: file stays writable, editable, savable after attach.
  - R6: unmatched file keeps the platform's `LexerEditorHighlighter`, not LogSmith's.
  - Token-by-token equivalence check of the lazy highlighter against the platform's own.
- Manual: open → colour → edit → save → clear; the file is untouched by the plugin
  (`git status` on a checked-out `.log` copy must stay clean).
