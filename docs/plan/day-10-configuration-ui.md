# Day 10 — The configuration UI (R3, R7, R8)

> **Status: ⬜.** Charter §7 "Day 10"; references R3, R7, R8, §5.1, §5.5.

## Goal
A settings page a first-time user never needs to open, but a power user never leaves.

## Step-by-step instructions
1. **Format registry service first** — lift the twelve sniffers into a
   `LogSmithFormatRegistry` (`PersistentStateComponent`, stored in `logsmith.xml`) so the
   settings page and the sniffer share one source of truth. Registry state: per-format
   enabled flag + optional colour overrides.
2. **Settings page** under `Settings → Tools → LogSmith` (`Configurable`):
   - Table of built-in formats: on/off checkbox + colour per format.
   - Section for user-defined formats: name, record regex, priority, colour fields.
   - Changes apply through the registry; the sniffer consults enabled formats only.
3. **The live preview panel is the differentiator** (§5.1, §5.5):
   - Below the pattern editor, show the first matching lines of **an actually-open file**
     re-rendering live, with the live match ratio.
   - Updates on every keystroke, debounced ~200 ms; no restart, no close-and-reopen.
   - Pick the preview source from the currently open `.log` editors; show the file name.
4. **Full clipboard support in every text field** — paste, cut, undo, select all. Verify
   each explicitly (this was a one-star review in the market research; do not repeat it).
5. **R8 — apply immediately:** on settings change, re-highlight open editors in place while
   **preserving caret and scroll** (store caret offset + scroll before, restore after).
6. **Retire the debt:** the deprecated `createTextAttributesKey(key, attrs)` calls (×5 in
   `LogSmithTokenTypes.kt`) get replaced now that a settings page owns colours.

## Acceptance criteria
- [ ] Settings page loads, persists across restarts, and applies live.
- [ ] Enabling/disabling a built-in format changes detection results without a restart.
- [ ] Preview shows live ratio for an open file while typing a custom pattern.
- [ ] Paste/cut/undo/select-all work in every text field.
- [ ] Caret and scroll survive a live re-highlight (R8 platform test).
- [ ] `.\gradlew.bat verifyPlugin` — the deprecated-API finding disappears.

## Commands
```powershell
.\gradlew.bat test --console=plain
.\gradlew.bat verifyPlugin --console=plain
.\gradlew.bat runIde   # exercise the settings page manually
```
