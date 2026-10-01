# LogSmith — a log-file viewer for JetBrains IDEs
### Project charter, market evidence, and build specification

> **One line:** A JetBrains plugin that opens `.log` files properly — no configuration, no silent failure, no breaking your editing — that anyone can install in 30 seconds from the IDE's own plugin search box.
>
> **Status:** greenfield · **zero lines of code written** · all evidence below was pulled live from the JetBrains Marketplace API on **2026-10-01**
> **Owner:** danilgorbunofff (solo)
> **This document is written to be picked up on a different machine with no other context.**

---

## ⛔ READ THIS FIRST — THE GROUND MOVED ON 2026-09-30

When this idea was selected (see the sibling document `parallel-track.md`), the log-file-viewer space looked like a nearly empty niche: a hated bundled incumbent and a handful of abandoned free plugins. **That is no longer the full picture.** Between **2026-06-14 and 2026-09-30**, five plugins were published into this exact space, three of them by *verified* vendors with freemium Pro tiers already announced:

| Published | Plugin | id | Vendor | Model | Downloads (2026-10-01) | Reviews |
|---|---|---|---|---|---|---|
| **2026-09-30 — 1 day ago** | **`LogLens`** | 33263 | **Veryation (verified)** | FREEMIUM `PLOGLENS`, 30-day trial | **106** | **0** |
| 2026-09-25 — 6 days ago | `SQLite Lens` | 33684 | Twilight Ventures (verified) | FREEMIUM | 3,078 | 2 |
| 2026-09-24 — 7 days ago | `XLSX Lens` | 33721 | Twilight Ventures (verified) | FREEMIUM | 1,665 | 0 |
| **2026-09-24 — 7 days ago** | **`SqlLogLens`** | 34505 | zuhaib (verified) | FREEMIUM `PSQLLOGLENS`, 30-day trial | **12** | **0** |
| 2026-09-23 — 8 days ago | `JSONL Lens` | 33397 | Twilight Ventures (verified) | FREEMIUM | 959 | 0 |
| 2026-09-21 — 10 days ago | `LogCraft` | 34397 | logcraft | FREE | 17 | 0 |
| 2026-06-14 — 109 days ago | `LogParser Pro` | 29050 | jakub-jirak (verified) | FREEMIUM `PLOGPARSERPRO`, 7-day trial | 2,951 | 1 (★1) |

**`LogLens`'s description claims, verbatim, in its first paragraph:**

> *"LogLens — open multi-gigabyte log files instantly. Stop fighting your log files. LogLens opens huge logs the built-in viewers choke on — multi-GB files load in a snap with indexed, virtualized scrolling. No 'file too large' wall, no freezing the IDE."*

…and its Pro tier already lists **cross-file trace merge, SQL log queries, JSON field querying & pretty-print, regex-capture columns, an error-density timeline, tail alerts, bookmarks with notes, custom highlight rules, saved filter views, and redaction-on-copy.**

**And `SqlLogLens` (7 days old) describes, verbatim:**

> *"Turn Hibernate, MyBatis and p6spy logs back into SQL you can run. Your application logs every statement with ? placeholders and prints the real values somewhere else. SqlLogLens puts the values back in, quoted correctly for their types, and gives you one statement ready to paste into a database console. … Hibernate 5 and 6 bind logs, including formatted SQL spread over several lines."*

That is **the framework-generic "log → runnable SQL" feature this charter was going to be built around**, described more precisely than I described it, shipped by a verified vendor one week ago.

### Two corrections I owe you (they are my errors, not yours)

1. **I said the generic SQL-log seat was open. It is not.** A 26-keyword screen over 334 plugins concluded "no plugin does framework-generic *log → runnable SQL*". That screen was wrong. Plugins in that job are **named after the framework** ("mybatis"), so they do not rank for queries like `sql log` or `sql decode`. Re-searching by framework name found **at least nine plugins** doing this job, including a free leader with **1,434,574 downloads** and a generic multi-framework converter published a year ago (`Log SQL Converter`, id 28590, FREE, 608 downloads). **The measurement method failed, not the data.** See §10.3.
2. **I described the bundled incumbent's 53 one-star reviews as "proven demand".** They are proven *dissatisfaction with software people cannot uninstall*. That is not the same thing as willingness to install and pay for a replacement. The download evidence for third-party log viewers is much weaker than I implied: the biggest one is **76,049 downloads in 0.7 years**, and the only paid pure log viewer is at **16,379 downloads lifetime**. See §10.1.

### What this means for the decision

This document still specifies a real, buildable, unclaimed product — **but its honest expected value is now a small one-year side income, not a business**, and the window is measurably closing. Read **§7 (the 2-hour Day-0 gate)** before writing any code. The gate is designed to *kill this project* in two hours for the cost of an evening. If it fails, do not build it; finish Perforce instead (§7.4).

**The one durable, evidence-backed edge that nobody has taken is not a feature. It is reliability, and it is written down verbatim in §5.**

---

## Table of contents

