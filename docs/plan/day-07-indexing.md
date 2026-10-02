# Day 7 — Indexing and performance

> **Status: ✅ DONE (commit `7c8a82b`).** Charter §7 "Day 7"; references R5, §8.1 CI perf job.

## Goal
Remove the size cap; make paint cheap and memory bounded.

## Instructions (as executed)
1. **`LineOffsetIndex`** — streams the file once in 4 MB chunks into a growable `long[]` of
   line starts (16 bytes/line, capped at 16 M lines). No text retained, so memory does not
   scale with file size. Background thread, progress reported, cancellable on tab close
   (`LogSmithLineIndexService`).
2. **`LogSmithLazyHighlighter`** — lexes only **windows**: ≤ 256 lines / 512 KB, whichever
   first, built on demand for the visible range, 12-window LRU cache. Windows lexed from a
   flat copy of the range (~5× faster than lexing the document's rope directly).
3. A line over 64 KB is never segmented — a pasted minified blob cannot hang a paint.
4. Every window is validated for contiguous, complete token coverage; on any doubt it
   degrades to plain text rather than corrupting the paint.
5. **R5 measured numbers in the README** on a real 500 MB file
   (`generateTestData -PbigLogLines=3700000`):

   | Measurement | Budget | Measured |
   |---|---|---|
   | Index a 500 MB log | — | 3.8–4.3 s, 117–132 MB/s |
   | Line index memory | bounded by index, not file | 67 MB (13 % of 500 MB) |
   | Time to first paint, 21 MB log | < 1 s | 178–266 ms |
   | Window build while scrolling (105 jumps) | < 50 ms | median 6 ms, p90 11 ms, p99 22 ms |

6. `-Plogsmith.perf=true` gates the two perf tests; CI runs them weekly with the ~500 MB
   fixture (§8.1).

## Verification
- `.\gradlew.bat test -Plogsmith.perf=true --tests '*PerfTest'`
- `.\gradlew.bat test` green (117 at this phase; 151 after Day 8).
- The measured table lives in the README's Day 7 section.
