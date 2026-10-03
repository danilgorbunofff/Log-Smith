# LogSmith

A zero-config log-file viewer for JetBrains IDEs. Open any `.log` / `.out` file and the
status line under the editor tells you what format you are looking at, and how much of the
file it explains. No settings, no wizard, no copy-paste.

This project is built to a written charter: every feature, deadline, and kill-criterion is
specified up front in [`docs/charter.md`](docs/charter.md) (§-references in `docs/` point there).

## Status

- **Day 0 gate: GO-NARROWED CONFIRMED.** The market sweep, competitor verification, and a GUI
  death-test pass against LogLens are recorded in [`docs/day0-gate.md`](docs/day0-gate.md).
- **Day 1–2 done: first installable build.** LogSmith *attaches* to the platform's own text
  editor for `.log` / `.out` files. The editor is not wrapped or replaced, and the status line
  is added underneath with `FileEditorManager.addBottomComponent`. Files the platform opens
  without a text editor (binary `a.out`, files too large for the text editor) are left alone —
  but a log over the IDE's text-editor limit (`idea.max.content.load.filesize`, 20 MB by
  default) gets a one-line notice saying LogSmith is off for it and how to raise the limit,
  instead of LogSmith silently not being there.
- **Day 3–4 done: detection engine.** 12 built-in formats plus a plain-timestamp fallback:
  - Logback / Log4j 2, which also covers Log4j 1 ISO-date layouts and Spring Boot 3's
    zoned timestamps (`2026-10-01T09:14:02.101+02:00  INFO …`)
  - java.util.logging (JDK and Tomcat JULI)
  - Python logging
  - Go log / slog
  - structured JSON lines with a level and a message key (pino, bunyan, winston's default
    format, logstash-logback-encoder's `@timestamp` lines, ECS `log.level`)
  - nginx/Apache access, from IPv4, IPv6 or host-name clients
  - nginx error
  - Apache error
  - PHP Monolog, including Laravel's `[stacktrace]` / `#N file(line)` exception traces and
    Monolog's own ISO timestamps
  - Django / gunicorn
  - .NET Microsoft.Extensions.Logging
  - syslog

  Stack traces and other continuation lines count toward a format, but a format must match at
  least one real record line to be claimed. The first 200 lines are scanned in the background
  for a fast answer. If the file is longer, a second pass refines it over up to 2M lines /
  256 MB. The decoder honours the file's charset and UTF-8/UTF-16 byte-order marks, and lines
  are capped at 64K characters to bound memory. Every outcome is shown:
  - `Format: Logback / Log4j 2 — matched 1,204 / 1,208 lines (99.7%)`
  - `Format: no format matched 25 lines — showing plain text (closest: Logback / Log4j 2, 12.0%)`
  - `Format: no format matched 41 lines — showing ANSI colours only` (an unclaimed file that
    carries colour codes: see Day 9)
  - `LogSmith could not read this file (<reason>) — showing plain text`

  The last two get a warning icon. Closing the editor cancels the scan.
- **Day 5–6 done: highlighting that never makes things worse.** A line-oriented lexer colours
  timestamps, threads and the level ramp (ERROR red, WARN amber, DEBUG dim). Everything else
  keeps exactly the highlighting the IDE gives the file without LogSmith: the platform's own
  highlighter stays underneath, so for `.log` the bundled TextMate log grammar still colours
  strings, numbers, URLs and exception names inside messages — LogSmith adds colour, it never
  removes any (§5.4). Unmatched files keep the platform's highlighter outright. Typing re-lexes only the edited line, not the whole document. Right-click
  in a log editor and choose *Disable LogSmith highlighting for this file* to turn colouring
  off without a restart; every open editor of that file follows the switch.
- **Day 7 done: indexing and performance (§5.3 R5).** The 5 MB size cap is gone, and LogSmith's
  own lexer never lexes a whole file. Two pieces replace the old whole-document highlighter:
  - `LineOffsetIndex` keeps only a growable `long[]` of line starts (16 bytes per line, capped at
    16M lines) — no text is retained. In the plugin it scans the editor's document snapshot in
    4M-character chunks on a background thread, so its offsets are the document's own (line
    separators normalized, BOM dropped) and the live tail can extend it exactly; it can also
    stream a file from disk, which is what the 500 MB measurement below exercises.
  - `LogSmithLazyHighlighter` lexes **windows**: at most 256 lines or 512 KB, whichever comes
    first, built on demand for what is on screen and cached (12 windows, LRU). Windows tile
    fixed 256-line blocks, so scrolling within a block is served from the cache. Windows are
    lexed from a flat copy of the range, which measured ~5× faster than lexing the document's
    rope directly. A line over 64 KB is never segmented, wherever it sits, so a pasted minified
    blob cannot hang a paint. Every window is validated for contiguous, complete token coverage
    and degrades to plain text rather than corrupting the paint.

  **What the IDE itself opens.** A stock IDE loads a file into a text editor only up to
  `idea.max.content.load.filesize` (20 MB by default); a bigger `.log` opens without a text
  editor, and LogSmith then shows the notice described under Day 1–2. The 500 MB rows below
  measure the index component on its own; in the IDE they apply only after raising that limit.

  Measured on this machine with `./gradlew test -Plogsmith.perf=true --tests '*PerfTest'`
  against the CI fixture (`generateTestData -PbigLogLines=3700000`, 500 MB):

  | Measurement | Budget (§5.3 R5) | Measured |
  |---|---|---|
  | Index a 500 MB log (5,087,501 lines) | — | 3.8–4.3 s, 117–132 MB/s |
  | Line index memory for that file | bounded by the index, not the file | 67 MB (13 % of 500 MB) |
  | Time to first paint, 21 MB log (198,543 lines) | < 1 s | 178–266 ms |
  | Window build while scrolling, 105 jumps | < 50 ms | median 6 ms, p90 11 ms, p99 22 ms, worst 32 ms |
  | Sniffer calls for the first screen | ≤ 2 windows | 256 |

  Re-measured on 2026-10-03 (one run, macOS) after windows became block-aligned: first paint
  47 ms, scroll median 1.3 ms and worst 1.4 ms over 105 jumps.

  Ranges are the spread of four consecutive runs, not a best case. The scroll budget is
  asserted on the worst of 105 jumps after a warm-up pass, and no jump in any run exceeded
  50 ms (the worst observed across runs was 48 ms); the first builds of a cold JVM pay class
  loading and JIT — 88–174 ms worst — and the IDE has long since done that by the time a user
  scrolls. `-Plogsmith.perf=true` gates the three perf tests, and CI runs them weekly with the
  ~500 MB fixture (§8.1).
