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
  without a text editor (binary `a.out`, files too large for the text editor) are left alone.
- **Day 3–4 done: detection engine.** 12 built-in formats plus a plain-timestamp fallback:
  - Logback / Log4j 2, which also covers Log4j 1 ISO-date layouts
  - java.util.logging (JDK and Tomcat JULI)
  - Python logging
  - Go log / slog
  - structured JSON lines (pino and bunyan included)
  - nginx/Apache access
  - nginx error
  - Apache error
  - PHP Monolog
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
  - `LogSmith could not read this file (<reason>) — showing plain text`

  The last two get a warning icon. Closing the editor cancels the scan.
- **Day 5–6 done: highlighting that never makes things worse.** A line-oriented lexer colours
  timestamps, threads and the level ramp (ERROR red, WARN amber, DEBUG dim). Logger and message
  keep the editor's default look. Unrecognised lines and unmatched files keep the platform's
  rendering (§5.4). Typing re-lexes only the edited line, not the whole document. Right-click
  in a log editor and choose *Disable LogSmith highlighting for this file* to turn colouring
  off without a restart; every open editor of that file follows the switch. Until lazy,
  visible-range highlighting lands (Day 7), files over 5 MB are not coloured, and the status
  line says so. Measured: a full lex costs about 19 ms/MB on the EDT (5 MB ≈ 100 ms,
  20 MB ≈ 380 ms on Apple Silicon).
- **Compatibility:** `verifyPlugin` reports *Compatible* for IC-252, IU-253, IU-261, IU-262
  and the IU-263 EAP. The only finding is the deprecated `createTextAttributesKey` noted below.
- **Tests:** 83, all green. They include platform tests (`BasePlatformTestCase`) for:
  - the real attach path
  - R4: the file stays writable, and can be typed into and saved, after LogSmith attaches
  - the toggle action
  - cancellation on close
  - incremental lexing

  Pure tests cover every sniffer, the scorer, the scanner (BOM, UTF-16, CRLF, caps), the status
  wording, and the §7.2 fixture files. Known deviation: `createTextAttributesKey(key, attrs)` is
  deprecated but kept until the Day-10 settings page replaces it.

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
- `./gradlew generateTestData` regenerates the `testdata/` fixtures, including the 400 MB
  `big.log`. Pass `-PbigLogLines=0` to skip `big.log`.

## Repo layout

- `docs/charter.md`: the project charter (moved from this README per charter §11.3)
- `docs/day0-gate.md`: Day-0 verdict with live evidence
- `testdata/`: the §7.2 log fixtures (CRLF, kept byte-exact by `.gitattributes`). `big.log`
  is gitignored.
- `src/main/kotlin/`: plugin sources:
  - `sniff/`: formats, scorer, scanner
  - `highlight/`: lexer
  - top level: editor attach, status line, detection service
