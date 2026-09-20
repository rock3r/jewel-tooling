---
name: using-git-worktree
description: Use when starting nontrivial feature work that should stay isolated from the main checkout. Creates or reuses a safe git worktree with sensible defaults.
metadata:
  internal: true
---

# Using Git worktrees

Create isolated workspaces for implementation work without disturbing the main checkout.

## Before creating anything

Check whether isolation already exists:

1. Are you already inside a worktree?
2. Are you already on a non-main branch?

If either is true, report that isolation already exists and skip worktree creation.
Do not create a worktree without asking.

## Directory selection

Prefer these locations in order:

1. `.worktrees/`
2. `worktrees/`
3. create `.worktrees/`

Before creating a project-local worktree directory, verify it is gitignored. If it is not, fix that first.

## Creation flow

1. Determine the current branch and default branch.
2. Choose the base branch.
3. Create a new worktree with a descriptive branch name.
4. Run an initial project sanity check in the worktree.

```bash
git rev-parse --git-common-dir
git rev-parse --git-dir
git rev-parse --abbrev-ref HEAD
git worktree add .worktrees/<branch-name> -b <branch-name> <base-branch>
python3 scripts/validate.py fast
```

## Jewel Tooling notes

- Keep `.plans/` in the root checkout rather than inside the worktree.
- Use absolute paths when switching between Jewel Tooling and sibling repos under `~/src`.
- Prefer a worktree for implementation-heavy tasks, not for tiny doc-only edits.
- Do not publish, push, or create a remote repository from a worktree without user approval.
