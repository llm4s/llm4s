# Worker brief: the P0 issues of one epic

You fix the P0 issues of one epic, in your own git worktree, as **one PR for the whole epic**. Read each issue in full first (`gh issue view N --comments`), and the epic's "Order", "Compatibility" and cross-backend sections.

## Steps
1. Make a worktree outside the main checkout, branched from up-to-date `origin/main`: `git fetch origin && git worktree add ../llm4s-epic-<epic> -b fix/epic-<epic>-p0 origin/main`.
2. Work the issues one at a time, in the epic's stated order. For each:
   1. Run the Compatibility gate (below) before coding.
   2. Write the failing spec first, then the fix. Fix every defect the issue lists, and treat each one as a symptom of a class of problem: look for the same flaw in the sibling code paths the issue names.
   3. Verify locally:
      - the affected module's tests (`sbt "<module>/test" 2>&1 | tail -40`);
      - `sbt scalafmtAll` (plus `sbt scalafmtSbt` if you touched `.sbt` files);
      - `sbt "<module>/scalafix --check"`;
      - `scripts/check-docs.sh` if you touched docs;
      - `sbt mimaReportBinaryIssues` if the module calls `mimaFrozen`.
      - Platform-sensitive code (files, temp files, moves, processes, paths) must have its suite in `project/PlatformTests.scala` and its source in `scripts/platform-sensitive-sources.txt`.
      - When a growth-prone or pinned case class gains a field, bump its arity in `PositionalPatternsGainFields` (`modules/scalafix-rules`).
   4. Add a changelog fragment `changelog.d/<area>/<issue>.md` (format in `changelog.d/README.md`; check with `scripts/changelog-fragments.sh check`). A behaviour change in a Beta or Experimental module, or in the behaviour of a frozen one, also gets `breaking:` front matter and a `docs/reference/migration.md` note.
   5. Commit with `git commit -s` and the Co-Authored-By trailer: one or more signed commits per issue.
   6. SendMessage the main session in one line: the issue, the commit SHA, and anything skipped.
3. When every P0 you can do is committed, rebase on `origin/main` and push. Then open **one PR** with `gh pr create`:
   - Title: `fix(<area>): epic #<epic> P0s (#a, #b, #c)`.
   - Body: fill `.github/PULL_REQUEST_TEMPLATE.md` (including *Release impact*), with one `Fixes #N` line per issue and a "Not done" section for anything partial. End it with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
   - Label: `semver:minor` if any part is a Beta/Experimental API or behaviour change, else `semver:patch`.
4. Comment `@codex review` on the PR, then report back (format below). The main session watches the PR and messages you with Codex findings and CI failures.
5. For each Codex finding: fix it (or reply with a reasoned rebuttal if it is wrong), push, reply on the thread, and re-request `@codex review`. Act on findings at once; don't wait for CI. Allow up to 3 rounds. Codex is done when it says "Didn't find any major issues" or after 3 rounds.
6. Fix any CI failure on your head SHA (`gh run view ID --log-failed | tail -80`). For a download or infrastructure failure (HTTP 429/5xx, `Error downloading`), rerun once with `gh run rerun ID --failed`.

**Never merge the PR.** The main session merges it.

## Compatibility gate
1.0.0 is released. Check a module's tier by whether it calls `mimaFrozen` in `build.sbt`, and a type's tier with `grep -n '@Stable\|@Experimental'`.
- **Allowed** as `semver:minor`, with a `breaking:` line and a migration note:
  - any change to an `@Experimental` type;
  - any change in a Beta or Experimental module;
  - a behaviour change behind an unchanged `@Stable` signature.
- **Needs a major release:** breaking a `@Stable` API of a frozen module when no add-beside-and-deprecate route exists (docs/reference/compatibility-policy.md). Breaking means a renamed or removed member, a changed signature or default, a new abstract member, a renamed config key, or changed stored JSON. If an issue, or part of one, needs a major release:
  - Skip that part.
  - Comment on the issue: `Needs a major release: <reason, minimal break>`.
  - Add the `semver:major` label to the issue.
  - List it in your report.
  - Do the rest of the issue.
  - In the PR body, reference a partly skipped issue with `Refs #N`, not `Fixes #N`, so it stays open for the major release. Describe the skipped part under "Not done".
  - Skip a wholly skipped issue: it gets no commit and no PR line.

## Report back (short)
Include:
- the PR URL and head SHA;
- the labels;
- the CI state of the head;
- Codex rounds and their outcome;
- per issue, its commit, what changed, and anything skipped (and why);
- anything you could not do because a permission was denied (say so plainly; don't ask the main session to do it).

## Scratch files
The scratchpad is shared by every worker. Name your files uniquely (`pr-<epic>.md`, `body-<pr>.md`), and never read a generic name such as `pr.md`.
