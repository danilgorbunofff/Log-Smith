# Day 8 — Filters and navigation

> **Status: ✅ DONE (commit `6e1ac70`, pushed; 151 tests green).**
> Charter §7 "Day 8"; references R11, R12, §5.5, §5.6 group F.

## Goal
Filter as a *view*, never a mutation; navigate stack frames and error records like `.log` does.

## Instructions (as executed)
1. **Filter bar** (`LogSmithFilterBar`) pinned at the top of the editor: one checkbox per
   detected level, a free-text field with 400 ms debounce, and Reset. State lives per-editor
   (`FilterState`), so two editors of one file can keep different views.
2. **Folding, never editing** (`FilterFoldPlan`): non-matching record lines collapse into
   `… N hidden` folds; continuation lines (stack frames) follow the record they belong to, so
   the frames of a *visible* record are never cut away. A hidden run is emitted only when it
   has ≥ 1 line.
3. **Classification once per snapshot** (`LogSmithLineFacts` + `LogSmithFilterService`): one
   byte per line on a background thread (64 MB cap), cached on the `VirtualFile`
   (`FACTS_KEY`), invalidated by any edit (stamp check). Folds are applied only on the EDT,
   never inside a write action — a document change schedules a re-filter via `invokeLater`.
4. **R12 — next/prev error:** `F2` / `Shift+F2` (`LogSmithGotoErrorAction`), wrapping around
   the file; a jump moves the caret to the record's start, scrolls it to centre, and expands
   any fold covering it.
5. **R11 — Ctrl+click stack frames** (`LogSmithStackFrameLink`): `path/file.kt:42`,
   `at org.example.Thing.java:42`, Python `File "src/app.py", line 10` →
   `OpenFileDescriptor.navigate(true)`. Resolution walks relative to the log's directory
   (build-tree layout) up to 12 ancestors, then falls back to bare-name lookup
   (`FilenameIndex`, ignoring `/build/`, `/out/`, `/generated/`). Holding Ctrl underlines the
   frame; without Ctrl nothing is interactive, so selection/copying stays untouched.
6. Status line notes: `filter hides N of M lines`; a failure shows
   `filter could not be applied: <reason>`.

## Verification
- Pure tests (`PureFilterTest`, 17): level/text matching, fold planning, `ErrorNavigator`
  wrap/edge cases.
- Platform tests (`FilterServiceTest`, 3): fold planning, facts caching, cache invalidation
  on edit.
- Platform tests (`SessionFilterTest`, 12): folds applied/cleared through the session, notes,
  re-filtering on edit, the F2 walk (forward/backward/wrap, also while folds are active),
  relative + bare-name frame resolution, span parsing.
- `.\gradlew.bat test` — 151 green.
