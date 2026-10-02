# Day 14 — Publish, then the part that actually decides the outcome

> **Status: ⬜.** Charter §7 "Day 14"; references §4.10, §6.3, §8.1.

## Goal
Publish — and then do the distribution work the last launch was missing.

## Step-by-step instructions
1. **Publish to the JetBrains Marketplace.** It goes live immediately — there is no human
   review queue for a free plugin (the approval gate applies to paid listings and is not a
   barrier to charging, only to activating).
2. **The thing that was missing on the last launch — showing up.** A marketplace listing with
   no audience gets ~0 installs. The proof is §4.10: `Remote Log Tail` shipped a better
   product than LogSmith, free, in 2026 — and has 318 downloads. `LogParser Pro` shipped a
   longer feature list — 2,951 downloads and one angry review. **Downloads come from people
   showing up, not from the listing existing.**
3. **The realistic distribution plan:** answer actual questions in the places where
   developers complain about log files:
   - IntelliJ YouTrack tickets about log rendering/tailing,
   - Stack Overflow tags `intellij-idea` + `logging`,
   - r/java, r/IntelliJIdea,
   - the JetBrains Discord.
   With the plugin mentioned **only where it is genuinely the answer to the question asked**.
   The §5 material is the script: fifty-three specific bugs people are living with are known
   and quotable, because they were read.
4. **First three genuine answers** — the phase's deliverable is not the publish, it is the
   three answers.
5. **Measure honestly:** record the install count baseline; check weekly; the market sweep's
   kill criteria (charter §6) decide what happens next.

## Acceptance criteria
- [ ] Plugin published and live on the marketplace.
- [ ] First three genuine answers posted in the channels above.
- [ ] Install baseline recorded; weekly check scheduled (CI already runs perf weekly, §8.1).

## Deliverable
Published, plus the first three genuine answers posted.
