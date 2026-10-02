# Day 3–4 — Detection engine and the match ratio

> **Status: ✅ DONE.** Charter §7 "Day 3–4"; references R1, §7.2.

## Goal
Sniff the log format in the background, score it honestly, and print the ratio in the status
line. The test suite is the product's quality.

## Instructions (as executed)
1. **Sniffers as small predicates:** each format = compiled regex + priority + display name.
   All are independently testable and ordered by priority.
2. Scan the **first 200 lines** on a background thread (`LogSmithDetectionService`); score
   every candidate by match ratio; pick the winner; then a second background pass refines the
   ratio over up to 2 M lines / 256 MB.
3. **Twelve built-in formats + fallback:** Logback / Log4j 2 (covers Log4j 1 ISO layouts),
   java.util.logging (JDK + Tomcat JULI), Python logging, Go log/slog, JSON lines
   (pino/bunyan), nginx access + error, Apache access + error, PHP Monolog, Django/gunicorn,
   .NET `Microsoft.Extensions.Logging`, syslog, plus plain `YYYY-MM-DD HH:MM:SS`.
4. Stack traces and continuations count toward a format, but a format must match at least one
   real record line to be claimed.
5. Charset handling honours the file's encoding plus UTF-8/UTF-16 BOMs; lines capped at 64 K
   characters to bound memory.
6. **R1:** status line shows `Format: Logback / Log4j 2 — matched 1,204 / 1,208 lines (99.7%)`.
   No-match files show `Format: no format matched 25 lines — showing plain text (closest:
   …, 12.0%)` with a warning icon. Read errors show `LogSmith could not read this file
   (<reason>) — showing plain text`.
7. Closing the editor cancels the scan (`Job.cancelled`, disposal-bounded).

## Verification
- One `BasePlatformTestCase` + `myFixture.configureByText` test per format with **real
  captured samples**, including truncated trailing lines, multi-line stack traces and
  `\r\n` endings.
- Deliverable: the ratio displays correctly on all three §7.2 fixture files.
- `.\gradlew.bat test` green.
