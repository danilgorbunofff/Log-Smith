# Day 9 — ANSI + live tail

> **Status: ✅ DONE.** Charter §7 "Day 9"; references R9, §5.3, §5.4, §4.8.

## Goal
ANSI escape sequences in log output render as colour (never as `←[32m` garbage), and a growing
file follows itself live. Deliverable: a running Docker container's log, live, in colour.

## Instructions (as executed)
1. **Pure ANSI parser first** — `src/main/kotlin/com/danilgorbunofff/logsmith/ansi/AnsiText.kt`:
   - Parse SGR sequences `\u001b[<params>m` into styled runs over a line's text.
   - Support: 16-colour base (`30–37` fg / `40–47` bg, bright `90–97` / `100–107`),
     256-colour (`38;5;n`, `48;5;n`), truecolour (`38;2;r;g;b`, `48;2;r;g;b`).
   - Support attributes: reset `0`, bold `1`, dim `2`, italic `3`, underline `4`.
   - Strip non-SGR escapes (cursor movement, OSC titles) — they never render as text.
   - Output: an ordered list of `(startOffset, endOffset, attributes)` runs per line.
   - No IDE imports — this class must be pure-testable.
2. **Map to highlighting** — integrate runs into `LogSmithLazyHighlighter`: within an ANSI
   span, the ANSI attributes **override** the level ramp; outside spans the ramp applies
   unchanged. §5.4 rule intact: if detection failed, no highlighter is installed at all, so
   ANSI never colours a file the plugin did not claim.
3. **Tests against real captured output** — save samples from a real Docker `docker logs`,
   a real `npm install`, and a real `pytest -v` run (they exercise 16/256/truecolour and
   bold/dim). Pure parser tests first, then a platform test asserting the highlighter returns
   the right colours on the sample documents.
4. **Live tail — append without re-parsing:**
   - Track the file's length; on a VFS refresh, consume only the text the platform appended.
   - Classify new lines with the **same** format — no re-sniff. If the appended tail fails
     classification, mark the tail unparsed rather than re-sniffing the whole file.
   - `LogSmithLineIndexService` grows its offset array append-only (O(new lines), no
     re-index). Highlight windows covering appended lines re-lex; the existing document
     listener already re-folds filtered views.
   - **As landed (deviation, user-approved):** the tail reads no bytes at all. A probe showed
     that a disk append plus `VirtualFile.refresh` reloads the document itself and delivers a
     pure append `DocumentEvent` char-aligned with the offset index, so the design is
     *document-anchored*: no charset decoding, no second view of the file, no background
     thread — the index grows synchronously in the write action that the reload already holds.
     A byte-level reader would have bought nothing except a chance to disagree with the editor.
5. **Degrade honestly:** files the tail cannot follow show
   `live tail unavailable — reopen to refresh` in the strip instead of failing silently, on top
   of the existing `line index capped` / `file too large` line note. It is stated as a limit,
   not as a warning — a stated limit is not a warning — matching the §4.8 philosophy.
6. **Perf budget:** appending 1,000 lines must not block the EDT > 50 ms on this plugin's
   account; index append is O(new lines). Measured against a 200,000-line / 12 MB document: the
   plugin's own share of a 1,000-line append — the index append plus detection statistics over
   the new text — is **2.8 ms**, far inside budget. The end-to-end window (1,320 ms) is dominated
   by the platform reloading a 12 MB document into the editor, which the tail neither causes nor
   can avoid: with the tail shut off the identical append costs 950 ms, and the run-to-run spread
   on that reload is wider than the plugin's whole share. Recorded, not hidden.

## Acceptance criteria
- [x] ANSI samples from Docker/npm/pytest render in colour; 256-colour and truecolour both work.
- [x] A growing log appends live: the status line's line count grows, colours continue, F2
      still walks the new errors, folds still apply.
- [x] Over-cap files get the explicit degrade message.
- [x] `.\gradlew.bat test` green with the new `AnsiTextTest` + `LiveTailTest`.

## Verification
- Pure tests (`AnsiTextTest`, 31): SGR parsing, 16/256/truecolour, bold/dim/italic/underline,
  resets, non-SGR sequences stripped, malformed input never throws.
- Pure tests (`AnsiFixtureTest`, 10): real captured `docker logs` / `npm install` / `pytest -v`
  samples, byte-exact.
- Detection and highlighting: `LogScannerTest` (16, an escape sequence cannot disguise a
  level because ANSI is stripped before scanning), `LineSegmenterTest` (23, spans mapped onto
  segments), platform `LazyHighlighterTest` (16, ANSI overrides the level ramp inside a span
  and leaves the ramp alone outside it).
- Pure tests (`live.LiveTailTest`, 19): pure-append, split-line, mid-file edit, truncation and
  restart classification; `FormatScorer.statsFor` merges base + tail without double counting.
- Platform tests (`platform.LiveTailTest`, 6): an append grows the line count and the colours; a
  split line is counted once; an edit pauses the tail with no warning icon; an appended ERROR is
  reachable by F2; a clean append after an edit rebuilds and reports 20 matched / 21 scanned /
  22 lines; an over-cap file states the tail limit instead of stalling silently.
- Pure tests (`StatusTextTest`, 14): the tail notes for followed, capped, too-large and failed
  files, and the note wording.
- `.\gradlew.bat test buildPlugin verifyPluginProjectConfiguration` — 21 suites, 237 tests,
  0 failures, 0 errors.
- `.\gradlew.bat test "-Plogsmith.perf=true"` — `LiveTailPerfTest` asserts the 1,000-line
  append costs the plugin under 50 ms and reports the platform's document-reload cost.

## Commands
```powershell
.\gradlew.bat compileKotlin compileTestKotlin --console=plain -q
.\gradlew.bat test --console=plain 2>&1 | Select-String -Pattern "^BUILD |FAILED|tests completed"
.\gradlew.bat test --tests '*perf.LiveTailPerfTest' "-Plogsmith.perf=true" --console=plain
# manual live check: copy a log, open the copy in runIde, then let a loop append to it.
Copy-Item testdata\docker.log $env:TEMP\growing.log
.\gradlew.bat runIde
# in a second shell:
1..200 | ForEach-Object {
  Start-Sleep -Milliseconds 500
  Add-Content $env:TEMP\growing.log "2026-10-01 09:00:00.000 [main] INFO  c.e.App - appended $_"
}
```
