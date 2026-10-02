# Day 12 — Diagnostics, error handling, and the honesty pass

> **Status: ⬜.** Charter §7 "Day 12"; references R2, §5.1 item 5, §4.5.

## Goal
Every failure is visible, every bug report is one copy away, and no UI string overclaims.

## Step-by-step instructions
1. **`Help → Copy detection diagnostics`** (R2): an action in the Help menu that copies to
   the clipboard:
   - every format tried, in priority order, with its ratio,
   - the first three unmatched lines,
   - the IDE build number (`ApplicationInfo.getBuild().asString()`),
   - the plugin version,
   - the outcome the user saw.
   Show a visible confirmation (balloon or status text). This is the bug report you want.
2. **Silent-failure sweep:** enumerate every place the plugin can fail and give each a visible
   message in the strip or the status bar — unreadable file, permission denied, encoding
   failure, unsupported compression, over-cap files. No bare `catch {}` anywhere; every
   `catch` either recovers visibly or logs with a reason and shows the user something.
3. **The honesty pass:** read `plugin.xml` and every UI string end to end.
   - Delete any claim the code does not support.
   - No "AI", no "enterprise", no "powerful", no "seamless".
   - `LogParser Pro`'s one-star review — *"Useless, just a placeholder"* — was earned by
     overselling a long feature list. The rule: every string is literally true.
4. **README cross-check:** the README's claims must match the shipped behaviour exactly.

## Acceptance criteria
- [ ] Diagnostics action present in Help menu; clipboard contains all five sections; a
      platform test asserts the content.
- [ ] Every failure path enumerated above shows a user-visible message (test per path).
- [ ] UI-string audit done: every string in `plugin.xml` + `strings` resources literally true.
- [ ] `.\gradlew.bat test` green.

## Commands
```powershell
.\gradlew.bat test --console=plain
```
