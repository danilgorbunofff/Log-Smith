# GUI pass — scorecard (fill this in, 40 minutes)

Instructions for a total beginner: do the steps, and after each step write your answer on the `YOUR ANSWER` line. When done, paste everything back into the chat.

---

## STEP 0 — Install LogLens (5 min)

1. Open **WebStorm** (search "WebStorm" in the Start menu).
2. Click **File** (top-left) → **Settings**. A window opens.
3. In that window, click **Plugins** in the left list.
4. Click the **Marketplace** tab at the top.
5. In the search box type: `LogLens`
6. Click **Install** on the LogLens card. Wait. Then click **Restart IDE** if a button appears.

**YOUR ANSWER 0:** Did it install without errors? (yes / no + what error)

---

## STEP 1 — Open the project folder (2 min)

1. **File** → **Open**.
2. Pick: `C:\Users\danil_gorbunov\Desktop\Projects\Log-Smith` → **OK**.
3. If a "Trust project?" popup appears → click **Trust Project**.
4. Look at the left panel (project files). You should see `testdata` with 4 files inside.

**YOUR ANSWER 1:** Can you see `testdata` with `big.log`, `docker.log`, `hibernate.log` in the left panel? (yes / no)

---

## STEP 2 — Open hibernate.log ZERO CONFIG (5 min)

1. In the left panel, double-click `testdata/hibernate.log`.
2. **Do not open any plugin settings. Do not configure anything.** Just look.

**YOUR ANSWER 2a:** What opened — a special log viewer with tables/buttons/filters, or a plain text file like in Notepad?

**YOUR ANSWER 2b:** Anywhere on screen (bottom bar, top bar, a popup, a banner) — does it SAY anything like "detected format: ..." or "pattern: ..."? What exactly does it say, word for word? If nothing — write "silence".

**YOUR ANSWER 2c:** Is the SQL block (the many lines `select / u1_0.id / from / where`) shown as ONE pretty block, or as many separate broken lines?

---

## STEP 3 — Open docker.log (5 min)

1. Double-click `testdata/docker.log`.

**YOUR ANSWER 3a:** Colors: do you see colored words (orange `36m`-style stuff is BAD) — or raw garbage like `←[36m` / `[38;5;196m` mixed into the text?

**YOUR ANSWER 3b:** Is there any color/filter/toolbar, or plain text?

---

## STEP 4 — Open big.log — THE 400 MB TEST (10 min)

1. Double-click `testdata/big.log`. Start your phone stopwatch NOW.
2. Wait until something usable appears. Watch the bottom-right of WebStorm — a progress bar may run.
3. If nothing happens after **3 minutes**, stop waiting. That's a fail.

**YOUR ANSWER 4a:** How long until you could actually use it? (seconds / 1-3 min / 3+ min / never)

**YOUR ANSWER 4b:** While it loaded, did WebStorm freeze (window goes white, "not responding" in title, mouse stuck)?

**YOUR ANSWER 4c:** Scroll to the very bottom. Did it let you? Is there a button like "tail" / "follow" / "scroll to end"?

**YOUR ANSWER 4d:** Is there a filter box? Type `ERROR` in it (if a filter exists). Did it filter?

---

## STEP 5 — Can you still EDIT the file? (5 min)

1. Close the log file tab (click the X on the tab).
2. Now: **File** → **Settings** → **Plugins** → **Installed** tab → click **LogLens** → toggle it **OFF** (uncheck Disable → confirm) → restart if asked.
3. Double-click `hibernate.log` again — this is now the PLAIN WebStorm editor.
4. Click inside the text. Type `HELLO TEST`. Press **Ctrl+S**.

**YOUR ANSWER 5:** Did it let you type? Did it save? Or does it say "read-only"? (If the file opened read-only / had a lock icon even BEFORE you disabled the plugin, write that instead.)

---

## STEP 6 — Put LogLens back

1. **File** → **Settings** → **Plugins** → **Installed** → LogLens → enable it again.

**YOUR ANSWER 6:** done (yes/no)

---

## THE VERDICT RULE (I compute this, you don't)

- If EVERY answer was perfect (fast 400MB load, colors rendered, SQL merged, a visible "detected format" message, and file stayed editable) → project is DEAD, we stop today.
- ANY imperfection (silence about detection, read-only takeover, garbage ANSI, freeze) → the project LIVES and we start building Day 1.

Paste your 0–6 answers back in the chat. That's the whole job.

---

# RESULTS — filled 2026-10-01 (LogLens free tier, WebStorm 2025.3.2)

**0 — install:** installed without errors.

**1 — project:** `testdata` visible with all 4 files.

**2a — what opened (hibernate.log):** a real log viewer (`Log` tab): Filter + Exclude boxes, Analyze, Views, JSON checkbox, `All levels` dropdown, Tail checkbox. Bottom tabs: `Log` / `Text` (Text = plain editor view with line numbers). Status bar: `29 lines · LogLens`.

**2b — visible detection statement:** **SILENCE.** Nothing anywhere says what format was detected — no format name, no match ratio, no notice. Status bar shows a line count + the plugin/tier name. → axis A intact.

**2c — multi-line SQL:** merged into ONE event, correctly indented under its header line; stack trace also merged. Detection is *correct*. → parsing is table stakes.

**3a — ANSI (docker.log):** **NOT rendered.** Blue banner: *"Plugins supporting ANSI codes found. — Install plugins / Ignore extension"*. The `[TRUECOLOR]` line rendered as plain text; only LogLens's own level-word coloring (red ERROR / orange WARN) appears. Raw `ESC[…m` garbage visible in the `Text` tab. → axis F visible failure; validates R9.

**4 — big.log (400 MB / 3,225,600 lines):** fast load, no freeze — "all scrolls easily and fast loaded". Tail checkbox present; filter box present (explicit ERROR-filter run not recorded). `Text` fallback for the big file = WebStorm's built-in "Large File Editor". → axis C conceded to LogLens.

**5 — editability:** **PENDING** — screenshot/answer not delivered. One 30-second check remains: with LogLens ENABLED, open the `Text` tab of `hibernate.log`, click in the text, type `HELLO TEST`, press `Ctrl+S`.

**6 — re-enable:** done.

**Verdict:** LogLens free tier is NOT perfect (silent detection + ANSI punt) → kill criterion §7.4(a) NOT triggered → **GO-NARROWED CONFIRMED**.

