# LogSmith day plan — one file per phase

The charter (`docs/charter.md` §7 plan) lays the 14 days out in phases. This folder holds one
instruction file per phase, in the exact grouping the README reports. Done phases record what
was built and how it is verified; upcoming phases carry the step-by-step instructions to
execute them.

| Phase | File | Status |
|---|---|---|
| Day 0 — the gate (2 h, before any code) | [`docs/day0-gate.md`](../day0-gate.md) | ✅ DONE — GO-NARROWED CONFIRMED |
| Day 1–2 — skeleton that opens a file | [`day-01-02-skeleton.md`](day-01-02-skeleton.md) | ✅ DONE |
| Day 3–4 — detection engine + match ratio | [`day-03-04-detection.md`](day-03-04-detection.md) | ✅ DONE |
| Day 5–6 — highlighting that never makes things worse | [`day-05-06-highlighting.md`](day-05-06-highlighting.md) | ✅ DONE |
| Day 7 — indexing and performance | [`day-07-indexing.md`](day-07-indexing.md) | ✅ DONE |
| Day 8 — filters and navigation | [`day-08-filters-navigation.md`](day-08-filters-navigation.md) | ✅ DONE |
| Day 9 — ANSI + live tail | [`day-09-ansi-live-tail.md`](day-09-ansi-live-tail.md) | ✅ DONE |
| Day 10 — configuration UI | [`day-10-configuration-ui.md`](day-10-configuration-ui.md) | ⬜ |
| Day 11 — remote and cross-platform | [`day-11-remote-cross-platform.md`](day-11-remote-cross-platform.md) | ⬜ |
| Day 12 — diagnostics, error handling, honesty pass | [`day-12-diagnostics-honesty.md`](day-12-diagnostics-honesty.md) | ⬜ |
| Day 13 — the listing and the packaging | [`day-13-listing-packaging.md`](day-13-listing-packaging.md) | ⬜ |
| Day 14 — publish, then distribution | [`day-14-publish-distribution.md`](day-14-publish-distribution.md) | ⬜ |

**Current test count: 273, all green.** A verification pass on 2026-10-03 reproduced and fixed
twelve defects behind the Day 1–9 checkmarks (see the README's "Verification pass" entry); each
has a regression test. Every phase ends with `.\gradlew.bat test` passing and
a commit pushed to `main`.
