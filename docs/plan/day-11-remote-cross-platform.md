# Day 11 — Remote and cross-platform

> **Status: ⬜.** Charter §7 "Day 11"; references R10, §4.10 ("Do not ship without this").

## Goal
Production logs live on remote machines. A plugin that fails over SSH is a plugin for hobby
projects.

## Step-by-step instructions
1. **R10 — VirtualFile-only audit:** grep the whole `src/main` for `java.io.File` and
   `java.nio` usages that touch paths obtained from the IDE. Every file access must go
   through `VirtualFile` (and `VirtualFile.getInputStream` / `contentsToByteArray`), which
   abstracts local, remote (SSH), WSL and mounted files alike. Fix anything else found.
2. **Remote passes** — verify on each transport:
   - Remote Development (SSH): open `.log` files from a remote host; detection, status line,
     highlighting, filtering, F2, Ctrl+click (resolve must respect the remote tree), live
     tail.
   - WSL: open a log inside a WSL distribution.
   - Docker mount: a mounted volume's log file.
3. **Cross-platform passes:**
   - Windows `\r\n` endings everywhere (already covered — re-run).
   - UTF-8 BOM, UTF-16 BOM, non-ASCII log content.
   - Case-sensitive paths (a `Thing.kt` reference must not match `thing.kt` on the same host).
4. **Deliverable:** the same three §7.2 test files open correctly over SSH.

## Acceptance criteria
- [ ] No `java.io.File` / `java.nio` on IDE-sourced paths (grep audit clean, documented).
- [ ] Detection + highlighting + filter + navigation work over SSH Remote Development.
- [ ] The three test files open correctly over SSH.
- [ ] All cross-platform regression tests green.

## Commands
```powershell
Select-String -Path "src\main\kotlin\**\*.kt" -Pattern "java\.io\.File|java\.nio" -CaseSensitive:$false
.\gradlew.bat test --console=plain
```
Remote checks are manual against `.\gradlew.bat runIde` connected to an SSH host.
