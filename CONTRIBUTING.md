# Contributing — branch & merge culture

This repo is developed both by hand and by an automated multi-stage SDLC pipeline.
To keep everyone in one flow (and avoid commits landing on the wrong branch), the
rules below are the convention.

## Branches

| Branch | Created by | Purpose |
|---|---|---|
| `main` | — | The single mainline. Always releasable. **Everything merges here.** |
| `feat/<task-id>` | the SDLC pipeline (real mode), or you by hand | One unit of work — cut from `main`, opened as a PR, deleted after merge. |
| `phase-b4-poc-*` | the pipeline (PoC mode) | Throwaway demo branches titled `[PoC, DO NOT MERGE]` — never merged. |

- Feature work goes on a `feat/<slug>` branch + PR — **not** straight to `main`.
- A maintainer may push small docs/ops fixes to `main` directly.

## Merge culture

- **One change = one PR = one squash-merge into `main`.** Keep `main` history linear
  and meaningful; delete the branch after merge.
- Pipeline PRs stop at a **human merge gate** — a maintainer approves before the
  squash-merge. Nothing auto-merges.
- `main` is the only long-lived branch; everyone branches from it and returns to it.

## The shared-clone rule (important — this is where confusion happens)

The SDLC pipeline operates on this repo's **working tree** — it checks out
`feat/<task-id>` while a task is running. **Do not commit into the working tree while
a pipeline task is running on it**: your commit would land on the task's branch
instead of `main`. If you must commit during a run, use an isolated worktree so the
task's checkout is untouched:

```bash
git worktree add /tmp/co-main main
# edit + commit + push from /tmp/co-main
git worktree remove /tmp/co-main
```

Otherwise, simply commit when no pipeline task is active.
