# Contributing — branch & merge culture

This repo is developed both by hand and by an automated multi-stage SDLC pipeline.
To keep everyone in one flow (and avoid commits landing on the wrong branch), the
rules below are the convention.

## Branches

| Branch | Created by | Purpose |
|---|---|---|
| `dev` | — | The integration branch. **Everything merges here** — humans and agents alike. |
| `main` | — | The release mainline. Always releasable; `dev` is promoted into it, and the atlas stand deploys from it. |
| `feat/<task-id>` | the SDLC pipeline (real mode), or you by hand | One unit of work — cut from `dev`, opened as a PR **against `dev`**, deleted after merge. |
| `phase-b4-poc-*` | the pipeline (PoC mode) | Throwaway demo branches titled `[PoC, DO NOT MERGE]` — never merged. |

- Feature work goes on a `feat/<slug>` branch + PR into `dev` — **not** straight
  to `dev`, and not into `main`.
- A maintainer may push small docs/ops fixes to `dev` directly.
- Both branches run the full CI workflow, but only a commit on `main` publishes a
  deployable jar (`app-jar-<sha>`) — promoting `dev` is what ships to the atlas
  stand. See `docker/atlas/README.md`.

## Merge culture

- **One change = one PR = one squash-merge into `dev`.** Keep the history linear
  and meaningful; delete the branch after merge.
- Pipeline PRs stop at a **human merge gate** — a maintainer approves before the
  squash-merge. Nothing auto-merges.
- `dev` and `main` are the long-lived branches; everyone branches from `dev` and
  returns to it.
- **Promotion.** `dev` reaches `main` through a maintainer-opened `dev` → `main`
  pull request, merged (not squashed) so both histories stay comparable. That
  merge is what produces a deployable jar: CI publishes it, and the stand takes it
  the next time the app is started there. Nothing is committed to `main` directly except a hotfix,
  which is merged back into `dev` the same day.

## The shared-clone rule (important — this is where confusion happens)

The SDLC pipeline operates on this repo's **working tree** — it checks out
`feat/<task-id>` while a task is running. **Do not commit into the working tree while
a pipeline task is running on it**: your commit would land on the task's branch
instead of `dev`. If you must commit during a run, use an isolated worktree so the
task's checkout is untouched:

```bash
git worktree add /tmp/co-dev dev
# edit + commit + push from /tmp/co-dev
git worktree remove /tmp/co-dev
```

Otherwise, simply commit when no pipeline task is active.
