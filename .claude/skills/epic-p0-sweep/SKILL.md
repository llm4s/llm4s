---
name: epic-p0-sweep
description: Use when asked to work through the P0 (or other priority) issues of one or more llm4s code-review epics with subagents, raising PRs and merging them on green. Also use when coordinating several lane agents' PRs through Codex review, CI and the main merge queue.
---

# Epic P0 sweep

Fix the P0 issues of several epics in parallel, one lane worker per epic and one PR per epic, then merge through the merge queue. The main session coordinates and never writes the fixes itself. Workers never merge.

## Setup
1. Read each epic: `gh issue view E --json body -q .body | grep -nE '^#|P0|- \[[ x]\]'`. Note its P0 list, its "Order" section, and which modules it touches (`grep -n mimaFrozen build.sbt`: frozen modules need care).
2. Skip an epic whose P0s are all ticked or already in an open PR.
3. Start **at most 3 workers**, one per epic, so no two touch the same code. Use the `general-purpose` agent type with `run_in_background: true`, all started in one message. Each prompt contains:
   - the epic URL and its P0 issue numbers, each with a one-line gist;
   - "Read and follow `.claude/skills/epic-p0-sweep/worker-brief.md`";
   - epic-specific notes (frozen modules, dependency checks, `@Docker` suites, platform-sensitive code, an issue that decides a design for another epic);
   - the worktree path `../llm4s-epic-<E>` and the scratch-file prefix.
4. **More epics than 3:** queue the rest, starting with any epic that touches the same modules as a running one. When a worker's PR is open and its Codex rounds are done, send that worker the next queued epic with SendMessage. The worker then uses a fresh branch and worktree, so a lane's PR in CI or the queue does not hold its slot.
5. Tell the user the lane table: epic, P0s and order, plus any queued epics.

## While workers run
- **Per-issue messages:** relay each in a line or two. Flag anything to check at PR review: a new public type, a signature change, a behaviour change in a frozen module.
- **A partial fix:** the PR's `Fixes` will close the issue, so have the worker explain the gap on the issue and in the PR's "Not done". If the gap is real, file a follow-up issue and add it to the epic's P1 list.
- **A permission-denied action** (e.g. a worker's issue comment was blocked): do not do it on the worker's behalf. Tell the user, and offer to do it if they say so.
- **When a PR opens:** watch it with Monitor, set to `timeout_ms: 1800000`, and re-arm it on expiry. Restart it with every open PR number when a new PR opens. The awk drops pending-count-only changes:
  ```
  bash .claude/skills/epic-p0-sweep/scripts/watch-prs.sh 3221 3222 2>&1 | awk '{k=$0; sub(/ pending=[0-9]+/,"",k); if(k!=last[$2]){print; fflush(); last[$2]=k}}'
  ```

## Acting on watcher events
| Event | Action |
|---|---|
| `codex=<sha>:completed:unresolved=true` | Read `gh api repos/llm4s/llm4s/pulls/N/comments`; SendMessage the worker the finding (file:line, example, expected behaviour). |
| `codex=...:completed:unresolved=false` | Codex is clean for that head. |
| `codex=<sha>:stale:...` | The latest review is of an older commit. The head is unreviewed: wait for Codex, and comment `@codex review` if none is running. |
| `merge=DIRTY` | It conflicts with main. The worker rebases on `origin/main`, re-runs the module's tests and force-pushes with lease. The new head needs Codex and CI again. A queued PR is dropped by the conflict. |
| `fail=<job>` | Find the cause from the job's annotations: `gh api repos/llm4s/llm4s/check-runs/<job>/annotations` (`--log-failed` is often empty while the run is still going). A real failure goes to the worker. |
| Infra failure (HTTP 429/5xx, `Error downloading`, coursier `jvm-index` 504) | Rerun with `gh run rerun ID --failed` once the run completes ("already running" otherwise): use a background `until` loop. If the same job fails the same way again, it is an outage: curl the failing URL in a background loop until it returns 200, then rerun. If it is still down after an hour, tell the user. |
| `merge=CLEAN fail= pending=0` with Codex clean | Merge (below). |
| `MERGED` | Close-out (below). |

## Merging
The merge rule: Codex clean (or 3 rounds done and the final diff reviewed yourself), CI green on the head SHA, and `MERGEABLE`/`CLEAN`.
- **Enqueue:** `bash .claude/skills/epic-p0-sweep/scripts/enqueue.sh N <head-sha>`. `gh pr merge --squash` is refused.
- **Check the queue:** `bash .claude/skills/epic-p0-sweep/scripts/queue.sh`.
- **A PR that left the queue but is still OPEN** was dropped for `failed_checks` on its merge-group run. Find it with `gh run list --event merge_group`. For an infra cause, enqueue it again with the same head. For a real failure, send it to the worker. Once that worker pushes, the new head needs Codex clean and CI green before it is enqueued again.
- **3 Codex rounds without a clean result:** read the final diff yourself (`git diff --stat`, then file by file), checking that every finding is fixed or has a reasoned rebuttal on its thread. Merge only if you would approve the PR.

## Close-out per merged PR
1. Check each issue the PR fixes is CLOSED. Close any that auto-close missed, with a comment naming the PR.
2. Tick them on the epic. Run `gh issue view E --json body -q .body > epic.md`, then `sed -i '' -E 's/^- \[ \] #(a|b) /- [x] #\1 /' epic.md` (GNU sed: `sed -i -E ...`), then `gh issue edit E --body-file epic.md`. **Tick only after MERGED**, never on enqueue.
3. Post a summary comment on the epic: a table of issue and fix, the release label, behaviour changes with migration notes, and what was not done, with links to follow-ups.
4. Tell the user.

## Common mistakes
- Ticking epic boxes when a PR is queued: the queue can still drop it.
- One PR per issue: each costs a full CI run, and CI capacity is the bottleneck.
- Reading `--log-failed` of an in-progress run and concluding nothing failed.
- Rerunning a job while its workflow run is still going: it is refused.
- A shared scratch name like `pr.md`: parallel workers overwrite each other's PR bodies.
