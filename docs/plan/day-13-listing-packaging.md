# Day 13 — The listing and the packaging

> **Status: ⬜.** Charter §7 "Day 13"; references §6.3, §8.1.

## Goal
The plugin and its marketplace page, ready to publish.

## Step-by-step instructions
1. **Release build:** `.\gradlew.bat buildPlugin` → `build\distributions\logsmith-0.1.0.zip`.
2. **Version bounds:** `sinceBuild` = the current stable platform build (252.*);
   **`untilBuild` deliberately unset or absurdly wide — permanently** (§8.1 decision 1).
   Verify with `.\gradlew.bat verifyPlugin` (Compatible on IC-252, IU-253 … EAP).
3. **The listing exactly as §6.3 specifies**, with **four screenshots** — the fourth must be
   **screenshot 3, the failure state** (honest failure is a selling point, not a confession):
   1) a detected, colourised log with the status line,
   2) the filter bar folding non-matching lines,
   3) the failure state with the warning icon and honest message,
   4) Ctrl+click stack-frame navigation.
4. **Public GitHub repository ready for users:**
   - README is the listing-quality description (status section current),
   - issue template that asks for the **diagnostics paste** from Day 12
     (`.github/ISSUE_TEMPLATE/bug_report.md`),
   - LICENSE present.
5. **Changelog:** `plugin.xml` `<change-notes>` summarising what shipped.

## Acceptance criteria
- [ ] Release zip builds clean; `verifyPlugin` Compatible everywhere targeted.
- [ ] `untilBuild` unset/wide; `sinceBuild` = current stable.
- [ ] Listing copy + 4 screenshots prepared, including the failure state.
- [ ] Issue template asks for the diagnostics paste.
- [ ] `.\gradlew.bat test` green before the release tag.

## Commands
```powershell
.\gradlew.bat clean buildPlugin verifyPlugin --console=plain
```
