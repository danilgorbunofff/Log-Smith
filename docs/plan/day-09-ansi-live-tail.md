# Day 9 — ANSI + live tail

> **Status: ⬜ NEXT.** Charter §7 "Day 9"; references R9, §5.3, §5.4, §4.8.

## Goal
ANSI escape sequences in log output render as colour (never as `←[32m` garbage), and a growing
file follows itself live. Deliverable: a running Docker container's log, live, in colour.

## Step-by-step instructions
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
   - Track the file's length; on `VirtualFileListener.contentsChanged` (or a VFS refresh),
     read only the bytes after the last known offset and append the new lines.
   - Classify new lines with the **same** format — no re-sniff. If the appended tail fails
     classification, mark the tail unparsed rather than re-sniffing the whole file.
   - `LogSmithLineIndexService` grows its offset array append-only (O(new lines), no
     re-index). Highlight windows covering appended lines re-lex; the existing document
     listener already re-folds filtered views.
   - Runs on a background thread; never block the EDT on file reads.
5. **Degrade honestly:** files above the memory-safe cap (16 M lines / the service caps) show
   `Live tail unavailable: file too large — reopen to refresh` in the strip instead of
   failing silently. The exact limitation is user-visible, like §4.8 asks, but better.
6. **Perf budget:** appending 1,000 lines must not block the EDT > 50 ms; index append is
   O(new lines).

## Acceptance criteria
- [ ] ANSI samples from Docker/npm/pytest render in colour; 256-colour and truecolour both work.
- [ ] A growing log appends live: the status line's line count grows, colours continue, F2
      still walks the new errors, folds still apply.
- [ ] Over-cap files get the explicit degrade message.
- [ ] `.\gradlew.bat test` green with the new `AnsiParserTest` + `LiveTailTest`.

## Commands
```powershell
.\gradlew.bat compileKotlin compileTestKotlin --console=plain -q
.\gradlew.bat test --console=plain 2>&1 | Select-String -Pattern "^BUILD |FAILED|tests completed"
# manual live check:
.\gradlew.bat runIde   # open testdata\growing.log while a job appends to it
```