- **Day 8 done: filters and navigation (§5.3 R11/R12).** Filtering never edits the document —
  it folds. A filter bar at the top of the editor offers one checkbox per detected level plus
  a free-text field (400 ms debounce), and a Reset button. Non-matching record lines collapse
  into `… N hidden` folds — each placeholder on a line of its own — whose stack frames stay
  attached to the matching record that owns them. What a record is, and its level, follows the
  claimed format exactly as the highlighter reads it, so a stack frame through `Logger.error(…)`
  or a thread named `[error-reporter]` is never mistaken for an error record; JUL's two-line
  records take the level from their `SEVERE:` line. Two keyboard actions walk the error records:
  `F2` jumps to the next `ERROR` and `Shift+F2` to the previous one, both wrapping around the
  file; a jump lands with the caret centred and any fold covering it expanded. In a LogSmith
  editor the keys are promoted ahead of the IDE's own *Next Highlighted Error*, which shares
  them; everywhere else they keep their usual meaning. A walk with nowhere to go says so. Typing in the document re-filters on the EDT
  (never inside a write action), and the status line reports `filter hides N of M lines`.
  Filter classification runs once per document snapshot on a background thread (64 MB cap),
  is cached on the file, and is invalidated by any edit; while a filtered log only grows, just
  the appended lines are classified, and the folds stay in place until the new plan replaces
  them. Ctrl+click (Cmd+click on macOS) on a stack frame — `path/file.kt:42`,
  `at org.example.Thing.java:42`, Node's `(/app/src/index.js:10:5)`, Go's absolute paths,
  PHP's `#0 /var/www/Kernel.php(42)` or Python's `File "src/app.py", line 10` — opens the
  referenced file at the line: an absolute path on the log's own file system first, then
  relative to the log's directory (build-tree layout), then by bare name across the project,
  preferring the file whose directory matches a JVM frame's package. A frame that cannot be
  found says so in a hint. Holding the modifier underlines the frame, so ordinary selection
  and copying stay untouched.