- [§0 — The 60-second version](#0--the-60-second-version)
- [§1 — What we are building, in plain English](#1--what-we-are-building-in-plain-english)
- [§2 — The business thesis in three sentences](#2--the-business-thesis-in-three-sentences)
- [§3 — The evidence](#3--the-evidence)
- [§4 — The competition, exactly](#4--the-competition-exactly)
- [§5 — The specification, written by the plugin's own users](#5--the-specification-written-by-the-plugins-own-users)
- [§6 — Naming, the listing, and the product code](#6--naming-the-listing-and-the-product-code)
- [§7 — The Day-0 gate: 2 hours, before any code](#7--the-day-0-gate-2-hours-before-any-code)
- [§8 — The build plan: 14 days](#8--the-build-plan-14-days)
- [§9 — Economics: what this can actually earn](#9--economics-what-this-can-actually-earn)
- [§10 — Risks and open questions](#10--risks-and-open-questions)
- [§11 — How to resume on another machine](#11--how-to-resume-on-another-machine)
- [Appendix A — Marketplace API recipes and traps](#appendix-a--marketplace-api-recipes-and-traps)
- [Appendix B — Plugin-ID reference table](#appendix-b--plugin-id-reference-table)
- [Appendix C — Verbatim review evidence](#appendix-c--verbatim-review-evidence)
- [Appendix D — Provenance, and a note on what is and is not proven](#appendix-d--provenance-and-a-note-on-what-is-and-is-not-proven)

---

<a name="0--the-60-second-version"></a>
## §0 — The 60-second version

**The product.** A plugin for IntelliJ IDEA, PyCharm, GoLand, WebStorm, PhpStorm, RubyMine, CLion, Rider and DataGrip that turns a `.log` file into something you can actually read: colour-coded levels, real columns (timestamp / level / thread / logger / message), filtering, ANSI colour support, clickable stack traces, and — the part nobody has done — **a viewer that tells you which format it detected and what it did, instead of silently doing nothing.**

**Why it is worth building.** The single most-installed log viewer in the JetBrains ecosystem is **`Ideolog` (id 9746, bundled with PHPStorm and PyCharm, 13,005,238 downloads)**. Its rating histogram is:

```
★1: 53   ★2: 9   ★3: 9   ★4: 8   ★5: 8      →  60.9% one-star, mean 1.95
```

**Sixty-one percent one-star, on a plugin JetBrains ships pre-installed to millions of developers.** Fifty-three people wrote a paragraph to say it does not work. That is not a market gap you have to argue for — it is a market gap with 53 signed confessions attached. And in the entire rest of the ecosystem, no independent log *file* viewer has ever received more than a handful of complaints, because there is essentially nothing else to complain about: the last three independent log-file viewers have **0.0% one-star reviews** (§3.3).

**What makes this hard.** It is not the technology. It is that:
- the market is **small** — §10.1 puts the biggest third-party log viewer at 76k downloads;
- the incumbent is **bundled and free**, so you cannot win on distribution, only on quality;
- **five plugins entered this space in the last 6 weeks**, including a freemium Pro product from a verified vendor published **yesterday** (§0 above);
- and **you still have no audience**, which was the actual reason periodictable.lol got 0 upvotes.

**What it is worth.** Between **$0 and roughly $1,500 in year one** in the realistic case, before tax and before your time. The whole economics are in §9, with the arithmetic shown. If you need more than that, this is the wrong project.

**What to do next.** §7. Two hours, before any code. It can kill the project.

---

<a name="1--what-we-are-building-in-plain-english"></a>
## §1 — What we are building, in plain English

*(Written on the assumption that the reader is not a JetBrains plugin developer.)*

### 1.1 The problem, without jargon

A **log file** is the running diary that a program writes while it runs. When something goes wrong in production, the log file is usually the only place that says what happened. Log files look like this:

```
2026-10-01 09:14:02.319 ERROR [http-nio-8080-exec-4] c.e.s.UserService - Failed to load user
java.lang.NullPointerException: Cannot invoke "User.getEmail()" because "user" is null
	at com.example.service.UserService.sendWelcomeEmail(UserService.java:128)
	at com.example.web.UserController.create(UserController.java:64)
2026-10-01 09:14:02.320  WARN [scheduling-1] c.e.s.CleanupJob - 3 stale sessions removed
```

Every line has structure: a timestamp, a level (`ERROR`/`WARN`/`INFO`/`DEBUG`), a thread, the class that logged it, and a message. When a program crashes, the log contains a **stack trace** — the list of `File.java:128` references starting with `at`.

A developer with a broken production system opens this file and reads it. They need four things:

1. To **see the errors** without reading 400,000 lines of `INFO`.
2. To **jump from the log to the source code** — `UserService.java:128` should be one click.
3. To **filter and search** without losing their place.
4. To open a file that is **large** — 500 MB is normal for a day of production traffic.

### 1.2 What the IDE does today, and why it is bad

IntelliJ-family IDEs open `.log` files in the plain text editor: white text, no colour, no columns, no structure. On top of that, JetBrains ships a plugin called **`Ideolog`** that is supposed to fix this. It is bundled with PHPStorm and PyCharm, so millions of people have it whether they want it or not.

Ideolog does three things that make it worse than nothing:

1. **It silently fails.** You give it a pattern for your log format. It fails to match. It does not tell you. Your log stays white. You conclude you are an idiot, and then you find out you are not (§5.1, ~25 of the 53 one-star reviews).
2. **It makes the file read-only.** Open a log file with Ideolog installed and you can no longer edit, delete, or clear it (§5.2, ~7 reviews). Developers on local dev servers hit this constantly.
3. **It removes highlighting that was already there.** One reviewer opened a `.log` file, saw it beautifully syntax-highlighted, accepted the IDE's suggestion to install the plugin — and everything turned grey. They uninstalled and "everything looks good again" (§5.4).

That is the incumbent. That is what 13 million downloads of a bundled plugin bought.

### 1.3 What the plugin does

**Free tier — the whole point of the product:**

| Capability | Detail |
|---|---|
| **Zero-configuration detection** | Recognise a dozen common formats out of the box — Java (Log4j 1, Log4j 2, Logback, `java.util.logging`), Python `logging`, Go `log`/`slog`, Node (pino, winston, bunyan), nginx + Apache access/error logs, PHP/Laravel, Django, .NET `Microsoft.Extensions.Logging`, syslog, and plain timestamped lines. |
| **Say what it did** | A status line that reads `Format: Logback — matched 1,204 / 1,208 lines (99.7%)` with a one-click *"try another format"* list. **Never fail silently.** |
| **Real columns** | Timestamp, level, thread, logger, message parsed into navigable, filterable fields. |
| **Editing keeps working** | The file stays editable, deletable, and clearable. Not read-only. Ever. |
| **Filter by field** | Show only `ERROR` and above; only this thread; only this logger; free-text match. |
| **Stack traces become links** | `Ctrl+click` on `UserService.java:128` opens that file at that line. |
| **ANSI colour** | Docker, npm, pytest and CI output renders as colour, not as `\u001b[31m` garbage. |
| **Works over SSH / WSL / Docker** | Remote file systems included, because that is where production logs live. |
| **Large files** | Streaming line-offset index; a 500 MB file opens without freezing the IDE and without being loaded into memory. |
| **Live tail** | Follow a file while it grows, like `tail -f`, inside the editor. |
| **No IDE restart** | Change a setting, see the change. Immediately. |

**Pro tier — $14.90/year or $19.90 one-time, decided later from real feedback:** multi-file timeline (interleave several log files into one chronological view), saved filter sets and shareable format packs, CSV/JSON export of filtered results, and cross-file correlation by request-ID.

> **Deliberately not in scope for v1:** AI summaries, dashboards, metrics, alerting, database connectivity, anything that requires a server. Every one of those either costs money to run (§9.4) or puts this product in competition with something with a $50M budget.

### 1.4 What "done" looks like on day 30

A developer installs the plugin from the IDE's own plugin browser, opens their production log, sees colour-coded errors immediately, clicks a stack frame to reach the code, and — if the format is unusual — reads a sentence on screen that tells them exactly why detection failed and what to try. They never open a documentation page. They never edit a setting before it works. They never lose the ability to edit the file.

---

<a name="2--the-business-thesis-in-three-sentences"></a>
## §2 — The business thesis in three sentences

**1. The only thing that has ever been proven in this niche is that the *bundled, pre-installed, unavoidable* log viewer is the most hated plugin in the ecosystem (60.9% one-star across 87 reviews) — and that its fifty-three complaints are not feature requests but failure reports.**

**2. Every independent third-party log-file viewer on the marketplace has 0.0% one-star reviews, which means the bar is not "be better than Ideolog", it is "be a thing that does what it says" — and nothing currently on the marketplace has been verified by its own users to do that, including the five that shipped in the last six weeks.**

**3. The product is therefore a deliberately narrow reliability play on a small, proven-painful market: it ships free, it ships in two weeks, and its entire strategic edge is that it says out loud what it detected, never silently fails, never breaks the file, and keeps working after the next IDE version bump — because that last property is exactly what kills every competitor in this graveyard, and maintaining it is boring work that a solo developer can actually sustain.**

---

<a name="3--the-evidence"></a>
## §3 — The evidence

> **Method note.** Every number in this section was read live from `plugins.jetbrains.com/api/` on **2026-10-01** using the recipes in Appendix A. Nothing is quoted from memory or from a secondary source. The raw outputs are listed in Appendix D.

### 3.1 The instrument: review density

The Marketplace API exposes, per plugin, a **histogram of star ratings actually attached to written reviews** (`GET /api/plugins/{id}/rating` → `{"votes":{"5":103,"4":7,…}}`). This is the only real human-sentiment signal in the whole API — as opposed to the auto-generated `meanRating` field, which returns the literal constant `4.0643` for every plugin in the marketplace and is therefore worthless (Appendix A.4).

The useful derived metric is **review density** — votes per 1,000 downloads — because it separates two populations that look identical in a download count:

| Population | Density band | Example | What it means |
|---|---|---|---|
| **Automatically installed / bundled** | 0.000 – 0.013 | `Subversion` 0.000 · `Mercurial` 0.000 · `Perforce P4` 0.0005 · `GitLab` 0.0011 · `Kubernetes` 0.004 · `SonarQube` 0.010 · JetBrains AI Assistant 0.006 | The download count is an artefact of bundling or of a 30-second look. It says nothing about demand. |
| **Deliberately chosen and used** | 0.014 – 1.810 | `Bitbucket Integration Pro` 1.810 · `JetLab` 1.278 · `Mongo DB` 1.102 · `Kafka Client` 0.734 · `Redis Manager` 0.622 · `Qodo` 0.478 · `GitToolBox` 0.014 | A human installed it on purpose and formed an opinion. |

**Consequence for this project:** for a paid plugin, *every* install is a deliberate choice by definition, because someone entered a card number. That makes the paid plugins in this niche the only trustworthy demand evidence available. §3.6 uses exactly that.

### 3.2 The finding that started this: Ideolog

| | |
|---|---|
| Plugin | `Ideolog` |
| ID | **9746** |
| Vendor | **JetBrains** (first-party, not a third party) |
| Pricing | FREE (bundled with PHPStorm, PyCharm, and others) |
| Downloads | **13,005,238** |
| Reviews | **87** |
| Histogram | `★1: 53 · ★2: 9 · ★3: 9 · ★4: 8 · ★5: 8` |
| One-star share | **60.9%** |
| Mean (from histogram) | **1.95** (matches the marketplace listing's 2.00) |

That is what a bundled plugin with a 13-million install base looks like when people are only moved to write a review by anger. §5 is the complete taxonomy of the 53 one-star reviews. **They are a specification.**

### 3.3 Every independent log-file viewer is at 0.0% one-star — only the bundled one is hated

This is the central table of the whole document. These are the log-*file* viewers on the marketplace, ordered by votes:

| Plugin | ID | Downloads | Votes | One-star | Mean | Vendor |
|---|---|---|---|---|---|---|
| **`Ideolog`** (bundled) | 9746 | 13,005,238 | **87** | **60.9%** | **1.95** | **JetBrains** |
| `Application Insights Debug Log Viewer` | 13984 | 462,764 | 23 | **0.0%** | 4.85 | third party |
| `Pretty JSON Log` | 24693 | 34,516 | 9 | **0.0%** | 4.83 | third party |
| `LogConsole` | 21675 | 9,744 | 5 | **0.0%** | 4.75 | third party |
| `Awesome Log Viewer` | 27750 | 76,049 | 3 | 33.3% | 3.84 | socolin (verified) |
| `.log` | 25828 | 16,379 | 1 | — | — | weirddev (verified) |
| `Spiderlog` | 18952 | 2,647 | 0 | — | — | third party |
| `Log Viewer` (Android logcat) | 10015 | 21,552 | 2 | **0.0%** | 4.75 | third party |
| `Log Support 2` | 9417 | 18,974 | 1 | **0.0%** | 4.75 | third party |
| `Structured Logging` | 12083 | 12,961 | 0 | — | — | third party |

**Read the column, not the rows.** Every independent log viewer that has ever accumulated reviews sits at or near zero one-stars. The only one with a large review count is the one nobody chose to install, and it is the only one people hate. `Awesome Log Viewer`'s single one-star is a third of its three reviews and should not be over-read.

**What this table does and does not prove.** It proves that **this job can be done well enough that nobody complains.** It does *not* prove that people will switch to a new entrant or pay for one — that question is answered in §9 and §10.1, and the honest answer there is much less encouraging.

### 3.4 The control: the console equivalent is a solo developer, and it is never hated

`Grep Console` colours the **run console** (not log files) — the closest job to ours that is not us.

| | `Ideolog` (9746) | `Grep Console` (7125) |
|---|---|---|
| Job | view log **files** | colour the **console** |
| Vendor | JetBrains | **Vojtěch Krása — one person** |
| Downloads | 13,005,238 | 3,368,597 |
| Reviews | 87 | **112** |
| Histogram | `★1: 53 · ★2: 9 · ★3: 9 · ★4: 8 · ★5: 8` | `★3: 1 · ★4: 5 · ★5: 106` |
| One-star | **60.9%** | **0.0% — not once, in 112 reviews** |
| Mean | 1.95 | **4.94** |

The same person also ships `StringManipulation` (**243 reviews, 0.4% one-star, mean 4.93**) and `plantuml4idea` (102 reviews, 4.9% one-star).

And in the paid aisle, `ANSI Highlighter Premium` (**id 9707, Ahmed Layouni, PAID `PANSIHIGHLIGHT`, 10-day trial, 1,552,351 downloads, 35 reviews, `★4: 3 · ★5: 32`, 0.0% one-star, mean 4.89**) is a *paid* plugin whose entire job is colouring log output and it has **never received a single one-star review**.

**The lesson:** in this problem space, the failure mode is not "the feature is hard". It is "the plugin fails quietly, breaks the file, freezes the IDE, or dies on a version bump." Both of the good ones are solo developers. That is directly relevant to you.

### 3.5 Review text as a specification

The reviews of `Ideolog` were read in full, verbatim, and bucketed. The result is §5 — **six defect groups, every one of them a bug report with a reproduction, and the fix for each is cheap.** The two most important findings:

- **~25 of the 53 one-star reviews are the same bug:** a regular expression that works everywhere else does not work in Ideolog, and Ideolog does not say so. *"I wrote a regexp that works anywhere else but ideolog. This tool is essentially useless."* The user's real complaint is not that the pattern failed — it is that **nothing told them it failed.**
- **Both of its five-star reviews admit the configuration is the problem:** *"Does its job. Hard to configure, sure."* and *"it's not for noobs, you have to read the documentation in GITHUB and to have a deep knowledge about regular expressions. Only took me 20 min."*

That is a product whose *fans* describe it as difficult, in a niche where the *replacement* can win by being honest.

### 3.6 Proof that log tools sell

All history, all verified live. Note the vendors: two are solo developers.

| Plugin | ID | Vendor | Model | Downloads | Reviews | One-star |
|---|---|---|---|---|---|---|
| `Extra Icons` | 11058 | **JONATHAN_LERMITAGE — solo** | **PAID** `PEXTRAICONS` t7 | 1,397,308 | 110 | **0.0%** |
| `Advanced JSON Studio` | 10650 | Godwin Joseph | FREEMIUM `PJSONPARSERCODE` t7 | 1,456,434 | **549** | 0.9% |
| `Bitbucket Integration Pro` | 13538 | Majera Software | PAID | 271,249 | 491 | 1.4% |
| `JetLab — Integration for GitLab` | 18689 | Majera Software | PAID | 198,861 | 254 | 0.4% |
| `Fast Request – API Buddy` | 16988 | kings1990 | PAID `PFASTREQUEST` t30 | 313,951 | 212 | 0.5% |
| `Toolset` | 14384 | kookob | FREEMIUM `PTOOLSET` t30 | 101,899 | 50 | 2.0% |
| `GitToolBox` | 7499 | **LukaszZielinski — solo** | FREEMIUM | 10,541,342 | 146 | 3.4% |

`Extra Icons`'s histogram in full: `★5: 103 · ★4: 7 · ★3: 0 · ★2: 0 · ★1: 0`. One person, a utility plugin, 1.4 million downloads, zero complaints. **A solo developer can absolutely ship a well-regarded plugin in this marketplace.** Whether they can *earn* from it is a different question — §9.

Observed trial lengths cluster at **7, 10, 14 and 30 days**; the paid plugins in this table use 7 and 30.

<a name="4--the-competition-exactly"></a>
## §4 — The competition, exactly

Ten things can open a log file in a JetBrains IDE. Here they all are, with what each one actually does, in the order you will meet them.

### 4.1 `Ideolog` — id 9746 — the incumbent you cannot compete with on distribution

Vendor **JetBrains**, FREE, bundled with PHPStorm and PyCharm, 13,005,238 downloads, 60.9% one-star. Covered in §3.2 and §5. Its structural advantage is that **it is already installed on millions of machines and there is no way to out-distribute it.** You cannot beat it on reach. You can only beat it on the thing it is failing at, which is §5. Its second structural advantage is that users *cannot easily remove it* — reviewers complain about being unable to disable it — so it will never lose them by attrition.

⚠️ **One thing to check on Day 0:** `Ideolog`'s API record shows `cdate` = "0.0y" (recently updated), but its complaints are 6–7 years old. JetBrains mass-rebuilds first-party plugins, which resets that field. **Do not read the recent date as "JetBrains is fixing it."** See Appendix A.7.

### 4.2 `Grep Console` — id 7125 — the proof, and not a competitor

Vendor **Vojtěch Krása — one solo developer**. FREE. 3,368,597 downloads. 112 reviews, `★3: 1 · ★4: 5 · ★5: 106`, **0.0% one-star, mean 4.94**. It colours the **Run console**, which is a different job from viewing log files, so it is not a competitor — it is the existence proof for the strategy in §2, and it is the single best argument that one person can win this space. Do not try to extend into its job; a console highlighter already exists and is perfect.

### 4.3 `Twilight Ventures` — a six-plugin industrial portfolio, all FREEMIUM, all last 8 days

This is the most important competitive fact in the document and it was invisible until the day this charter was written. **One verified vendor is systematically colonising the "open a file format in an editor tab" job, one file format at a time, with freemium products that all use the same playbook** (30-day trial, read-only by default, streaming, no row cap, "Lens" suffix):

| Plugin | id | productCode | Downloads | Reviews | Published |
|---|---|---|---|---|---|
| `SQLite Lens: SQLite Database File Viewer` | 33684 | `PTVSQLITELENS` | **3,078** | 2 (`★4`, `★5`) | 2026-09-25 |
| `XLSX Lens: Excel XLSX Spreadsheet Viewer` | 33721 | `PTVXLSXLENS` | 1,665 | 0 | 2026-09-24 |
| `JSONL Lens: JSON Lines / NDJSON Viewer` | 33397 | `PJSONLLENS` | 959 | 0 | 2026-09-23 |
| `Notebook Lens` | 33811 | `PTVNOTEBOOKLENS` | 950 | 0 | — |
| `Parquet Lens` | 33510 | `PTVPARQUETLENS` | 753 | 0 | 2026-09 |

**Total: ~7,400 downloads across six plugins, 2 reviews, 0 negative.** They have not been beaten by anyone, but they have also not won: 3,078 downloads on their best plugin in 6 days is modest, and they have no reviews to prove their claims work.

**Two consequences for you:**
1. **The suffix "Lens" is theirs.** Do not name your plugin anything `-Lens`. (§6.2 has the verified collision data.)
2. **They have not published a free-text log-file viewer.** `LogLens` is a separate vendor (below). Their bet is structured data formats — SQLite, XLSX, Parquet, JSONL, Notebooks — which is a *different and arguably better* market than prose log files, and they are winning it by breadth rather than depth.

### 4.4 `LogLens` — id 33263 — the direct competitor, published YESTERDAY

| | |
|---|---|
| Vendor | **Veryation** (verified) |
| Model | FREEMIUM, productCode **`PLOGLENS`**, 30-day trial |
| Downloads | **106** |
| Reviews | **0** |
| Published | **2026-09-30** |

Its claimed free tier: multi-GB streaming index, live tail, filter by level or text/regex, level colour-coding, ANSI-aware rendering, multi-line stack traces preserved, works in every JetBrains IDE. Its **Pro** tier already advertises cross-file trace merge, SQL log queries, JSON field querying & pretty-print, regex-capture columns, error-density timeline, tail alerts, bookmarks with notes, custom highlight rules, saved filter views, and redaction-on-copy.

**This product's free tier is, feature-for-feature, nearly identical to §1.3.** What it does **not** yet have is a single review, and its vendor has exactly one other plugin (`Git Flow GUI`, id 33632, FREE, 81 downloads). It is one day old.

**How to read it honestly:** do not panic and do not dismiss it. Two facts sit side by side:
- **A verified vendor shipped your product yesterday.** That is bad news.
- **The identical-looking claim from three months ago produced this:**
  `LogParser Pro` (id 29050), verified vendor `jakub-jirak`, FREEMIUM `PLOGPARSERPRO`, 7-day trial, published 2026-06-14, **2,951 downloads in 109 days, one review, and that review is a one-star reading: *"Useless, just a placeholder, not a real working plugin."*** Its feature list is *longer* than LogLens's. It did not work.

**Base rate: in this space, a long feature list on a fresh plugin has, so far, predicted nothing.** The differentiator that decides this market is not the list — it is whether the thing works on the user's actual file, on the user's actual IDE version, without warning them in advance to change their logging configuration.

### 4.5 `LogParser Pro` — id 29050 — the cautionary tale

Covered above. Verified vendor, 2,951 downloads, **one review, one star, "not a real working plugin"**. Their claimed feature list, verbatim from the listing: ANSI colour rendering, colour-coded levels, dedicated log editor with table view, level filtering, keyword search, **pattern library with auto-detection of Spring Boot / Node.js / Python / Java**, structured JSON + logfmt parsing, gzip support, stack-trace folding, exception grouping, error timeline with z-score spike detection, latency percentiles (p50/p90/p95/p99), log diff, secret redaction, Grok pattern compiler, correlation-ID tracking, anomaly detection, statistics dashboard.

**Everything on that list is what a customer would pay for, and one person wrote one sentence: it is a placeholder.** File this under: *the specification is not the product.*

### 4.6 `SqlLogLens` — id 34505 — 7 days old, and it took the SQL job

| | |
|---|---|
| Vendor | **zuhaib** (verified) |
| Model | FREEMIUM, productCode **`PSQLLOGLENS`**, 30-day trial |
| Downloads | **12** |
| Reviews | **0** |
| Published | **2026-09-24** |

Verbatim from the listing:

> *"Turn Hibernate, MyBatis and p6spy logs back into SQL you can run. Your application logs every statement with ? placeholders and prints the real values somewhere else. SqlLogLens puts the values back in, quoted correctly for their types, and gives you one statement ready to paste into a database console. Free: restore from a selection — select log lines in any editor or Run console, right-click, Restore SQL from Selection. … Hibernate 5 and 6 bind logs, including formatted SQL spread over several lines. … a statement whose values do not add up is marked so you check it."*

**That is a more precise description of the feature I proposed than the one I wrote.** I proposed "framework-generic SQL log → runnable SQL"; this is it, with type-correct quoting, multi-line formatted-SQL support, and a mismatch safety check — all three of which I listed as the reasons the incumbents fail.

**It has 12 downloads and no reviews.** Which means: the feature is claimed, not proven. See §10.3 for the honest read on what this does to the plan.

### 4.7 `LogCraft` — id 34397 — 10 days old, a different job

FREE, 17 downloads, zero reviews, vendor `logcraft`. Verbatim: *"a free, private, local-first log pipeline workbench for Grok, ECS, Logstash, pasted Kafka messages, and safe log sharing inside your JetBrains IDE."* This targets SRE/DevOps people pasting structured logs, not developers reading application log files. **Adjacent, not competing.** Worth watching because it proves the space is warming up.

### 4.8 `.log` — id 25828 — the paid pioneer, and the ceiling

| | |
|---|---|
| Vendor | **weirddev** (verified) |
| Model | **PAID** `PLOG` |
| Downloads | **16,379** |
| Reviews | **1** (`★5`) |

Verbatim feature list — **this is a paid plugin that has shipped essentially the entire free tier of §1.3, and it took 16,379 downloads to do it:**

> *"View & Navigate Log content as code. Support Log files and Console logs · Syntax Highlighting · Navigate to source code from log category/stack trace (Cmd B|Ctrl B) · Cycle next/prev Error/Warning (F2/Shift+F2) · Open URI's and file paths as hyperlinks · Destructure JSON logs to human-readable format · Convert epoch timestamps to human-readable date-time · Autodetect various log formats & json logs · Support ANSI color/style codes · Support large log files · Tail live log files (currently not supported for large log files)."*

**Read that list twice.** Colouring, hyperlinks, JSON destructuring, epoch conversion, format autodetection, ANSI, large files, live tail — all of it shipped, by a verified vendor, for money, at **16,379 downloads total and one review.**

Two things follow, and both are uncomfortable:

1. **This is the single most important number in the document for the money question (§9).** The demand ceiling for a paid, well-featured log-file viewer in this marketplace is measured in *tens of thousands of downloads*, not hundreds of thousands.
2. **It also tells you what is still missing.** Nobody has 16,379 customers waiting to be taken. There are only ~16,000 people who have ever wanted this enough to install a third-party viewer, and they already have one. **You do not have headroom here. You have a foothold at best.**

### 4.9 `Awesome Log Viewer` — id 27750 — the freemium rival, .NET-leaning

Vendor `socolin` (verified), FREEMIUM `PAWESOMELOGVIEW`, **76,049 downloads**, 3 reviews (`★1: 1 · ★5: 2`, 33.3% one-star), updated recently. Verbatim: *"Real-time log monitoring and visualization · Log filtering system · Automatically capture logs when using run/debug · Support for multiple log sources: Application Insights · OpenTelemetry · Console output, like Microsoft.Extensions.Logging or NLog and even custom logs."* Paid features, verbatim: *"Structured / Hierarchical views · Waterfall view."*

**76,049 downloads is the biggest third-party log-viewer number that exists**, and it took the .NET/Application Insights/OpenTelemetry lane to get there. Note that its **paid** tier is exactly "structured views + waterfall" — which is where I put the §1.3 Pro tier. It is open-source-core and still moving, so treat it as live competition on the .NET side.

### 4.10 `Remote Log Tail: SSH Server Log Explorer` — id 31690 — the live demand signal

Vendor `philz_dev` (**NOT verified**), FREE, **318 downloads**, 1 review (**★5**). Verbatim capability list: verified SSH with fingerprint trust, resilient streaming, structured queries (text/regex/severity/server/source), JSON log reader for Spring Boot/Logback/Log4j2, environment-wide merged timelines, clickable stack traces, time-shift, SFTP browser, snapshot export, redaction extension point.

**That is a serious, thoughtful product, at 318 downloads — and its single review is the only piece of *live, dated* demand evidence in this entire document:**

> *"Where were you 5 years ago?! This is exactly the log management tool I've been waiting for. Completely game-changing. Just one question: why on earth does it only support IDEA?"*

Two readings, both true: (a) someone was genuinely delighted — the job matters to people; (b) **it shipped free, with a great spec, in 2026, and has 318 downloads.** Distribution, not product, is the bottleneck. That is the same wall you hit with periodictable.lol (§10.9).

### 4.11 The abandoned pile — what actually happens to plugins in this space

| Plugin | id | Downloads | Last updated | Status |
|---|---|---|---|---|
| `Log Support 2` (a fork of an abandoned `Log Support`) | 9417 | 18,974 | — | free, 1 review |
| `Structured Logging` | 12083 | 12,961 | — | 0 reviews |
| `LogConsole` | 21675 | 9,744 | — | 0% one-star, 5 votes |
| `Spiderlog` | 18952 | 2,647 | — | 0 reviews |
| `Pretty JSON Log` | 24693 | 34,516 | — | 0% one-star, 9 votes |
| `Application Insights Debug Log Viewer` | 13984 | 462,764 | — | 0% one-star, 23 votes |
| `HotSpot Crash Examiner` (JetBrains, first-party) | 24675 | 3,916 | — | 0 reviews |
| `Log Viewer` (Android logcat, *not* a log file viewer) | 10015 | 21,552 | — | reviewers ask for 2024.2.3 and PhpStorm support |

**Every single one of these has 0.0% or near-0.0% one-star reviews.** Nobody is complaining. Nobody is using them either. **This is the graveyard you are choosing to enter**, and it is full of people who wrote a correct plugin and then had nobody install it.

### 4.12 The version-rot evidence: `Json Helper` (id 13873) — the cleanest proof of the real killer

Not a log viewer, but the most important single plugin in this document after Ideolog:

| | |
|---|---|
| Downloads | **286,044** |
| Reviews | **50** — `★1: 2 · ★3: 1 · ★4: 3 · ★5: 44` |
| One-star | **4.0%** |
| Mean | 4.74 |

**286,044 downloads, 44 five-star reviews, and it nearly died because JetBrains released a new IDE version.** Verbatim, all from its own review page:

> *"Still not working. I think the author is no longer around or just too busy to update this… Would be amazing to get the source code so we can fix the problem."* — `brad.8`, ★1
>
> *"The 2025 version of the idea is reporting an error"*
>
> *"2025无法使用,期待更新"* — "unusable on 2025, hope for an update"
>
> *"ERROR com.intellij.diagnostic.PluginException: Cannot init toolwindow"*
>
> *"I lost all hope to see it updated for latest Idea, but it is back, finally!"*
>
> *"So happy the 2025 issue was solved, I was unable to find a suitable replacement."*

**A beloved, 286,000-download tool whose users could not leave it and could not find an alternative** — and it almost died of a version bump. The same pattern repeats all over this space:

- `MyBatis Log Free` (549,732 DL): *"还在维护吗？最新版idea无效，已经个把月了，不得已使用了别的插件，但是还习惯了这个插件"* — **"Are you still maintaining this? Doesn't work on the newest IDEA. It's been a month. I had no choice but to use another plugin, but I'm used to this one."**
- `Mybatis Smart Code Help Pro` (73,478 DL) — last updated **368 days** ago. `MyBatis Log EasyPlus` (64,263 DL) — **1,138 days**. `JPA SQL` (24,234 DL) — **899 days**. `mybatis-log` (27,493 DL) — **1,469 days**. `SQL Params Setter` (29,811 DL) — **2,001 days**. `Mybatis Log` (69,737 DL) — **3,601 days**.

**This is the actual product.** Not the colouring. Not the columns. **The industry's entire failure mode is "the plugin stopped working on the new IDE and nobody fixed it."** A solo developer who ships something small, correct, and *keeps it working for five years* will outlast every one of the five competitors that launched in the last six weeks, because none of them can tell yet whether they will still be maintaining it in 2031.

### 4.13 What buyers actually type (search landscape)

Measured live by counting results for exact queries via `searchPlugins`:

| Query | Results | Notes |
|---|---|---|
| `log` | 815 | useless, too broad |
| `log file` | 406 | too broad |
| `log viewer` | **96** | `Ideolog` ranks #1 |
| `log analyzer` | **73** | |
| `log file viewer` | **71** | `Ideolog` ranks #1 |
| `log to sql` | 128 | |
| `sql log` | 128 | |
| `jpa sql` | 43 | |
| `hibernate log` | 14 | |
| `lens` | 97 | the Twilight Ventures family lane |

**The entire competitive field for the search a buyer will actually type is under 100 results, and the top spot is held by the 60.9%-one-star bundled plugin.** There is no SEO fight here worth having — which also means there is no SEO channel here worth relying on. The listing name should carry the keywords anyway (§6.3).

---

<a name="5--the-specification-written-by-the-plugins-own-users"></a>
## §5 — The specification, written by the plugin's own users

**All 53 one-star reviews of `Ideolog` (id 9746) were read verbatim.** They fall into six groups. Every group is followed by the concrete engineering requirement that closes it. **This section, not §1.3, is the product.**

### 5.1 Group A — the pattern silently does not match *(~25 reviews — the single biggest cluster)*

> *"I wrote a regexp that works anywhere else but ideolog. This tool is essentially useless."* — `martin.goldhahn.1`
>
> *"Just lost 20 minutes with this and my logs are still plain white text… I've double checked my regex on regex101."* — `jpinto`
>
> *"Couldn't get it to work on even the most trivial regex, even though the Find dialog showed 100% match."* — `sirlordmikey`
>
> *"Despite setting up patterns correctly, the plugin blatantly fails to acknowledge them."* — `stradivari1390`
>
> *"Log format not recognized."* — `martin.peterka`
>
> *"The log format registry seems to be deserted."*

**The engineering requirement — and this is the whole product:**

1. **Never be silent.** If detection or a user-supplied pattern fails, say so, in the UI, in the editor, immediately: `No format matched in the first 200 lines. Showing plain text. [Try a built-in format ▾] [Paste a sample line…]`
2. **Always report a match ratio.** `Format: Logback — 1,204 / 1,208 lines matched (99.7%)`. A user who sees 99.7% knows it worked. A user who sees 3% knows their pattern is wrong, and can stop guessing.
3. **Test the pattern while they type.** In the configuration panel, render a **live before/after preview of their actual file's first lines** with the pattern applied, and the match count updating as they edit. Never require them to close a dialog, restart, and re-open a file to find out whether it worked.
4. **Fail loudly on the file level too.** If zero lines match, do not leave the file plain and say nothing. A status-bar warning widget + an editor banner is the difference between "this plugin is broken" and "my pattern is wrong".
5. **Diagnostics action.** A single `Help → Copy detection diagnostics` that copies the format list tried, the ratios, and the first three unmatched lines — so a bug report is one paste instead of an argument.

### 5.2 Group B — it makes log files read-only *(~7 reviews)*

> *"it does one thing… it makes it so you can't edit, delete, or manipulate log files in any way. So it does do something… something bad."* — `tjohns92109`
>
> *"It's been over 7 years and the problem is still here."* — `Alexander_Khmelnitskiy`
>
> *"Can't clear the log on a LOCAL DEV server because its 'READ ONLY'."* — `jakekoekemoer`

**The engineering requirement:** the plugin is a **viewer over the existing document**, not a file-system layer. Never call `VirtualFile.setWritable(false)`, never register a read-only `FileSystem`, never intercept writes. Highlighting is decoration applied by an `EditorHighlighter` / markup layer. If the user can edit the file in the IDE today, they must be able to edit it in exactly the same way with the plugin installed. **Add a regression test that asserts `virtualFile.isWritable()` before and after the plugin attaches.**

### 5.3 Group C — it freezes or crashes the IDE on real files *(~6 reviews)*

> *"I had to disable the plugin because it freezes the IDE. Took a month to pinpoint."* — `Michaël_Dieudonné`
>
> *"Crashed my PHPStorm when opening laravel.log files."* — `gedealdhi26`
>
> *"Doesen't open very large log file"* — `Diego_Marcolungo`

**The engineering requirement:**

1. **Nothing on the EDT that parses a file.** Format detection and line indexing run in a background coroutine / pooled thread; the UI shows progressively improving results.
2. **Bounded memory.** Never hold the whole file. Maintain a line-offset index (`long[]` of line starts, built in 8 MB chunks) and read line content on demand by offset.
3. **Cancel on close.** If the user closes the tab, in-flight indexing must be cancelled, not left running.
4. **A performance test in CI:** generate a 500 MB synthetic log, open it, assert time-to-first-paint < 1 s, assert indexing completes, assert no action > 50 ms blocks the UI thread.
5. **A hard cap with a message.** Above the cap, degrade to plain viewing and *say so*, rather than hanging.

### 5.4 Group D — it makes logs worse than no plugin at all *(the most damning pair of reviews on the page)*

> *"Opened a .log file without the plugin. It was beautifully syntax highlighted. Got the suggestion to install the plugin, and I did. After that, nothing is highlighted, all is gray. Uninstalled the plugin, and everything looks good again."* — `hallenfur`
>
> *"it provides nothing and actually removes existing highlighting"* — `bradley.hayes`

**The engineering requirement:** **this is the sharpest product requirement in the document.** The IDE already syntax-highlights `.log` files better than Ideolog does. Your plugin must be a **strict improvement or a no-op**. Concretely:

- If your highlighter has nothing to add, **let the platform's highlighter win** — do not install an empty markup layer.
- **One-click off switch** that instantly restores the platform's default appearance, without uninstalling and without a restart. A "Disable for this file" action in the editor's context menu.
- Never attach your highlighter to a file whose format you could not detect (§5.1) — fall back to the platform rendering and say so in the status bar.

### 5.5 Group E — the configuration experience *(users wrote the spec themselves)*

> *"The good experience: to have already preconfigured log regex with default colors and let the user change them."* — `7killua` *(a user literally describing this product)*
>
> *"Changes are only applied after you restart the IDE."* — `stevejluke+jet`
>
> *"Cannot copy and paste regex patterns into the configuration window."* — `mac671`

**The engineering requirement:**

1. **Twelve built-in formats, pre-coloured, that work before the user configures anything.** Configuration is opt-in and only for unusual formats.
2. **Apply immediately, no restart.** A settings change re-highlights open editors in place, preserving scroll position and caret.
3. **Normal text fields.** Paste, cut, undo and select-all must work in every configuration input. (This sounds trivial; it is an explicit one-star review.)
4. **Live preview** of the first matching lines *inside* the config UI (§5.1 item 3).

### 5.6 Group F — unhandled realities

> *"This does not escape any ANSI color codes. It also doesn't work with Jetbrains' own Remote File Systems plugin."* — `kpervin`
>
> *"Works on windows, does not work on Linux? well."* — `orman iec`
>
> The scrollbar has been broken *"6 YEARS AGO"* — `henningsprang`

**The engineering requirement:**

1. **ANSI escape codes render as colour, always.** This is table stakes in 2026 (Docker, npm, pytest, CI). `ANSI Highlighter Premium` (id 9707) has 1.55M downloads and zero one-stars doing exactly this — proof the expectation exists.
2. **Remote File Systems from day one.** Use `VirtualFile` APIs exclusively; never `java.io.File`. Test against SSH (Remote Development), WSL, and a Docker-mounted volume. **Production logs live on remote machines.**
3. **Cross-platform.** Windows, macOS, Linux, with tests that cover line endings (`\r\n`), file encodings (UTF-8 with BOM), and case-sensitive file names.
4. **Stack-trace navigation** (`File.java:128` → `OpenFileDescriptor`) — `.log` (id 25828) has it, Ideolog does not.

### 5.7 And the two five-star reviews — the product's second specification

> *"Does its job. Hard to configure, sure."* — `vitorhugods.1` (★5)
>
> *"it's not for noobs, you have to read the documentation in GITHUB and to have a deep knowledge about regular expressions. Only took me 20 min."* — `jguidos` (★5)

**Twenty minutes and a GitHub README to colour a log file.** That is the review a *satisfied* customer wrote. Any competitor that removes that sentence wins the comparison in the review section for years.

### 5.8 The requirement list, consolidated

| # | Requirement | Closes | Cost |
|---|---|---|---|
| R1 | Report detected format + match ratio on screen | §5.1 (~25 reviews) | ~1 day |
| R2 | Explicit failure message with one-click alternatives | §5.1 | ~0.5 day |
| R3 | Live preview of pattern against the real file, in the config UI | §5.1, §5.5 | ~1.5 days |
| R4 | Never modify file writability; regression test | §5.2 (~7 reviews) | ~0.5 day |
| R5 | Off-EDT indexing, streaming, bounded memory | §5.3 (~6 reviews) | ~3 days |
| R6 | No-op-if-no-improvement; per-file disable | §5.4 (2 reviews) | ~0.5 day |
| R7 | 12 built-in formats, pre-coloured | §5.5 | ~3 days |
| R8 | Apply without restart | §5.5 | ~0.5 day |
| R9 | ANSI rendering | §5.6 | ~1 day |
| R10 | Remote File Systems support | §5.6 | ~1 day (design, not code) |
| R11 | Stack-frame → source navigation | §5.6 | ~1 day |
| R12 | CI that verifies the plugin against the next IDE version | §4.12 | ~1 day |

**R1, R2, R4, R6 and R8 together are about three and a half days of work and they are the entire competitive advantage of the product.** R12 is the one that decides whether you still exist in 2028.

---

<a name="6--naming-the-listing-and-the-product-code"></a>
## §6 — Naming, the listing, and the product code

### 6.1 The decision

**Product name: `LogSmith`** — with the listing title **`LogSmith — Log File Viewer for IDEA, PyCharm, GoLand & more`**.

**Alternates, all verified clean, in preference order:** `LogAtlas` · `LogDeck` · `TidyLog` · `ReadLog` · `LogLines` · `LogSmith`.

**Why `LogSmith`:** it is the only short, unmistakable option that carries no false promise. The two candidates that read better on a landing page were rejected on evidence:

- **`LogPilot`** — clean (`total=0`, and all 5 near-matches are unrelated: `JAIPilot` 27706, `Firebase Co-Pilot Build Uploader` 32211, `SpringPilot` 29960). **Rejected because in 2026 "-Pilot" reads as "AI assistant".** This plugin has no AI. Setting that expectation produces exactly the one-star review pattern this whole charter exists to avoid.
- **`LogWizard`** — clean (`total=0`), and **rejected because "Wizard" promises a configuration dialog, which is precisely Ideolog's defect (§5.5).** Naming the product after the thing users hate about the incumbent is a self-inflicted wound.

`LogSmith` is plain, brandable, has zero collision in the JetBrains Marketplace, and carries no claims.

### 6.2 The verified collision table

Every name below was checked on **2026-10-01** by exact-name search against the live marketplace (Appendix A.8). **A name here marked "clean" still needs one more check before you commit — see the warning below the tables.**

**✅ Verified clean — zero exact-name results, zero relevant near-matches:**

`LogSmith` · `Log Smith` · `LogAtlas` · `LogDeck` · `Log Deck` · `TidyLog` · `ClearLog` · `ReadLog` · `LogReader` · `LogLines` · `Logly` · `Loggram` · `LogKitten` · `LogCrux` · `LogMason` · `LogNest` · `LogLoom` · `ParseLog` · `LogTamer` · `LogWhisperer` · `LogPulse` · `SiftLog` · `LogSift` · `Log Sift` · `LogBook` · `LogLight` · `LogSurfer` · `Log Hero` *(1 unrelated near-match: `Heroku Integration` 6659)*

**❌ Rejected — exact-name collision:**

| Name | Colliding plugin | id | Note |
|---|---|---|---|
| **`LogLens`** | `LogLens` | **33263** | The direct competitor. Published 2026-09-30. **Also: never use any `-Lens` suffix — Twilight Ventures owns it (§4.3).** |
| **`LogCraft`** | `LogCraft` | **34397** | Published 2026-09-21. |
| `LogViewer` / `Log Viewer` | `Log Viewer` (Android logcat) | 10015 | 21,552 downloads — different job, same name. |
| `LogScope` | `V2EX LogScope` | 29034 | |

**⚠️ Rejected — polluted / ambiguous:**

| Name | Problem |
|---|---|
| `Log Reader` | 9 near-matches, no relevance; the words are too generic to defend. |
| `Log Insight` | Collides with VMware/Datadog products outside the marketplace. |
| `Log Studio` | 96 results; generic. |
| `LogSense` | `LogSensei` 29628. |
| `LogExplorer` | `Salesforce LogExplorer` 27299. |

**⚠️ The collision check that this table cannot do, and that you must do yourself before committing the name:** the checks above cover **only the JetBrains Marketplace**, which is the only place where a collision is *fatal*. They do **not** cover GitHub, npm, or the open web — where a same-named project will outrank you in Google and make your plugin unfindable. Before you commit the name, spend five minutes searching `LogSmith` on GitHub, npm, and Google. If something prominent owns it, take `LogAtlas` instead. **This is an honest gap in the evidence, not a checked fact.**

### 6.3 The listing (this is what determines whether anyone finds the plugin)

The marketplace listing is a search result. Write it for the search box first.

| Field | Content |
|---|---|
| **Name** | `LogSmith — Log File Viewer for IDEA, PyCharm, GoLand & more` |
| **Vendor** | `daverg` *(or your own name — new vendor, no history)* |
| **Tags** (all 10) | `log`, `log viewer`, `log file`, `log analyzer`, `logging`, `log4j`, `logback`, `ansi`, `tail`, `console` |
| **Description, first sentence** | Must contain `log file viewer` and `zero configuration` — the first sentence is what the search snippet shows. |
| **Screenshot 1** | A real 200,000-line production log, coloured, with the status bar reading `Format: Logback — 99.7% matched`. **Show the thing nobody else shows.** |
| **Screenshot 2** | The same file *before*, plain white, side by side. |
| **Screenshot 3** | The failure state: `No format matched. [Try a built-in format ▾]`. **Showing the failure state is a marketing asset here, because the incumbent's failure state is 53 one-star reviews.** |
| **Screenshot 4** | `Ctrl+click` on a stack frame landing in source code. |
| **Compatibility** | Declare the full range: IDEA, PyCharm, WebStorm, PhpStorm, GoLand, CLion, Rider, RubyMine, DataGrip, Android Studio, plus Community/Ultimate. |
| **Free/paid** | Free, no trial, no telemetry, no account. |
| **Support** | A public GitHub repo with an issue template. **The issue template should ask for the `Help → Copy detection diagnostics` output (§5.1 item 5)** — so bugs arrive diagnosable. |

**Listing title keywords are the entire discovery channel.** §4.13: the search space is under 100 results, so a well-tagged listing cannot be buried. But it also cannot be *found* without the words `log` and `viewer` in the name, because that is what people type.

### 6.4 The product code (needed only if/when you go paid)

If you list on the marketplace as PAID or FREEMIUM you get a **productCode**, which is the only signal in the public API that a paid listing exists (`buyUrl` is always `null` and there are no price fields anywhere in the API — Appendix A.5).

**Observed codes in this space:** `PLOG` (`.log`) · `PLOGLENS` (LogLens) · `PSQLLOGLENS` (SqlLogLens) · `PLOGPARSERPRO` (LogParser Pro) · `PAWESOMELOGVIEW` (Awesome Log Viewer) · `PMYBATISLOG` and `PJPASQL` and `PTOOLSET` (vendor `abc`/kookob) · `PMYBATISHELPER` (bruce-ge) · `PJTRACKER` (jtracker) · `PEXTRAICONS` (Extra Icons).

**Suggested: `PLOGSMITH`.** ⚠️ **This was not verified as unused** — the dataset available locally does not carry product codes, and the API does not expose a code registry. **Uniqueness is enforced by the listing form at the moment you create it, and a collision will be rejected there.** If `PLOGSMITH` is taken, try `PLOGSMITHLOG` or `PLOGSMITHVIEW`. This is a known-unchecked item (§10.12).

### 6.5 The repository name

**`LogViewer-IntelliJ-Integration`** — deliberately *not* the product name. The repo describes the integration (the thing that will exist for years), while the brand lives in the plugin's display name (the thing you may want to change after real user feedback). Renaming a plugin is a marketplace edit; renaming a repository breaks every link anyone has ever posted. **Naming the repo after the job, not the brand, is the same convention used for the sibling `Perforce-IntelliJ-Integration` repo.**

<a name="7--the-day-0-gate-2-hours-before-any-code"></a>
## §7 — The Day-0 gate: 2 hours, before any code

**This gate exists to kill this project for the price of an evening.** If you run it and it fails, you have saved two weeks. If you skip it, you will build a product into a slot that a verified vendor filled yesterday, and you will find out three months later from the review section.

### 7.1 Setup (30 minutes)

In an **up-to-date 2026.x IDE** (IDEA Community or Ultimate, current stable), install these four plugins from the marketplace browser alongside the bundled `Ideolog`:

| Install | id | What you are testing |
|---|---|---|
| `LogLens` | **33263** | The direct competitor, published 2026-09-30. **The gate turns on this one.** |
| `LogParser Pro` | **29050** | The 109-day-old verified vendor; does a long feature list actually work? |
| `.log` | **25828** | The paid pioneer; the quality bar and the feature baseline. |
| `Awesome Log Viewer` | **27750** | The 76k-download freemium rival. |

### 7.2 Prepare three files (30 minutes)

Deliberately adversarial — each file targets a different §5 defect group:

1. **`big.log` (~400 MB)** — a real or synthetic Logback/Log4j2 application log. Targets §5.3 (freezing on large files).
2. **`hibernate.log`** — Hibernate SQL with `hibernate.format_sql=true`, i.e. **statements spread over multiple lines with `?` placeholders and separate `binding parameter` lines.** Targets §5.1 (pattern matching) and §4.6 (the SQL job).
3. **`docker.log`** — real ANSI-coloured Docker/CI output with escape sequences in it. Targets §5.6.

### 7.3 Score it (60 minutes)

Open all three files through each plugin, **exactly as you would on a normal day, with no configuration first.** Score each on the six §5 axes:

| Axis | Question |
|---|---|
| **A. Silent failure** | Did it match? **Did it tell you it matched, or did you have to guess?** |
| **B. Editability** | Can you still edit, truncate and clear the file? Is it read-only now? |
| **C. Performance** | Did `big.log` open without freezing? Did the IDE stay responsive? |
| **D. Net improvement** | Is the rendering *better* than the platform default? Or just greyer? |
| **E. Configuration** | Did you have to configure anything to open a normal application log? How long? |
| **F. Reality** | Does ANSI render? Does the stack frame click through to source? |

### 7.4 The kill criteria — read these literally

> **(a) If `LogLens`'s free tier opens all three files correctly, on the first try, with no configuration, with editing preserved, and with a visible statement of what it detected — then the reliability wedge is closed and this project is dead. Do not build it. Ship Perforce instead, and revisit in six months.**
>
> **(a′) Softer version of (a):** if `LogLens` gets *most* of it right but fails one of the three axes **in a way the user can see** (silent failure, read-only, freeze, or ANSI garbage), the wedge is open — and §5 tells you exactly which one to attack.
>
> **(b) Read `LogLens`'s and `SqlLogLens`'s reviews.** They have none today. If they have accumulated reviews by the time you run this gate, **read every word.** Fifty-three complaints about Ideolog is the entire thesis of this document; if the new entrants have *zero* complaints, the thesis is dead — do not build. This is the single cheapest market test in the entire charter and it is free.
>
> **(c) If `LogParser Pro` still shows a one-star "placeholder" review and no five-stars after 109 days, treat it as a warning about the whole space, not just about that vendor.** Low engagement on shipped, feature-rich products is the dominant pattern in §4 and it is not explained by product quality alone.
>
> **(d) If `.log`'s free trial reveals that a single person's plugin already does everything in §1.3 well, ask what is left for Pro.** `.log` has 16,379 downloads total (§4.8). That is also the demand ceiling. Look at it. Decide if you are comfortable building into a 16,000-person market.

### 7.5 Also confirm on Day 0 (15 minutes)

- **Vendor requirements.** `LogParser Pro`, `LogLens`, and all six Twilight Ventures plugins carry `verified=True`; `MyBatis Log` (kookob) does **not**. Check whether the *verified vendor* badge is required to charge money. **Unresolved in this charter** (§10.11). ✅ *Confirmed mandatory:* the **trader-status declaration** (EU consumer-law requirement) — this one you have to do regardless.
- **Platform APIs.** Check whether `Ideolog` can now be **disabled** in current IDEs. If JetBrains has fixed the read-only/disable complaints, the incumbent's remaining defect surface is only §5.1 — which narrows the wedge but does not close it.
- **Bundling policy.** Check the current JetBrains terms on shipping a plugin that *also* does what a bundled one does. This is why `Grep Console` stayed in the console lane.

### 7.6 Decision record

Write the result into this repository as `docs/day0-gate.md` — one line per axis per plugin plus a verdict: **GO / NO-GO / GO-NARROWED (targeting axis X)**. Commit it. If it is NO-GO, commit that too, with the date. **A documented no-go is worth more than a month of undirected building**, and it is the thing that makes this repository honest rather than promotional.

---

<a name="8--the-build-plan-14-days"></a>
## §8 — The build plan: 14 days

Assumes the §7 gate is **GO** or **GO-NARROWED**. Every day has a shippable artifact.

### Day 1–2 — Skeleton that opens a file and tells you what it did

- `git init`, IntelliJ Platform plugin, **IntelliJ Platform Gradle Plugin 2.x** (not the deprecated `gradle-intellij-plugin` 1.x).
- `plugin.xml` with `com.intellij.modules.platform` as the only hard dependency — **so it loads in every JetBrains IDE, not just IDEA**, which is both a bigger market and the explicit complaint in `Remote Log Tail`'s review (§4.10).
- Register a **file editor provider** for `.log` (and `.out`, `.txt` opt-in), attaching to the platform text editor rather than replacing it.
- **Ship the status line immediately:** `Format: <unknown>` with a tooltip *"detection runs after the first 200 lines are indexed."* Even the failure state must be visible from day one (§5.1).
- Deliverable: install into a live IDE, open a `.log`, see the status line.

### Day 3–4 — Detection engine and the match ratio

- Implement **format sniffers** as small, independently testable predicates: a compiled regex plus a priority and a display name. Start with the first 200 lines; score each candidate by match ratio; pick the winner; refine in the background over the whole file.
- **Built-in formats (twelve):** Log4j 1, Log4j 2, Logback, `java.util.logging`, Python `logging`, Go `log`/`slog`, Node (pino, winston, bunyan), nginx error + access, Apache access/error, PHP/Laravel, Django/gunicorn, .NET `Microsoft.Extensions.Logging`, syslog, plus a plain `YYYY-MM-DD HH:MM:SS` fallback.
- **R1 lands here:** the status line becomes `Format: Logback — matched 1,204 / 1,208 lines (99.7%)`.
- **Tests (`BasePlatformTestCase` + `myFixture.configureByText`)** — one per format, with **real captured samples**, including truncated trailing lines, multi-line stack traces, and `\r\n` endings. This test suite *is* the product's quality.
- Deliverable: the ratio displays correctly on all three §7.2 files.

### Day 5–6 — Highlighting that never makes things worse

- Custom `EditorHighlighter` over the **existing document**. Timestamp / level / thread / logger / message get distinct attributes; level gets a colour ramp (ERROR red, WARN amber, INFO default, DEBUG dim).
- **R4 lands here:** no file-system mutation, of any kind. Add the writability regression test.
- **R6 lands here:** if detection failed, **install no highlighter at all** and let the platform render — plus a status-bar warning. Never grey out a file that was previously fine.
- Deliverable: open, colour, edit, save, clear — the file is untouched by the plugin.

### Day 7 — Indexing and performance

- **Line-offset index** built in a background coroutine: `long[]` of line starts, filled in ~8 MB read chunks. Content is read by `(offset, length)` on demand.
- Highlight **lazily by visible range**: only compute markup for lines the editor is about to paint, with an LRU cache keyed by line number. Cancel in-flight work on tab close.
- **R5 lands here.** Performance asserts in CI: 500 MB file, time-to-first-paint < 1 s, no > 50 ms EDT block, memory bounded by index + cache (not file size).
- Deliverable: a measured number, in the README, on a real 500 MB file.

### Day 8 — Filters and navigation

- Filter by level (checkboxes), by logger, by thread, by free text; a filter is a *view*, never a mutation of the file.
- **`Ctrl+click` on a stack frame → `OpenFileDescriptor`** (R11) for `File.java:128`, including "find in project by class name" fallbacks when the path cannot be resolved.
- Cycle next/previous error (`F2` / `Shift+F2`), matching `.log`'s behaviour (§4.8).
- Deliverable: the three-file demo works end to end.

### Day 9 — ANSI + live tail

- **ANSI (R9):** parse `\u001b[…m` in the highlighter and map to attributes — including the 16-colour base, 256-colour, and truecolour forms. Test against real Docker/npm/pytest output.
- **Live tail:** follow a growing file via `VirtualFileListener`, appending without re-parsing. Degrade to a message for files above the memory-safe cap (`.log` documents that exact limitation, §4.8 — do it better).
- Deliverable: a running Docker container's log, live, in colour.

### Day 10 — The configuration UI (R3, R7, R8)

- **Settings page** under `Settings → Tools → LogSmith`: a table of built-in formats (on/off, colour), then user-defined formats.
- **The live preview panel is the differentiator:** as the user edits a pattern, the first matching lines of **an actually-open file** re-render below with a live match ratio. No restart, no close-and-reopen, no guessing (§5.1, §5.5).
- **Full clipboard support** in every text field — paste, cut, undo, select all. Yes, this was a one-star review (§5.5).
- **Apply immediately** by re-highlighting open editors in place, preserving caret and scroll (R8).
- Deliverable: a first-time user never opens this page; a power user never leaves it.

### Day 11 — Remote and cross-platform

- **R10:** audit every file access to confirm `VirtualFile`-only — no `java.io.File`, no `java.nio.Files` on paths from the IDE. Test over Remote Development (SSH), WSL, and a Docker mount.
- Cross-platform passes: Windows `\r\n`, UTF-8 BOM, non-ASCII log content, case-sensitive paths.
- **Do not ship without this.** Production logs live on remote machines; a plugin that fails over SSH is a plugin for hobby projects.
- Deliverable: the same three test files open correctly over SSH.

### Day 12 — Diagnostics, error handling, and the honesty pass

- **`Help → Copy detection diagnostics`** (R2, §5.1 item 5): copies tried formats, ratios, the first three unmatched lines, IDE build number, and plugin version. This is the bug report you want.
- Sweep for every place the plugin could fail silently and give each one a visible message: unreadable file, permission denied, encoding failure, unsupported compression, > cap.
- **The honesty pass:** read `plugin.xml` and every UI string. **Delete any claim the code does not support.** No "AI", no "enterprise", no "powerful", no "seamless". `LogParser Pro`'s one-star review says *"Useless, just a placeholder"* — and it was earned by overselling a long feature list (§4.5).
- Deliverable: a plugin whose UI strings are all literally true.

### Day 13 — The listing and the packaging

- Release build, **`sinceBuild` = current stable, `untilBuild` deliberately unset/wide** (§8.1).
- The listing exactly as specified in §6.3, with the four screenshots — **including screenshot 3, the failure state.**
- Public GitHub repository with an issue template requesting the diagnostics paste.
- Deliverable: the plugin and the page, ready to publish.

### Day 14 — Publish, then the part that actually decides the outcome

- Publish. It goes live immediately — **there is no human review queue for a free plugin** (the approval gate applies to paid listings and, per `MyBatis Log`'s review history, is not a barrier to charging, only to *activating*).
- **Then do the thing that was missing on the last launch.** A marketplace listing with no audience gets ~0 installs. §4.10 is the proof: `Remote Log Tail` shipped a *better* product than yours, free, in 2026, **and has 318 downloads.** `LogParser Pro` shipped a longer feature list and has 2,951 downloads and one angry review. **Downloads come from people showing up, not from the listing existing.**
- The realistic distribution plan: answer actual questions in the places where developers complain about log files (IntelliJ YouTrack, Stack Overflow `intellij-idea` + `logging` tags, r/java, r/IntelliJIdea, the JetBrains Discord) — **with the plugin only where it is genuinely the answer to the question asked.** The §5 material is your script: you can describe fifty-three specific bugs people are living with, because you read them.
- Deliverable: published, plus the first three genuine answers posted.

### 8.1 The two engineering decisions that must survive to the next machine

**Decision 1 — `untilBuild` stays unset (or absurdly wide), permanently.**

```kotlin
// build.gradle.kts
intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild.set("243")      // current stable at release time
            // untilBuild deliberately NOT set: the plugin declares compatibility
            // with every future IDE version, and CI verifies it weekly.
        }
    }
}
```

`Json Helper` had 286,044 downloads, 44 five-star reviews, and almost died because a version bump made it incompatible (§4.12). `untilBuild` is the switch that causes that death — every plugin that rots does so because the author pinned a ceiling and stopped moving it. Leave it open; let CI catch real breakage.

**Decision 2 — a weekly CI job that runs `verifyPlugin` against current stable *and* EAP.**

```yaml
# .github/workflows/verify.yml
name: Verify plugin compatibility
on:
  schedule: [{ cron: '0 6 * * 1' }]   # Mondays 06:00 UTC
  workflow_dispatch:
jobs:
  verify:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }
      - run: ./gradlew verifyPlugin verifyPluginProjectConfiguration
```

**This job is the product.** It converts the industry's dominant failure mode (§4.12) from "your users discover it and write a one-star review" into "you get an email on Monday morning." Combined with an open `untilBuild`, it is the cheapest possible insurance against the graveyard.

### 8.2 The build order is not negotiable

Build **R1, R2, R4, R6, R8 first** (§5.8). They are three and a half days of work, they are the entire competitive advantage, they are all cheap, and **every single one of them is a thing the five new competitors have not demonstrated and cannot demonstrate without users.** Do not build the Pro tier. Do not build dashboards. Do not build anything with the word "insight" in it. Ship the boring, correct, honest thing — because the honest thing is the only uncontested slot left.

---

<a name="9--economics-what-this-can-actually-earn"></a>
## §9 — Economics: what this can actually earn

**Read this section before §8. If the number below is not acceptable to you, stop here and do not build.**

### 9.1 What the marketplace looks like, measured

From the third-party scan `poko8725/jetbrains-marketplace-scan` (snapshot **2026-08-03**), covering **404 paid plugins**:

| Metric | Value |
|---|---|
| **Median downloads, all paid plugins** | **1,411** |
| p75 | 6,000-ish |
| **p90** | **43,031** |
| Maximum | 1,548,926 |
| **Median downloads, 2026 cohort (n=138)** | **171** |
| **Median annual price** | **$19** (p10 $5 · p25 $10 · p75 $39 · p90 $59 · max $350) |
| Median perpetual price (n=60) | $9.90 |
| Share on free Community edition | **91% (366/404)** |

**Translate that to money, assuming a 5% trial rate and a 6.3% trial→paid conversion** (the author's own figures, retracted-and-restated in that repo's README):

| Scenario | Installs | Trials (5%) | Sales (6.3%) | Revenue @ $19/yr |
|---|---|---|---|---|
| **The median paid plugin** | 1,411 *(lifetime)* | 71 | 4 | **~$85 — total, lifetime** |
| **The 2026 cohort median** | 171 | 9 | 1 | **~$10** |
| A good outcome — beats `.log`'s lifetime 16k | 20,000 | 1,000 | 63 | **~$1,197/yr** |
| A very good outcome — matches `Awesome Log Viewer`'s 76k | 76,000 | 3,800 | 239 | **~$4,550/yr** |
| The p90 paid plugin | 43,031 | 2,152 | 135 | ~$2,575/yr |

**So: half of all paid JetBrains plugins will earn about $1,340 in their entire lifetime.**

### 9.2 The honest distribution of outcomes for this project

| Scenario | Probability | Year-1 revenue |
|---|---|---|
| Nobody finds it; the listing publishes into silence — **the modal outcome, and the one you already experienced once** | ~50% | **$0** |
| A few hundred installs, no reviews, no conversion | ~30% | **$0** |
| 5,000–20,000 installs, some reviews, a handful of Pro sales | ~15% | **$100–$1,200** |
| 20,000–80,000 installs, positive reviews, steady Pro conversion | ~5% | **$1,200–$4,500** |
| It takes over the niche and becomes the log viewer | <1% | **$10,000+/yr** |

**Weighted expected value: roughly $200–$600 in year one, before tax and before valuing your time at anything.** Base case to plan on: **$0 to $1,500.**

Three structural reasons the number is this small, and why they are not fixable by working harder:

1. **The addressable market is tiny.** The biggest third-party log viewer ever is at 76,049 downloads (§4.9). The only paid pure log viewer is at 16,379 **lifetime** (§4.8). Those are the ceiling and the realistic target.
2. **Conversion is structurally unmeasurable in advance.** You cannot know your trial→paid rate before you have a paid tier running. Every number above is an assumption and is labelled as one.
3. **The free tier is genuinely good.** A free plugin that solves the problem probably *should* be the outcome — and "free and brilliant" has known precedent here: the `Markdown Navigator` family has **5,392,215 downloads** and is **still free**, years later (§10.6).

### 9.3 Why build it anyway

Not for the money. For three other reasons, all of which are real:

1. **It is two weeks of work with a two-week feedback loop.** The Perforce project is a 40-hour integration requirement with an unread license and a slower build. This one can be live, measured and *abandoned* in the same fortnight.
2. **It compounds into a reputation, and reputation is the asset both projects are actually missing.** `Grep Console`'s author is one person with three plugins and 112 five-star-scale reviews. That reputation is why his next plugin gets installed. **Zero audience is your bottleneck (§10.9) and shipping a good, free, well-reviewed utility is one of the few legitimate ways to build one.**
3. **If §9.2's 5% case hits, it hits harder than the table suggests**, because a working free plugin with convertable power users in a market with a 16,000-person paying precedent and no incumbent worth respecting is exactly the shape of small business that pays a mortgage on the long tail.

### 9.4 Costs: genuinely $0 — and why

**You need no server. None. Ever, for v1.**

| Item | Cost | Why |
|---|---|---|
| Distribution | **$0** | JetBrains hosts and serves the plugin. |
| Server / hosting | **$0** | The plugin is a desktop IDE plugin. It reads local files. There is nothing to host. |
| **Payments, licensing, trials** | **$0 and handled by JetBrains** | JetBrains processes the sale, manages licences, keys, trials and refunds. |
| **VAT, sales tax, invoicing, compliance** | **$0 and handled by JetBrains** | JetBrains is the merchant of record. You never touch a tax authority. |
| Bank / payout | **$0** | Payouts via the marketplace's own rails. |
| **Your take** | **85%** | JetBrains keeps 15%; the rest is yours. |
| Code signing | **$0** | The platform handles it. |
| CI | **$0** | GitHub Actions free tier is far more than enough for a weekly `verifyPlugin`. |
| Domain | optional | You do not need one. |
| Customer support | your time | Realistically a few hours a month at this scale. |

**Running cost: ~$0/year.** The only genuine cost is your two weeks and the ongoing maintenance obligation that §4.12 is about.

**The one thing that would cost money is exactly the thing §1.3 excludes.** An AI feature means an API bill per user and a server per user — the fastest possible way to turn a $200/yr product into a liability. **Do not add one to v1.** `LogLens` and `LogCraft` both correctly frame themselves as *"local-first"*; keep that property.

<a name="10--risks-and-open-questions"></a>
## §10 — Risks and open questions

Ordered by how much they should worry you. **Items 2, 3, 9 and 11 are the ones that changed in the last 48 hours.**

### 10.1 🔴 The market may simply be too small

**The evidence:** the biggest third-party log viewer ever built is `Awesome Log Viewer` at **76,049 downloads in 0.7 years** (§4.9) — and it needed the .NET/Application Insights/OpenTelemetry lane to get there. The only *paid* pure log-file viewer is `.log` at **16,379 downloads lifetime** (§4.8). Half of all paid JetBrains plugins earn about **$1,340 lifetime** (§9.1).

**Why it's not fatal:** a plugin can be small and still worth two weeks, and the reputation argument in §9.3 is real. **Why it is still the #1 risk:** two weeks of your attention is the only scarce resource you have, and this is the *same order of magnitude* as the Perforce project, not a step up. If you are looking for a project that pays the rent, this is not it, and no amount of execution changes that.

### 10.2 🔴 The niche is being colonised right now, including yesterday

**Seven plugins in the last 15 weeks, two verified vendors, one published 2026-09-30** (§0). `Twilight Ventures` is running a six-plugin portfolio play (§4.3); `Veryation` shipped a direct competitor's free tier with a Pro tier already announced **one day ago** (§4.4).

**Why it's not fatal:** their claims are *unproven* — `LogLens` has **0 reviews**, all six Twilight plugins have **2 reviews combined**, and the most feature-complete entrant of the last four months has one review reading *"Useless, just a placeholder"* (§4.5). In this space, a shipped feature list has so far predicted nothing.

**The uncomfortable implication:** you are not entering an empty room. You are entering a room where five other people ran in during the last two weeks, and **the winner will likely be decided by who is still maintaining in 2028**, not by who ships first this month.

### 10.3 🟠 My own measurement error — the SQL-log seat I said was open is not open

**This is the most important correction in this document and it is mine.**

I screened 26 keywords across 334 plugins and concluded that **"no plugin does framework-generic log → runnable SQL with parameters substituted"**, and I made that a headline claim. **The screen was wrong.** Plugins in that job are **named after the framework** — `mybatis` — so they do not rank for `sql log` or `log to sql`. Re-searching by framework name found **at least nine plugins**:

| Plugin | id | Model | Downloads | Reviews | Last update |
|---|---|---|---|---|---|
| `MyBatisCodeHelperPro` | 9837 | FREE | **1,434,574** | 197 | 9 days ago |
| `MyBatis Log` | 13905 | **PAID** `PMYBATISLOG` | **631,753** | 23 | 112 days ago |
| `MyBatis Log Free` | 17898 | FREE | **549,732** | 16 | 66 days ago |
| `MyBatisCodeHelperPro (Marketplace Edition)` | 14522 | **PAID** `PMYBATISHELPER` | **231,777** | 28 | 9 days ago |
| `MybatisLogFormat` | 14292 | FREE | 182,372 | 16 | 74 days ago |
| `Mybatis Smart Code Help Pro` | 18389 | FREEMIUM | 73,478 | — | **368 days** |
| `JPA SQL` | 15242 | FREEMIUM `PJPASQL` | 24,234 | 2 | **899 days** |
| `Log SQL Converter` | 28590 | FREE | 608 | 1 | 355 days ago |
| **`SqlLogLens`** | **34505** | FREEMIUM `PSQLLOGLENS` | **12** | **0** | **7 days ago** |

`Log SQL Converter`'s description: *"Multi-framework log adaptation: Automatically identify and parse SQL templates/parameters in JPA (Hibernate) and MyBatis logs… Intelligently replace '?' placeholders to generate complete, directly runnable SQL statements… One-click copy…"* — **a generic multi-framework converter, free, published a year ago, 608 downloads.**

**Three conclusions, and they are the reason §4.6 matters:**
1. **The SQL-log job is roughly 10× the market of the general log-viewer job** — the MyBatis family alone is ~2.5M downloads vs ~250k for general viewers.
2. **It is defended.** A free plugin with 1.43M downloads owns it, two paid plugins with 631k and 231k downloads sell into it, and there is a free generic converter that nobody uses.
3. **The only un-served micro-gap was "formatted multi-line SQL, no config change" — and `SqlLogLens` claimed exactly that, seven days ago, with 12 downloads.**

**What I should have done:** searched by **framework name**, not by **abstract job name**. Wherever a job is industry-attached to a specific technology, the plugins will be named after that technology, and a job-vocabulary search will return zero and look like an open field. **This error is fixed in this document and it is recorded so that the same mistake is not repeated.** The SQL wedge is dead; §1.3 stays a viewer.

### 10.4 🟠 Free tools already do most of the paid plan

`.log` is a **paid** plugin whose advertised feature set — colouring, hyperlinks, JSON destructuring, epoch conversion, autodetection, ANSI, large files, live tail (§4.8) — is essentially **the entire §1.3 free tier.** And `Awesome Log Viewer` is selling exactly "structured views + waterfall", which is where the Pro tier was heading (§4.9).

**Mitigation:** Pro must be something the free tier genuinely cannot do **on one file** — multi-file interleaving, saved formats, export — and the free tier must never be crippled. A crippled free tier in a market this small produces an angry review, and angry reviews are the only reviews this market reliably produces.

### 10.5 🟠 The incumbent is bundled and free, so you cannot out-distribute it

`Ideolog` is pre-installed on millions of machines and customers cannot easily remove it. **You cannot win on reach, only on quality** — and quality is exactly what the last seven entrants have failed to prove (§10.2).

### 10.6 🟠 A free hit does not convert automatically

`Markdown Navigator` has **5,392,215 downloads** and is **still free**, with a live product code and a listing that has not been updated in years. Millions of happy users did not become paying customers. **Popularity in the JetBrains Marketplace and revenue are only loosely coupled**, and the coupling is weakest for exactly the kind of utility this project is.

### 10.7 🟡 Version rot is the real killer — and it will kill *you* too if you stop

§4.12 is the evidence: `Json Helper` (286,044 DL, 44×★5) nearly died on a version bump. `MyBatis Log Free`'s customers wrote *"Are you still maintaining this? Doesn't work on the newest IDEA. It's been a month. I had no choice but to use another plugin."* Six plugins in this space are **899–3,601 days** stale.

**This is a risk in both directions.** It is the reason a new entrant *can* win (everyone else rots) and it is the reason *you* will lose (everyone else rots, and so will you, the first time work gets busy). **The mitigation is §8.1: open `untilBuild` plus a weekly CI verification job. That converts an existential risk into a Monday-morning email.** If you are not willing to run that job for five years, do not start this plugin.

### 10.8 🟡 JetBrains could fix `Ideolog` at any time

The complaints are 6–7 years old and the plugin keeps shipping, so this is unlikely. But `Ideolog` is first-party, **and its API `cdate` has been reset by a mass rebuild**, which means JetBrains touched it recently (§4.1). If they fix silent failure and read-only in a future release, the §5 specification loses about half its value — and you will have built a product whose differentiation JetBrains can erase with a maintenance patch. **No mitigation exists. This is the standard risk of building against a first-party incumbent's defect.**

### 10.9 🔴 You still have no audience — and that is the bottleneck, not product quality

This is the one that actually killed the last launch, and the evidence in this document points at it repeatedly:

- `Remote Log Tail` (id 31690) shipped a **better, more thoughtful free product than this charter specifies** — SSH tail, structured queries, merged timelines, clickable stack traces, SFTP, snapshot export — in 2026, and it has **318 downloads and one review** (§4.10).
- `LogParser Pro` shipped a **longer free feature list** and has **2,951 downloads and a one-star review** after 109 days (§4.5).
- `LogLens` shipped yesterday. It has 106 downloads, which is what one day of a vendor's own traffic looks like.

**Zero audience is not a promotion problem that a good product solves.** A marketplace listing is not a distribution channel; it is a shelf. Publishing into silence produces silence, which is exactly what happened with `periodictable.lol`. **The realistic plan is §8's day 14: go to where developers are already asking about log files and be genuinely useful there, with the plugin as the answer to the question actually asked. Anything else is hoping.**

### 10.10 🟡 The free tier's licence terms have not been read

The Perforce sibling project has this risk too. Here it is smaller — this plugin depends on **no vendor's free tier**. The one open question is whether shipping a plugin that does a job the bundled `Ideolog` does violates any marketplace policy, which §7.5 asks you to check. **Low risk, unread, and cheap to confirm.**

### 10.11 ⚠️ Unresolved — is the "verified vendor" badge required to charge money?

`LogParser Pro`, `LogLens`, all six Twilight Ventures plugins, `.log`, and `MyBatis Log Free` are **`verified=True`**; `MyBatis Log` (kookob) and `Remote Log Tail` are **not verified, and `MyBatis Log` still sells.**

**Reading:** the badge is probably opt-in and not a licence prerequisite for a paid listing. ✅ **Confirmed mandatory regardless: the trader-status declaration** (required by EU consumer law for anyone selling). ⚠️ **The JetBrains developer agreement could not be read** — the page is client-rendered and returns no text. **This is an honest gap: confirm it in the listing form, or with JetBrains support, before you plan a price.**

### 10.12 ⚠️ Unresolved — is `PLOGSMITH` available as a product code?

The API exposes **no code registry**, and the local paid-plugins dataset carries **no product codes**, so uniqueness could not be checked. Observed codes in this space: `PLOG`, `PLOGLENS`, `PSQLLOGLENS`, `PLOGPARSERPRO`, `PMYBATISLOG`, `PMYBATISHELPER`, `PJPASQL`, `PJTRACKER`, `PEXTRAICONS`. **`PLOGSMITH` was not observed, which is not a guarantee.** Uniqueness is enforced by the listing form itself, so the failure mode is a minor annoyance at listing time, not a legal problem.

### 10.13 ⚠️ Unresolved — every conversion number in §9 is an assumption

**Trial→paid conversion is structurally unmeasurable in advance.** No public data exists per plugin; the 5% / 6.3% figures are the dataset author's retracted-and-restated estimates, not measurements. **§9's revenue figures should be treated as order-of-magnitude reasoning, not forecasts.** The only way to know is to run a paid tier and watch.

### 10.14 ⚠️ Unresolved — the name's collisions outside the JetBrains Marketplace were never checked

§6.2 verifies the name against the JetBrains Marketplace, which is the only place a collision is fatal. **`LogSmith` has not been checked against GitHub, npm, or the open web**, where a prominent same-named project would outrank the plugin in search and make it unfindable. Five minutes before you commit the name.

---

<a name="11--how-to-resume-on-another-machine"></a>
## §11 — How to resume on another machine

You are reading this on a different machine, months later, with no context. Here is the whole state of the project:

### 11.1 Where things stand

| | |
|---|---|
| **Code written** | **None.** This repository is a charter, not a plugin. |
| **The idea** | A zero-configuration log-file viewer for JetBrains IDEs, name `LogSmith`. |
| **The next action** | **§7 — the Day-0 gate. Two hours. Run it before writing anything.** |
| **The gate can kill it** | Yes. Read §7.4 literally. A documented no-go is a good outcome. |
| **Evidence date** | **2026-10-01.** Everything in §3 and §4 was read live that day. |
| **Sibling project** | `Perforce-IntelliJ-Integration` — a Perforce/Helix Core integration for JetBrains. **Do not modify that repository from here.** |

### 11.2 The 10-minute restart

1. **Re-read §7.**

2. **Re-verify that the ground has not moved again** — this is the single most important 3 minutes of the restart, because five plugins appeared in the six weeks before this document was written:

   ```bash
   # Newest log-space plugins (works anywhere with python3 + network)
   curl -s "https://plugins.jetbrains.com/api/searchPlugins?search=log%20viewer&max=20&orderBy=newest" \
     | python3 -c "import sys,json;[print(p['id'],p['name'],p['downloads']) for p in json.load(sys.stdin)['plugins']]"

   # Does LogLens have reviews yet?  (33263)
   curl -s "https://plugins.jetbrains.com/api/plugins/33263/rating"
   curl -s "https://plugins.jetbrains.com/api/plugins/33263/comments?size=50"

   # And SqlLogLens? (34505)
   curl -s "https://plugins.jetbrains.com/api/plugins/34505/rating"
   ```

   **If `LogLens` now has reviews and they are positive, the wedge is closed — stop, and go and finish Perforce.** If they are negative, read every word: their complaints are your specification, exactly as `Ideolog`'s 53 one-stars are §5.

3. **Then read §5.8 and start on R1/R2/R4/R6/R8**, which together are three and a half days and the entire competitive advantage.

4. **Do not build the Pro tier, a dashboard, or anything with AI in it.**

### 11.3 What "done" looks like for this repository

This repo stops being a charter and becomes a plugin the day `src/main/kotlin` exists. At that point:

- Move this file to `docs/charter.md` and write a **user-facing** `README.md` — what the plugin does, how to install it, and the four screenshots from §6.3. **Do not leave a market-analysis document as the repository front page of a shipped product.**
- Add `docs/day0-gate.md` with the §7.6 decision record.
- Add the `verify.yml` from §8.1 **on day one**, not on the day it first breaks.

### 11.4 The rules this project was built on (keep them)

1. **No claim the code does not support.** No "AI", no "enterprise", no "powerful", no "seamless" (§8, day 12).
2. **Never fail silently.** Ever, for any reason, in any code path (§5.1).
3. **Never break the user's file.** Not read-only, not modified, not intercepted (§5.2).
4. **Never make anything worse.** If the highlighter has nothing to add, do nothing (§5.4).
5. **Every registered plugin exists** — no dead menu items, no placeholder features. `LogParser Pro`'s only review is *"Useless, just a placeholder"* and it is the most expensive sentence in this document (§4.5).
6. **The free tier is the product.** Pro is for people who already got value for free.

---

<a name="appendix-a--marketplace-api-recipes-and-traps"></a>
## Appendix A — Marketplace API recipes and traps

Every number in this document came from these endpoints. **These recipes are for you, on the next machine, running this document's own re-verification in §11.2.**

### A.1 Search plugins

```
GET https://plugins.jetbrains.com/api/searchPlugins?search=<query>&max=<n>&orderBy=<field>&offset=<k>
```

- **`offset` works.** `max` must stay **≤ 20** — `max=30` returns **HTTP 400**. `page` is **ignored**.
- Returns a **dict**, not a list: `{"plugins": [...], "total": N}`. **`total` caps at 10,000.**
- Available fields per plugin: `cdate`, `downloads`, `hasSource`, `icon`, `id`, `link`, `name`, `preview`, `previewImage`, `pricingModel`, `rating`, `tags`, `vendor`, `xmlId`.
- ⚠️ **`tags` is a list of dicts with a `name` key**, not a list of strings. `",".join(plugin["tags"])` raises `TypeError: expected str instance, dict found`. Use `",".join(t["name"] for t in plugin["tags"])`.

### A.2 Rating histogram — the only real sentiment signal

```
GET https://plugins.jetbrains.com/api/plugins/{id}/rating
→ {"votes": {"5": 8, "4": 8, "3": 9, "2": 9, "1": 53}, "meanVotes": 2, "meanRating": 4.0643}
```

- **`votes` is the real histogram.** Use it. All of §3.3, §3.4, §3.6 and Appendix B comes from this field.
- ⚠️ **`meanRating` is the literal constant `4.0643` for every plugin in the marketplace**, and **`meanVotes` is always `2`.** Both are worthless. Do not plot them, do not quote them.
- A plugin with no ratings returns `votes: {}` — **not** a 404. Handle the empty dict.

### A.3 Review comments

```
GET https://plugins.jetbrains.com/api/plugins/{id}/comments?size=<n>
```

- ⚠️ The text field is **`comment`** (HTML), **not `text`**. It contains markup and must be stripped.
- ⚠️ **`rating: 0` means "comment with no star rating" — it is NOT one star.** Filtering `rating == 1` will undercount complaints. `MyBatis Log` shows 23 rated votes but has many more comments, and its activation-failure complaints are almost all `rating: 0` (§10.3).
- ⚠️ **Comment dates are broken** — `cdate` renders as strings like *"56.7y ago"*. **Reviews cannot be reliably dated.** Do not try.
- ⚠️ **The histogram understates.** §3.3 measures *rated votes*; the true complaint volume is higher.

### A.4 The mean-vs-histogram trap

Two different means exist and they do not agree:

- `searchPlugins.rating` is the **UI mean** — it includes star ratings cast without a written comment.
- The `/rating` histogram mean counts **only ratings attached to reviews**.

They agree when votes are numerous (`Bitbucket Integration Pro` 4.76 both; `Ideolog` 1.95 histogram vs 2.00 UI). They diverge when votes are few: **`.log` (25828) shows a histogram mean of 5.0 from its single vote, while `searchPlugins.rating` reports 3.79.** And `GET /api/plugins/9746` reports `rating: 0.00` while `searchPlugins` reports `2.00` for the same plugin.

**Rule: read the mean from `searchPlugins`, or compute it from the histogram, and always report which one you used.** Review counts below ~5 should not be used to rank anything.

### A.5 Plugin details and the paid-listing signal

```
GET https://plugins.jetbrains.com/api/plugins/{id}
→ purchaseInfo: {"productCode": "PLOGLENS", "buyUrl": null, "purchaseTerms": null, "optional": false, "trialPeriod": 30}
```

- ⚠️ **`buyUrl` is *always* `null`. There are no price fields anywhere in the API.**
- **A non-null `productCode` is the only signal that a paid listing is live.** It is also how you discover a competitor's trial length.
- There is no endpoint that lists product codes, so **you cannot check whether a code is free** (§10.12).

### A.6 Timestamps

- ⚠️ **`cdate` is last-updated, not created.** `searchPlugins.cdate` and `/api/plugins/{id}.cdate` **agree** for third-party plugins, so it is usable — but it answers "when did this last change", never "when did this launch".
- Python 3.14 removed/warns on `datetime.utcfromtimestamp()`; use `datetime.fromtimestamp(ts, datetime.UTC)`.
- Reference conversions verified **2026-10-01** (this is how the "1 day ago" / "7 days ago" labels in §0 were derived):

  | Plugin | ms | Date |
  |---|---|---|
  | `LogLens` | 1790780275000 | **2026-09-30** |
  | `SQLite Lens` | 1790358096000 | 2026-09-25 |
  | `XLSX Lens` | 1790271452000 | 2026-09-24 |
  | `JSONL Lens` | 1790204769000 | 2026-09-23 |
  | `LogCraft` | 1789960450000 | 2026-09-21 |
  | `Remote Log Tail` | 1788783565000 | 2026-09-07 |
  | `LogParser Pro` | 1781438490000 | **2026-06-14** |

  **Today is 2026-10-01.** Any "days ago" figure in this document is relative to that date.

### A.7 The first-party timestamp trap

⚠️ **`cdate` is unreliable for JetBrains' own plugins, because JetBrains mass-rebuilds them.** `Ideolog` shows `cdate` = "0.0y" (appears freshly updated) while its dominant complaints are 6–7 years old and still present in the reviews. **Do not read a recent `cdate` on a first-party plugin as evidence that its bugs were fixed.** Check the review text instead.

### A.8 The name-collision recipe used for §6.2

For each candidate name, run an exact search and count results:

```bash
for n in "LogSmith" "Log Smith" "LogAtlas" "Log Lens"; do
  t=$(curl -s "https://plugins.jetbrains.com/api/searchPlugins?search=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$n")&max=20" \
      | python3 -c "import sys,json;print(json.load(sys.stdin).get('total',0))")
  echo "$n -> $t"
done
```

**`total=0` means no plugin carries that name.** A small non-zero total must be inspected by eye, because the marketplace matches substrings — `LogSmith` returning 0 is meaningful; `Log` returning 815 is not.

⚠️ **This checks the JetBrains Marketplace only.** It says nothing about GitHub, npm, or the web (§10.14).

### A.9 Fetch plugin descriptions

`GET /api/plugins/{id}` returns `description` as **HTML** (with `<p>`, `<ul>`, `<code>`). Strip tags before quoting, and **keep the quoting verbatim** — every quoted release note in §0 and §4 is copied character-for-character from this field.

### A.10 Things the API does not tell you

- **Number of installs currently active** (only cumulative downloads).
- **Revenue, sales, or conversion**, for anyone.
- **Uninstall rate.** For `Ideolog` this is the number that actually matters and it does not exist.
- **Whether a plugin still works on the current IDE** — no compatibility field per user, only the declared `sinceBuild`/`untilBuild`.
- **Whether two plugins are by the same person** unless they share a vendor string (`Twilight Ventures` does; `abc` and `kookob` turn out to be the same vendor across `MyBatis Log`, `JPA SQL` and `Toolset` — a fact only visible in `purchaseInfo`/vendor strings, not in the UI).

---

<a name="appendix-b--plugin-id-reference-table"></a>
## Appendix B — Plugin-ID reference table

Deep-link any of these with `https://plugins.jetbrains.com/plugin/<id>`. All figures read live **2026-10-01**.

### The incumbent and the control

| id | Plugin | Vendor | Model | Downloads | Reviews | ★1% | Mean |
|---|---|---|---|---|---|---|---|
| **9746** | **Ideolog** | **JetBrains** | FREE (bundled) | 13,005,238 | 87 | **60.9%** | 1.95 |
| 7125 | Grep Console | **Vojtěch Krása (solo)** | FREE | 3,368,597 | **112** | **0.0%** | 4.94 |
| 9707 | ANSI Highlighter Premium | Ahmed Layouni | **PAID** `PANSIHIGHLIGHT` t10 | 1,552,351 | 35 | **0.0%** | 4.89 |

### The 2026 entrants — the reason §0 exists

| id | Plugin | Vendor | Model | Downloads | Reviews | Published |
|---|---|---|---|---|---|---|
| **33263** | **LogLens** | **Veryation** ✅ | FREEMIUM `PLOGLENS` t30 | **106** | **0** | **2026-09-30** |
| **34505** | **SqlLogLens** | zuhaib ✅ | FREEMIUM `PSQLLOGLENS` t30 | **12** | **0** | **2026-09-24** |
| 34397 | LogCraft | logcraft | FREE | 17 | 0 | 2026-09-21 |
| 29050 | LogParser Pro | jakub-jirak ✅ | FREEMIUM `PLOGPARSERPRO` t7 | 2,951 | 1 (★1) | 2026-06-14 |

### The Twilight Ventures portfolio (all ✅ verified, all FREEMIUM)

| id | Plugin | productCode | Downloads | Reviews |
|---|---|---|---|---|
| 33684 | SQLite Lens | `PTVSQLITELENS` | 3,078 | 2 |
| 33721 | XLSX Lens | `PTVXLSXLENS` | 1,665 | 0 |
| 33397 | JSONL Lens | `PJSONLLENS` | 959 | 0 |
| 33811 | Notebook Lens | `PTVNOTEBOOKLENS` | 950 | 0 |
| 33510 | Parquet Lens | `PTVPARQUETLENS` | 753 | 0 |

### The log-file viewers

| id | Plugin | Vendor | Model | Downloads | Reviews | ★1% |
|---|---|---|---|---|---|---|
| 13984 | Application Insights Debug Log Viewer | 3rd party | FREE | 462,764 | 23 | 0.0% |
| **27750** | **Awesome Log Viewer** | socolin ✅ | FREEMIUM `PAWESOMELOGVIEW` | **76,049** | 3 | 33.3% |
| 24693 | Pretty JSON Log | 3rd party | FREE | 34,516 | 9 | 0.0% |
| 24675 | HotSpot Crash Examiner | JetBrains | FREE | 3,916 | 0 | — |
| 10015 | Log Viewer (Android logcat) | 3rd party | FREE | 21,552 | 2 | 0.0% |
| 9417 | Log Support 2 | 3rd party | FREE | 18,974 | 1 | 0.0% |
| **25828** | **.log** | weirddev ✅ | **PAID `PLOG`** | **16,379** | **1** | — |
| 12083 | Structured Logging | 3rd party | FREE | 12,961 | 0 | — |
| 21675 | LogConsole | 3rd party | FREE | 9,744 | 5 | 0.0% |
| 18952 | Spiderlog | 3rd party | FREE | 2,647 | 0 | — |
| **31690** | **Remote Log Tail: SSH Server Log Explorer** | philz_dev ❌ | FREE | **318** | **1 (★5)** | — |

### The SQL-log family (§10.3)

| id | Plugin | Model | Downloads | Votes | Last update |
|---|---|---|---|---|---|
| 9837 | MyBatisCodeHelperPro | FREE | **1,434,574** | 197 | 9 days |
| **13905** | **MyBatis Log** | **PAID `PMYBATISLOG` t30** | **631,753** | 23 | 112 days |
| 17898 | MyBatis Log Free | FREE | **549,732** | 16 | 66 days |
| 14522 | MyBatisCodeHelperPro (Mkt Edition) | **PAID `PMYBATISHELPER` t30** | **231,777** | 28 | 9 days |
| 14292 | MybatisLogFormat | FREE | 182,372 | 16 | 74 days |
| 18389 | Mybatis Smart Code Help Pro | FREEMIUM | 73,478 | — | **368 days** |
| 12449 | MyBatis Builder | FREEMIUM | 78,827 | — | 74 days |
| 9258 | Mybatis Log | FREE | 69,737 | — | **3,601 days** |
| 18429 | MyBatis Log Plus | FREE | 65,146 | 9 | — |
| 21381 | MyBatis Log EasyPlus | FREE | 64,263 | 5 | **1,138 days** |
| 14608 | SQL Params Setter | FREE | 29,811 | 0 | **2,001 days** |
| 14530 | mybatis-log | FREE | 27,493 | 9 | **1,469 days** |
| 15242 | JPA SQL | FREEMIUM `PJPASQL` t30 | 24,234 | 2 | **899 days** |
| 24694 | JTracker: MyBatis Log & JPA Log | FREEMIUM `PJTRACKER` t14 | 2,387 | 0 | **817 days** |
| 22306 | JPA SQL LOG | FREE | 905 | 0 | **1,137 days** |
| 28590 | Log SQL Converter | FREE | 608 | 1 | 355 days |
| **34505** | **SqlLogLens** | FREEMIUM `PSQLLOGLENS` t30 | **12** | **0** | **7 days** |

### The paid-model validation (§3.6)

| id | Plugin | Vendor | Model | Downloads | Reviews | ★1% |
|---|---|---|---|---|---|---|
| 11058 | Extra Icons | **JONATHAN_LERMITAGE (solo)** | **PAID** `PEXTRAICONS` t7 | 1,397,308 | 110 | **0.0%** |
| 10650 | Advanced JSON Studio | Godwin Joseph | FREEMIUM `PJSONPARSERCODE` t7 | 1,456,434 | 549 | 0.9% |
| 13538 | Bitbucket Integration Pro | Majera | PAID | 271,249 | 491 | 1.4% |
| 18689 | JetLab — Integration for GitLab | Majera | PAID | 198,861 | 254 | 0.4% |
| 16988 | Fast Request – API Buddy | kings1990 | PAID `PFASTREQUEST` t30 | 313,951 | 212 | 0.5% |
| 14384 | Toolset | kookob | FREEMIUM `PTOOLSET` t30 | 101,899 | 50 | 2.0% |
| 7499 | GitToolBox | **LukaszZielinski (solo)** | FREEMIUM | 10,541,342 | 146 | 3.4% |

### The version-rot evidence (§4.12)

| id | Plugin | Downloads | Reviews | ★5 | ★1 |
|---|---|---|---|---|---|
| **13873** | **Json Helper** | **286,044** | **50** | **44** | **2** |

---

<a name="appendix-c--verbatim-review-evidence"></a>
## Appendix C — Verbatim review evidence

Everything in §5 is quoted from `GET /api/plugins/9746/comments` on **2026-10-01**. Reviewers are identified by the marketplace's own handles, exactly as shown.

### C.1 Silent pattern failure (~25 of 53 one-stars)

| Reviewer | Review |
|---|---|
| `martin.goldhahn.1` | *"I wrote a regexp that works anywhere else but ideolog. This tool is essentially useless."* |
| `jpinto` | *"Just lost 20 minutes with this and my logs are still plain white text… I've double checked my regex on regex101."* |
| `sirlordmikey` | *"Couldn't get it to work on even the most trivial regex, even though the Find dialog showed 100% match."* |
| `stradivari1390` | *"Despite setting up patterns correctly, the plugin blatantly fails to acknowledge them."* |
| `martin.peterka` | *"Log format not recognized."* |

### C.2 Read-only files (~7)

| Reviewer | Review |
|---|---|
| `tjohns92109` | *"it does one thing… it makes it so you can't edit, delete, or manipulate log files in any way. So it does do something… something bad."* |
| `Alexander_Khmelnitskiy` | *"It's been over 7 years and the problem is still here."* |
| `jakekoekemoer` | *"Can't clear the log on a LOCAL DEV server because its 'READ ONLY'."* |

### C.3 Freezes and crashes (~6)

| Reviewer | Review |
|---|---|
| `Michaël_Dieudonné` | *"I had to disable the plugin because it freezes the IDE. Took a month to pinpoint."* |
| `gedealdhi26` | *"Crashed my PHPStorm when opening laravel.log files."* |
| `Diego_Marcolungo` | *"Doesen't open very large log file"* |

### C.4 Worse than nothing — the most damning pair on the page

| Reviewer | Review |
|---|---|
| `hallenfur` | *"Opened a .log file without the plugin. It was beautifully syntax highlighted. Got the suggestion to install the plugin, and I did. After that, nothing is highlighted, all is gray. Uninstalled the plugin, and everything looks good again."* |
| `bradley.hayes` | *"it provides nothing and actually removes existing highlighting"* |

### C.5 Configuration — users wrote the spec

| Reviewer | Review |
|---|---|
| `7killua` | *"The good experience: to have already preconfigured log regex with default colors and let the user change them."* |
| `stevejluke+jet` | *"Changes are only applied after you restart the IDE."* |
| `mac671` | *"Cannot copy and paste regex patterns into the configuration window."* |

### C.6 Unhandled realities

| Reviewer | Review |
|---|---|
| `kpervin` | *"This does not escape any ANSI color codes. It also doesn't work with Jetbrains' own Remote File Systems plugin."* |
| `orman iec` | *"Works on windows, does not work on Linux? well."* |
| `henningsprang` | *"the scrollbar is broken since 6 YEARS AGO"* |
| `Ante_Lucic` | *"Why package this with PHPStorm and not allow to disable it completely?"* |

### C.7 The two five-star reviews — the satisfied customer's complaint

| Reviewer | Review |
|---|---|
| `vitorhugods.1` | *"Does its job. Hard to configure, sure."* |
| `jguidos` | *"it's not for noobs, you have to read the documentation in GITHUB and to have a deep knowledge about regular expressions. Only took me 20 min."* |

### C.8 The 2026 entrants' own reviews — the live signal to re-check (§11.2)

| Plugin | Reviewer | Review |
|---|---|---|
| `LogParser Pro` (29050), ★1 | `leandro.bortoletto` | *"Useless, just a placeholder, not a real working plugin"* |
| `Remote Log Tail` (31690), ★5 | — | *"Where were you 5 years ago?! This is exactly the log management tool I've been waiting for. Completely game-changing. Just one question: why on earth does it only support IDEA?"* |

### C.9 Version rot — `Json Helper` (13873)

| Reviewer | Review |
|---|---|
| `brad.8`, ★1 | *"Still not working. I think the author is no longer around or just too busy to update this… Would be amazing to get the source code so we can fix the problem."* |
| — | *"The 2025 version of the idea is reporting an error"* |
| — | *"2025无法使用,期待更新"* — "unusable on 2025, hope for an update" |
| — | *"ERROR com.intellij.diagnostic.PluginException: Cannot init toolwindow"* |
| — | *"I lost all hope to see it updated for latest Idea, but it is back, finally!"* |

### C.10 Version rot — `MyBatis Log Free` (17898)

| Reviewer | Review |
|---|---|
| `zhangmrit` | *"还在维护吗？最新版idea无效，已经个把月了，不得已使用了别的插件，但是还习惯了这个插件"* — **"Are you still maintaining this? Doesn't work on the newest IDEA. It's been a month. I had no choice but to use another plugin, but I'm used to this one."** |

### C.11 Activation failure on a *paid* plugin — `MyBatis Log` (13905)

These are comments with **`rating: 0`** (no star attached), which is why they do not appear in the 23-vote histogram (§A.3). All verbatim:

| Reviewer | Comment |
|---|---|
| — | *"别买，买了用不起。还没地方退款"* — **"Don't buy it. I bought it and can't use it. There's nowhere to get a refund."** |
| — | *"购买之后无法激活，什么破玩意儿，白花了"* — "Can't activate after purchase. What a piece of crap, wasted money." |
| — | *"激活后，tools里面没有选项，买了个寂寞，用不了。浪费半天时间"* — "After activation there's no option in Tools. Bought nothing. Can't use it. Wasted half a day." |
| **author** | *"我是作者。激活失败的，可以尝试使用激活码激活"* — "I'm the author. If activation failed, try activating with a licence key." |
| — | *"买的时候成10美元了，有点贵啊"* — "It cost $10, a bit expensive." |

**Trial expiry observed as low as 14 days on `JTracker` (24694) and 7 days on `LogParser Pro`.**

---

<a name="appendix-d--provenance-and-a-note-on-what-is-and-is-not-proven"></a>
## Appendix D — Provenance, and a note on what is and is not proven

### D.1 Source of every number

| Source | What it provided | Date |
|---|---|---|
| `plugins.jetbrains.com/api/searchPlugins` | every download count, `cdate`, `pricingModel`, `vendor` string, result totals in §4.13 | 2026-10-01 |
| `plugins.jetbrains.com/api/plugins/{id}/rating` | **every histogram in §3 and Appendix B** | 2026-10-01 |
| `plugins.jetbrains.com/api/plugins/{id}/comments` | **all of §5 and Appendix C**, and the activation failures in C.11 | 2026-10-01 |
| `plugins.jetbrains.com/api/plugins/{id}` | descriptions (§0, §4), `purchaseInfo`/`productCode`/`trialPeriod` (Appendix A.5) | 2026-10-01 |
| `poko8725/jetbrains-marketplace-scan`, `data/*.json` | §9.1's download and price distributions (404 paid plugins, snapshot 2026-08-03) and the three author retractions | 2026-08-03 |
| `plugins.jetbrains.com/docs/marketplace/` | the paid-listing and publication requirements in §7.5 | 2026-10-01 |

**No figure in this document is quoted from memory, from a blog post, or from Product Hunt.**

### D.2 What is *proven*

- `Ideolog` (9746) has **60.9% one-star across 87 reviews**, and its 53 complaints fall into **six reproducible defect groups**, each quoted verbatim in §5 and Appendix C. *(Histogram + full review text.)*
- **Every independent third-party log-file viewer with reviews sits at ~0.0% one-star** (§3.3). *(Histograms.)*
- **Two solo developers** (`Vojtěch Krása`, `LukaszZielinski`, plus `JONATHAN_LERMITAGE` in a paid listing) hold **0.0%–3.4% one-star** records on plugins with **112, 146 and 110 reviews** respectively (§3.4, §3.6). *(Histograms.)*
- **Five plugins entered this space between 2026-09-21 and 2026-09-30**, three from verified vendors, with **Pro tiers already listed** (§0, §4.3, §4.4). *(cdate + descriptions + `productCode`.)*
- **The SQL-log job is served by 9+ plugins** including a 1.43M-download free leader and a free generic converter a year old (§10.3). *(Search + histograms + descriptions.)*
- **The biggest third-party log viewer is 76,049 downloads; the only paid pure log viewer is 16,379 lifetime** (§4.8, §4.9). *(Download counts.)*
- **Median paid JetBrains plugin: 1,411 downloads ≈ $1,340 lifetime** (§9.1). *(Third-party dataset.)*

### D.3 What is *not* proven — read this before you spend two weeks

- **That anyone will install this plugin.** No audience exists yet, and the last launch produced zero. §4.10's `Remote Log Tail` is the warning: a better product, free, with **318 downloads**.
- **That `LogLens` and the other entrants do not already solve the problem.** They have **zero reviews**, and this document's central claim — that the field is beatable — rests entirely on the fact that their superiority is *unverified*, not that it is false (§4.4).
- **That any of §9's revenue figures are achievable.** The conversion assumptions are third-party estimates (§10.13).
- **That the name `LogSmith` is free outside the JetBrains Marketplace** (§10.14).
- **That a free plugin can charge money without the verified-vendor badge** (§10.11).
- **That `PLOGSMITH` is an available product code** (§10.12).

### D.4 The standard this document was written to

The evidence chain above exists because the previous attempt — `periodictable.lol` — was built on no evidence at all, launched to no audience, and produced zero. **The rule is: no feature, no price, and no claim in this plugin that is not traceable to one of the tables in this document or to user feedback after release.**

Correspondingly, **this document contains three explicit corrections of my own earlier analysis** (§0 item 1, §0 item 2, §10.3), all of which made the opportunity *smaller*. They are kept in the document on purpose: **a charter with no retractions in it was not researched, it was rationalised.**

---

*Last verified against the live JetBrains Marketplace API: **2026-10-01**. Re-run §11.2 before writing any code — the ground has moved once already.*
