# LogSmith

A zero-config log-file viewer for JetBrains IDEs. Open any `.log` / `.out` file and the
status line tells you what format you are looking at — no settings, no wizard, no copy-paste.

This project is built to a written charter: every feature, deadline, and kill-criterion is
specified up front in [`docs/charter.md`](docs/charter.md) (§-references in `docs/` point there).

## Status

- **Day 0 gate: GO-NARROWED CONFIRMED** — market sweep, competitor verification, and a GUI
  death-test pass against LogLens are recorded in [`docs/day0-gate.md`](docs/day0-gate.md).
- **Day 1–2 done**: first installable build. The plugin attaches to the platform text
  editor for `.log` / `.out` files and shows `Format: <unknown>` → real format after the first
  200 lines are scanned.
- **Day 3–4 done: detection engine.** 12 built-in format sniffers plus a plain-timestamp
  fallback (Logback/Log4j 2, java.util.logging, Python logging, Go log/slog, structured JSON,
  nginx/Apache access + error, PHP Monolog, Django/gunicorn, .NET, syslog, and more), stack-trace
  and continuation-line handling, and a match-ratio score. Detection scans the first 200 lines
  in the background for a fast first answer, then refines up to 2M lines / 256 MB; the status
  line reports e.g. `Format: Logback / Log4j 2 — matched 1,204 / 1,208 lines (99.7%)`. 24 unit
  tests cover every sniffer plus the scorer.

## Building

Requires JDK 21 (the JetBrains Runtime shipped with any IntelliJ-family IDE works: `jbr/`).

```powershell
.\gradlew.bat buildPlugin
```

The installable zip appears at `build/distributions/logsmith-0.1.0.zip`.
Install it in Settings → Plugins → ⚙ → Install Plugin from Disk.

## Repo layout

- `docs/charter.md` — the project charter (moved from this README per charter §11.3)
- `docs/day0-gate.md` — Day-0 verdict with live evidence
- `testdata/` — log fixtures for manual verification; `big.log` (400 MB) is gitignored,
  regenerate with `testdata/generate-test-files.ps1`
- `src/main/kotlin/` — plugin sources (sniffers, detection service, editor integration)