- **Day 9 done: colour ANSI and follow a growing file (§5.3 R9, §5.4).** Output from
  `docker logs`, `npm install`, `pytest` and anything else that keeps its colours arrives full
  of escape sequences, and LogSmith parses them instead of printing them. A pure `AnsiText`
  parser turns SGR sequences into styled runs — 16-colour, 256-colour and truecolour foreground
  and background, plus bold, dim, italic and underline — and strips everything else (cursor
  movement, OSC titles), so an escape can never appear as `←[32m` garbage. Inside an ANSI span
  the sequence's own colour wins over the level ramp; outside one the ramp is unchanged. A file
  no format explains but which carries colour codes — `docker logs`, `npm install`, `pytest`
  output usually is — still gets its colours: LogSmith installs an ANSI-only layer (colours
  rendered, escapes hidden, everything else exactly as the IDE shows it) and the status line
  says `no format matched … — showing ANSI colours only`. A file with neither gets nothing. Detection runs **ANSI-blind**:
  sequences are stripped before a line is scanned, so an escape cannot disguise a level or flip
  a format.
  A growing file now follows itself. When a program appends to an open log the line count in the
  status line grows, folds keep applying, F2 walks into the appended errors, and the new lines
  are classified with the format already detected — no re-sniff, no re-read of the old lines,
  and the offset index grows by the appended lines only. CRLF files follow exactly like LF ones:
  the index is built from the document, where `\r\n` is already a single `\n`. The tail is **document-anchored**: it
  consumes the text the platform itself loads when the file changes on disk, so there is no
  second reader that could disagree with the editor about encoding or byte offsets, and no
  background thread whose results could arrive out of order. Anything the tail cannot follow
  says so — `live tail unavailable — reopen to refresh`, on top of the existing `line index
  capped` / `file too large` note — instead of quietly showing stale text.

  Measured with `./gradlew test -Plogsmith.perf=true --tests '*LiveTailPerfTest'` against a
  200,000-line / 12 MB document: appending 1,000 lines costs LogSmith **2.8 ms** — the index
  append plus the detection statistics over the new text — against a 50 ms budget. The
  end-to-end window for that append is 1,320 ms, and it is the platform's own document reload
  rather than the tail: with the tail switched off the identical append costs 950 ms, and the
  run-to-run spread on that reload (775–1,320 ms for the same work) is wider than the plugin's
  entire share.

- **Verification pass (2026-10-03).** A review of Days 1–9 reproduced, with failing tests, and
  then fixed: CRLF logs re-detecting and re-indexing forever; unclaimed ANSI captures (the
  Docker/npm/pytest samples) never being coloured; F2 being taken by the IDE's own action;
  filter folds joining the next record onto the placeholder line; format-blind record
  detection in the filter and F2; Spring Boot 3, Laravel, logstash/ECS/winston JSON and IPv6
  access logs reported as no format; LogSmith removing the IDE's TextMate `.log` colours from
  messages; silence on logs too large for a text editor; a highlighter iterator that could not
  walk backwards; a long line mid-window still being segmented; absolute-path stack frames not
  being clickable; and the live-tail tests failing on macOS. Each has a regression test.
- **Compatibility:** `verifyPlugin` reports *Compatible* for IC-252, IU-253, IU-261, IU-262
  and the IU-263 EAP. The only finding is the deprecated `createTextAttributesKey` noted below.
