---
name: project
description: Work inside a project directory on the phone — browse the tree, read and write files, run approved shell commands, and use the safe git verbs. Use this instead of raw shell file edits when the user is building or debugging a project (site, script, repo) with you.
---

# project — one directory, its files, a shell and git

`jarvis-project` talks to `/project` on the daemon (`127.0.0.1:8780`). The user picks one
**project directory** once; everything below is relative to it and cannot leave it.

```sh
jarvis-project status                     # dir, git state, entry count
jarvis-project open ~/my-app              # switch project (must be inside $HOME)
jarvis-project ls [path] [depth]          # depth 1 = one level
jarvis-project cat src/main.py
jarvis-project write notes.md "…"         # or: write notes.md -   (stdin)
jarvis-project run "python3 hello.py"     # approved shell, cwd = project root
jarvis-project git status
jarvis-project git commit "fix: typo"
```

Every call prints ONE JSON object. `ok:false` carries `error` + `detail`.

## Rules that matter

- **Paths are project-relative and sandboxed.** `..`, absolute paths and symlinks that leave
  the project answer `outside_project`; a `dir` outside `$HOME` answers `outside_home`.
- **A shell needs approval.** `POST /project/run` without `{"approve":true}` answers 409
  `needs_approval` and runs nothing. The CLI always approves (it is the user's chosen hand);
  the app's panel has its own switch. Every approved command is appended to
  `~/.hermes/jarvis/project-runs.log` as one JSON line (cmd, rc, ms).
- **No network git.** `push`/`fetch`/`pull` answer `not_supported`; only `status`, `log`,
  `diff`, `commit`, `init` exist, so no credential is ever involved.
- **Writes are atomic and backed up.** The previous content lands in
  `~/.hermes/jarvis/project-backups/` (`sha1(path)+timestamp`); the route returns its path.
- Limits: read ≤ 256 KB (bigger answers `too_large`), write ≤ 512 KB, tree 500 items,
  64 KB of stdout/stderr, `run` timeout default 60 s (max 600).
- Skipped directories: `.git node_modules __pycache__ .venv build dist .gradle`.

## Typical loop

1. `jarvis-project status` — is the project open, is it a git repo, how many entries?
2. `jarvis-project ls` → `jarvis-project cat <file>` — read before editing.
3. `jarvis-project write <file> "…"` — atomic write, backup kept.
4. `jarvis-project run "python3 <file>"` — check the real stdout/stderr and `ms`.
5. `jarvis-project git status` → `jarvis-project git commit "…"`.

If `status` shows the wrong `dir`, `open` the right one first; if it answers `outside_home`,
ask the user which directory inside `$HOME` they mean instead of trying another path.
