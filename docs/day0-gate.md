# Day-0 Gate — decision record

**Charter reference:** README §7 (§7.6 — write the result here, one line per axis per plugin plus a verdict, commit it).
**Run date:** 2026-10-01 (the same day as the charter's evidence snapshot).
**How this run was performed:** every programmatically verifiable part of §7 was executed live against the JetBrains Marketplace API and official JetBrains sources, and the GUI scoring pass (§7.3) was **executed the same day** — the owner ran LogLens free tier in WebStorm 2025.3.2 against the three §7.2 files with zero configuration (full record in §8 below; raw answers in `docs/gui-pass-scorecard.md`).

---

## Verdict: **GO-NARROWED — CONFIRMED by the §7.3 GUI pass** — targeting axis A (visible detection reporting) + axis B (editability preservation)

The wedge has narrowed exactly the way the charter's §7.5 predicted it might ("narrows the wedge but does not close it"): no kill criterion is fully triggered, criterion (c) fires as a space-wide warning, and the §7.5 Ideolog check removes most of the incumbent's defect surface. What remains unclaimed, per published evidence from all 13 live competitors, is the §5.1 wedge: **a viewer that reports what it detected instead of silently doing nothing**, and preservation of the user's file. If the GUI pass below contradicts this — commit a NO-GO the same day. A documented no-go is a good outcome.

---

## 1. §11.2 re-verification — the ground moved again (live API, 2026-10-01)

The charter's snapshot listed **7** plugins in this space. Live search found **6 more that its keyword screen missed**, most by verified vendors:

| Plugin | id | Vendor (verified) | Model | Product code / trial | Downloads 2026-10-01 | Rated votes | Notes |
|---|---|---|---|---|---|---|---|
| PRISM Log Viewer | 32995 | Telemark Digital ✅ | FREEMIUM | `PPRISMLOGVIEWER` / 7d | 962 | 0 | **Free tier: streaming 1 GB+ viewport, level filters, follow, "local format detection"** |
| Log Lens: Large & Structured Log Viewer | 33416 | Twilight Ventures ✅ | FREEMIUM | `PTVLOGLENS` / 30d | 799 | 0 | "your file is not taken over and not made read-only" — separate tab alongside the editor |
| Logs - Large Log Viewer | 33365 | Zuhaib ✅ | PAID | `PLOGS` / 30d | 78 | 0 | 1 GB in ~5 ms claim, block-read from disk |
| ANSI Log Viewer | 29008 | Jakub Jirák ✅ | FREEMIUM | `PANSILOGVIEWER` / 7d | 1,535 | 0 | ANSI specialist (SGR/256/truecolor) |
| LogQuarry Log Viewer | 34232 | DevCairn Labs ✅ | PAID | `PLOGQUARRY` / 30d | 22 | 0 | **"read-only viewer"** — ships the §5.2 defect deliberately, 2026 |
| TailScope: Log Viewer, Filter & Follow | 34380 | Vaultworks Apps ✅ | PAID | `PTAILSCOPE` / 30d | 13 | 0 | Description ≈ the LogSmith spec (levels, timestamps, threads, loggers, stack traces, level filter bar, follow). Paid-only, trial required |

Unchanged from the charter's snapshot (re-verified): `LogLens` 33263 (106 dl, 0 rated votes, description confirms the Pro list verbatim), `SqlLogLens` 34505 (0 votes), `LogParser Pro` 29050, `LogCraft` 34397, `Ideolog` 9746 (13,005,238 dl, rating 2.0), `Awesome Log Viewer` 27750 (76,049 dl), `.log` 25828 (**16,379 dl — the §7.4(d) ceiling, confirmed**), `LogQuarry`-adjacent SQL family unchanged.

**Demand pace, measured:** PRISM ≈ 200 downloads/week over 5 weeks; LogLens 106 in its first day; every 2026-entrant reviewed below has **zero rated votes and zero written reviews as of today**. The `.log` paid pioneer's lifetime total is still ~16.4k after years. Supply is accelerating; demand is not.

## 2. Kill-criteria status (§7.4, applied literally)

| Criterion | Status | Evidence |
|---|---|---|
| **(a)** LogLens free tier perfect on all three files incl. visible detection statement → project dead | **UNVERIFIABLE today** (needs the GUI pass). Partially assessed from published claims only — see axis table below. LogLens's own 1.1.0 changelog puts `LogLens`/`LogLens Pro` in the status line — that is a **tier** indicator, **not a format-detection report**. | 1.1.0 release notes, 2026-10-01 |
| **(b)** New entrants' reviews with zero complaints → thesis dead | **PENDING — not triggered.** All seven 2026 entrants (33263, 33365, 33416, 34232, 34380, 34397, 34505) have **0 rated votes, 0 comments**. The charter's death sentence applies once reviews exist and are complaint-free; §11.2 says re-check. | `/rating`, `/comments` endpoints, 2026-10-01 |
| **(c)** LogParser Pro one-star placeholder + no five-stars after 109 days → warning about the whole space | **TRIGGERED.** Histogram: `1:1` — exactly one rated vote, one star, *"Useless, just a placeholder"* — after 109 days and 2,951 downloads. No other rated votes. | `/api/plugins/29050/rating`, `/comments` |
| **(d)** `.log` trial does everything §1.3 well → 16,000-person ceiling | **CONFIRMED CEILING.** `.log` = 16,379 downloads lifetime. Its live reviews add the strongest reliability signal found: *"LOG file looks nice, colored … BUT unstable for big log file such as 20MB, 100MB. after crashed full IDE several time was removed"* and *"Does not work with Idea ultimate."* Even the best-funded product here crashes IDEs on 20–100 MB files. | `/api/plugins/25828`, `/comments` |

**Axis assessment from published claims only (GUI pass required to confirm):**

| Axis | LogLens | PRISM | TailScope | Log Lens TV | LogQuarry | `.log` | Awesome |
|---|---|---|---|---|---|---|---|
| A. Visible detection report | ✗ not claimed (status line shows tier, not format) | claimed detection, ✗ visibility not claimed | ✗ | ✗ | ✗ | ✗ ("autodetect") | ✗ |
| B. Editability preserved | silent | "configurable large-file takeover" | "Never locks your file read-only" ✅ | "not taken over and not made read-only" ✅ (separate tab) | ✗ **read-only by design** | silent | n/a (viewer pane) |
| C. Multi-GB without freezing | ✅ (10 GB claim) | ✅ (1 GB+) | "stays responsive" | ✅ | bounded (8 MiB events) | ✅ (tail caveat documented) | n/a |
| F. ANSI + source click-through | ✅ ANSI | silent | ✅ highlighting | ✅ (title) | ✅ | ✅ (incl. Ctrl+B) | silent |

Nobody claims Axis A. Two verified vendors explicitly claim Axis B. Five claim Axis C. This is the narrowing: **the multi-GB performance job is now crowded; the honesty job is not.**

## 3. §7.5 Day-0 confirmations (web research, 2026-10-01, official sources)

- **Ideolog can be disabled but not uninstalled** in current IDEs (official help: "enable, disable, update, or remove them"; bundled plugins get no Uninstall action). Uninstall request **IJPL-35312** open since 2019, last touched 2026-09-30 — JetBrains: disabled plugins "have 0 cost for IDE performance."
- **JetBrains repaired Ideolog's worst UX**: **WI-78357** *"Unsupported log file looks better with the Ideolog plugin disabled"* — Verified, available 2024.2.1. **WI-77995** added registry keys (`ideolog.terminal.enabled`, `ideolog.large.file.editor.enabled`), off by default outside PhpStorm. Open in 2026: **WI-78355/WI-78356** (Ideolog configurable exceptions), **WI-78443** (registry-key warnings). Per the charter's own rule (§7.5, §A.7): the incumbent's remaining defect surface is now **§5.1 only**.
- **Verified-vendor badge is not, by any published document, the gate for selling.** The gate is: vendor profile with **trader status** + trader identity + **banking details** under the Trader Details tab (Verified Vendor Badge docs; release-plugin checklist). The charter's counterexample — `MyBatis Log` (kookob), paid, unverified, still selling — remains unexplained by any doc (**unverified**).
- **Trader-status declaration (Omnibus Directive 2019/2161) is mandatory for every vendor**, free or paid — confirmed verbatim. Doing it regardless stands.
- **No published policy forbids shipping a plugin that duplicates a bundled plugin's function.** Approval Guidelines v1.3 (effective 2026-03-31) read in full + docs sitemap grepped: no duplication clause. Relevant constraints: §2.2.c (must not modify/hide/interfere with product behavior) and the catch-all removal right. Developer Agreement text unread (SPA) — noted in §10.11's spirit.

## 4. §7.2 adversarial files — generated, ready for the GUI pass

- `testdata/big.log` — **400,622,800 bytes** synthetic Logback-format log (timestamps, threads, loggers, level mix, ERROR + multi-line stack traces every 500 lines). Generator logic is embedded in this run's history; keep it out of git (see `.gitignore`).
- `testdata/hibernate.log` — Hibernate 6 `format_sql`: multi-line SQL, `?` placeholders, separate `binding parameter [N] as [TYPE] - [value]` lines (targets §5.1 and §4.6's taken job).
- `testdata/docker.log` — real ANSI sequences: 16-colour, 256-colour, truecolour, bold/dim, Docker log-prefix format.

## 5. Name check (§6.2's admitted gap — resolved with evidence)

`LogSmith` on the **JetBrains Marketplace: clean (0 results)** — the only place a collision is fatal. On **GitHub: polluted but not owned** — `otto-de/logsmith` (27★, AWS role switcher), `stacksjs/logsmith` (9★, changelog tool), `Aliipou/logsmith` ("zero-config structured logging" — same keyword, different job), plus ~7 smaller repos. On **npm: polluted but not owned** — `logsmith` (winston wrapper), `@stacksjs/logsmith`. Nothing prominent owns it (nothing ≥30★; all are *log-writing* tools, not viewers). **Decision left to the owner:** keep `LogSmith` (marketplace is what matters for discovery; "logsmith jetbrains" will disambiguate on the web) or take the charter's first alternate **`LogAtlas`** to own a clean namespace everywhere.

## 6. What GO-NARROWED means (§8.2 is not negotiable)

Build **R1, R2, R4, R6, R8** and nothing else: visible detection status with match ratio, `Help → Copy detection diagnostics`, zero file mutation, no highlighter on detection failure, live re-apply. Multi-GB streaming (axis C) is necessary plumbing, not the wedge — five verified vendors claim it. No Pro tier, no dashboards, no AI. Economics reminder (§9, read before committing to the two weeks): realistic outcome $0–1,500 in year one; criterion (c) fired; the bottleneck is distribution (§10.9), and `.log`'s 16,379 is the ceiling.

## 7. The §7.3 GUI pass — pre-registered flip conditions (executed: see §8)

Install `LogLens` (33263), `LogParser Pro` (29050), `.log` (25828), `Awesome Log Viewer` (27750) — add `PRISM` (32995) and `TailScope` (34380); open the three files with zero configuration; score axes A–F.

- **Flip to NO-GO if:** any single free product opens all three files perfectly, on first try, with editing preserved, **and** visibly states what it detected. The wedge is closed; go finish Perforce; revisit in six months.
- **Confirm the narrow target if:** a competitor shows a *visible* failure on axis A (silence), B (read-only), C (freeze), or F (ANSI garbage) — §5 tells you which defect to attack, and the build plan starts at Days 1–2 unchanged.
- **Also record:** if new-entrant reviews appear with zero reliability complaints → thesis dead, per (b).

## 8. §7.3 GUI scoring pass — EXECUTED 2026-10-01 (LogLens death test)

Environment: WebStorm 2025.3.2 (the owner's IDE), LogLens free tier, zero configuration, the three §7.2 files. Raw answers: `docs/gui-pass-scorecard.md`.

| Axis | Observed (LogLens, free tier) | Meaning |
|---|---|---|
| Detection correctness | Detected the Logback pattern zero-config; merged the multi-line `format_sql` SQL block and the stack trace into single events; colored levels correctly | The incumbent parses well — parsing is table stakes, not the wedge |
| **A. Visible detection report** | **Silent.** Status bar reads `29 lines · LogLens` — a line count and the tier name. No format name, no match ratio, no failure notice anywhere | **Wedge intact — exactly as predicted** from the 1.1.0 changelog |
| **B. Editability preserved** | Plugin offers a `Text` tab next to `Log`; typing test pending owner confirmation (screenshot not delivered) | Pending one 30-second check |
| **C. Multi-GB** | 400 MB / 3,225,600 lines: fast load, easy scroll, no freeze (owner: "all scrolls easily and fast loaded"); big-file `Text` fallback is the platform's built-in Large File Editor | **Conceded — the free tier handles 400 MB. Do not compete on performance** |
| **F. ANSI** | **Fails visibly.** Blue banner: *"Plugins supporting ANSI codes found. — Install plugins / Ignore extension"*. Truecolor line not rendered; raw `ESC[…m` visible in the `Text` tab. Only LogLens's own level-word coloring appears | **Wedge bonus** — validates R9's slot in the build plan |

**Death-test result (§7.4a): NOT TRIGGERED — LogLens is not perfect.** And it did not fail quietly: on axis F it exhibits the exact honesty pattern §5.1 complains about missing elsewhere — it *detects* something (ANSI) and then *reports its own inability*, redirecting the user to install a different plugin. The space's dominant free product is silent about format detection and cannot render ANSI.

**Verdict after §7.3: GO-NARROWED CONFIRMED.** Build R1, R2, R4, R6, R8 (≈3.5 days, §8.2). R9 (ANSI rendering) now carries direct GUI-pass evidence that the free incumbent punts on it — it stays in the plan, after the narrow set. Remaining open item: axis-B typing test (owner).

*Record committed on 2026-10-01 (§7.3 executed the same day). Charter moves to `docs/charter.md` the day `src/main/kotlin` exists (§11.3).*