- **Tests:** 273, all green on macOS (CI runs them on Linux). They include platform tests
  (`BasePlatformTestCase`) for:
  - the real attach path
  - R4: the file stays writable, and can be typed into and saved, after LogSmith attaches
  - the toggle action
  - cancellation on close
  - lazy window highlighting, checked token-by-token against the platform's own
    `LexerEditorHighlighter`
  - the line-index service: EDT delivery, the size cap, and disposal
  - the filter service: fold planning, fact caching and invalidation on edit
  - the filter through the session: folds applied and cleared, notes, re-filtering on edit,
    the F2/Shift+F2 error walk, and stack-frame resolution
  - ANSI on the real captured Docker / npm / pytest samples, checked token-by-token against the
    platform's own highlighter colours
  - the live tail: an append growing the index, the colours and the line count; a CRLF file
    indexed once and followed; a split line counted once; an edit pausing the tail with no
    warning icon; F2 reaching an appended `ERROR`; the clean rebuild after an edit; and the
    over-cap degrade message
  - layering over the IDE's real `.log` highlighting (the TextMate grammar the test IDE runs):
    strings and numbers keep the platform colour, levels get LogSmith's
  - an unclaimed ANSI file getting its colours; F2 promotion and its "nothing to go to" hint;
    folds on their own line and kept during a re-plan; JUL levels; incremental facts; the
    highlighter's backward walk, long lines anywhere and block-cached scrolling; absolute, PHP
    and package-disambiguated stack frames

  Pure tests cover every sniffer, real-world default layouts with their stack traces, the
  format-aware filter facts, the scorer, the scanner (BOM, UTF-16, CRLF, caps), the line
  index (chunk-size equivalence, CRLF, caps, cancellation), the status wording, the ANSI parser
  (16/256/truecolour, attributes, stripped non-SGR sequences, malformed input), the tail
  classifier (append, split line, edit, truncation, restart) and the §7.2 fixture files. Known
  deviation: `createTextAttributesKey(key, attrs)` is deprecated but kept until the Day-10
  settings page replaces it.

## Building

Requires JDK 21 (the JetBrains Runtime shipped with any IntelliJ-family IDE works: `jbr/`).

```bash
./gradlew buildPlugin
```

On Windows use `.\gradlew.bat buildPlugin`. The build compiles against IntelliJ IDEA Community
2025.2, which Gradle downloads. To compile against an IDE you already have installed, set it in
`~/.gradle/gradle.properties` (not in the repo):

```properties
logsmith.localIde=C:/Program Files/JetBrains/WebStorm 2025.3.2
```

The installable zip appears at `build/distributions/logsmith-0.1.0.zip`.
Install it in Settings → Plugins → ⚙ → Install Plugin from Disk.

Other tasks:
- `./gradlew test` runs the unit and platform tests.
- `./gradlew runIde` starts a sandbox IDE with the plugin installed.
- `./gradlew verifyPlugin` checks binary compatibility against the recommended IDEs and the
  latest IntelliJ IDEA EAP. CI runs it weekly (§8.1).
- `./gradlew generateTestData` regenerates the `testdata/` fixtures. `big.log` is ~135 bytes a
  record, so the default `-PbigLogLines=4200000` writes ~570 MB; CI uses
  `-PbigLogLines=3700000` (~500 MB) for the perf job. Pass `-PbigLogLines=0` to skip `big.log`.
- `./gradlew test -Plogsmith.perf=true --tests '*PerfTest'` runs the three gated perf tests
  against whatever `big.log` is on disk. CI runs them weekly, on the schedule, not on push.

## Repo layout

- `docs/charter.md`: the project charter (moved from this README per charter §11.3)
- `docs/day0-gate.md`: Day-0 verdict with live evidence
- `testdata/`: the §7.2 log fixtures (CRLF, kept byte-exact by `.gitattributes`). `big.log`
  is gitignored.
- `src/main/kotlin/`: plugin sources:
  - `sniff/`: formats, scorer, scanner
  - `ansi/`: the pure SGR parser
  - `index/`: the streaming line-offset index and the service that runs it
  - `highlight/`: lexer, segmenter, lazy visible-range highlighter
  - `live/`: the tail growth classifier that follows appended lines
  - `filter/`: the fold plan, the level/text matcher, the error navigator, stack frames
  - top level: editor attach, status line, detection service, per-editor session
