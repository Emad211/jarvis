# Jarvis project / code mode (`/project`)

Daemon-side contract for working inside **one project directory**: browse it, read and write
files, run an approved shell, and use five safe git verbs. Two clients speak it — the Android
panel and the `jarvis-project` CLI the agent uses.

Module: `phone/.local/bin/jarvisd_project.py` (loaded by `jarvisd.py`'s module tuple).
CLI: `phone/.local/bin/jarvis-project`. Skill: `phone/.hermes/skills/mobile/project/SKILL.md`.
State: `~/.hermes/jarvis/project.json` → `{"dir", "updated"}`.

Every number below was measured on the phone (SM-A536E, Android 16 / Termux) on **2026-10-27**
through `curl` from the phone over loopback (`127.0.0.1:8780`), `time_total` for the request and
the route's own `ms` field where it reports one. Nothing here is estimated.

## Routes

| method | path | body / query | answer |
|---|---|---|---|
| GET | `/project` | — | `{ok, dir, exists, git{repo,branch,dirty,ahead}, entries, ignored[], items[]}` |
| POST | `/project/open` | `{dir}` | same shape |
| GET | `/project/tree` | `?path=.&depth=1` | `{ok, path, items[{name,path,dir,size,mtime}], truncated, count}` |
| GET | `/project/file` | `?path=rel` | `{ok, path, text, size, truncated, binary}` |
| POST | `/project/file` | `{path, text}` | `{ok, path, bytes, backup}` |
| POST | `/project/run` | `{cmd, timeout?, approve}` | `{ok, cmd, rc, stdout, stderr, ms, timed_out}` |
| POST | `/project/git` | `{action, message?, approve?}` | `{ok, action, stdout, stderr, rc}` |

`entries` is the **count** of visible top-level items; `ignored` lists the skipped directory
names; `items` (up to 100 names) is the extra the panel can render without a second call.
`ok` on `/project/run` and `/project/git` means *the command completed successfully*
(`rc == 0 and not timed_out`); a non-zero `rc` is reported as `ok:false` with the real `rc`.

### Errors

| error | when | HTTP |
|---|---|---|
| `outside_home` | `open` target is not under `$HOME` | 403 |
| `outside_project` | `path`/`..`/absolute/symlink leaves the project | 403 |
| `missing` | path (or the project dir) does not exist | 404 |
| `not_dir` / `not_file` | wrong kind of path | 400 |
| `too_large` | read > 256 KB, write > 512 KB | 413 |
| `needs_approval` | `run` without `approve:true`, `git commit/init` without `approve:true` | 409 |
| `not_supported` | `git push/fetch/pull` (no network git, ever) | 400 |

## Measured

| route | case | wall |
|---|---|---|
| `GET /project` | default `$HOME`, 138 entries, 1 ignored | **55 ms** |
| `POST /project/open` | `~/proj-demo` | **54 ms** |
| `POST /project/open` | `/data/data/com.termux` → `outside_home` | **15 ms** |
| `POST /project/file` | create `hello.py` (93 B) | **16 ms** |
| `POST /project/file` | overwrite (18 B) **+ backup** | **52 ms** |
| `POST /project/file` | 600 000 B write → `too_large` | **24 ms** (`413`) |
| `POST /project/open` | a file → `not_dir` | `400` |
| `POST /project/open` | missing directory → `missing` | `404` |
| `GET /project` | saved directory deleted since → `exists:false` | — |
| `GET /project/file` | `hello.py` (93 B) | **15 ms** |
| `GET /project/file` | `big.txt` 300 000 B → `too_large` | **9 ms** |
| `GET /project/file` | `bin.dat` (NUL) → `binary:true, text:""` | ~10 ms |
| `GET /project/tree` | one file, `depth=1` | **15 ms** |
| `GET /project/tree` | 505 files, `depth=1` → `count:500, truncated:true` | (count verified) |
| `GET /project/file` | `../../../etc/passwd` → `outside_project` | **9 ms** |
| `POST /project/run` | no `approve` → `409 needs_approval` | **14 ms** (nothing executed) |
| `POST /project/run` | `python3 hello.py` (approved) | **163 ms** (`ms:153`, python start) |
| `POST /project/run` | `python3 -c "sys.exit(3)"` | **166 ms** (`ok:false, rc:3`) |
| `POST /project/run` | `sleep 5` with `timeout:2` | **2011 ms** (`ms:2010, rc:-9, timed_out:true`) |
| `POST /project/git status` | not a repo yet | **44 ms** (`rc:128`) |
| `POST /project/git init` | approved | **73 ms** |
| `POST /project/git status` | after init (`?? hello.py`) | **48 ms** |
| `POST /project/git commit` | approved, first commit | **197 ms** (`ms:171`, `rc:0`) |
| `POST /project/git status` | clean after commit (`## master`) | **30 ms** |
| `POST /project/git log` | one line | **29 ms** |
| `POST /project/git diff` | one file changed (`--stat` + full diff) | **103 ms** |
| `POST /project/git push｜fetch｜pull` | refused | — (`400 not_supported`) |

CLI wall times include the phone's **Python interpreter start (~0.5 s)** — that is the tool's
cost, not the route's: `status` 648 ms, `ls` 613 ms, `cat` 588 ms, `write` 1084 ms,
`run "python3 hello.py"` 1286 ms (623 ms of which is the child `python3`), `git status` 720 ms
(route `ms:45`). The CLI exits **0** only when the answer has `ok:true`, prints one JSON object,
honours `JARVISD` (a dead port answers `{"ok":false,"error":"unreachable"}` and exit 1).

## Behaviour worth knowing

- **Discovery of the project root**: `~/.hermes/jarvis/project.json` else `$HOME`. `open`
  stores `realpath(expanduser(dir))` after the `$HOME` guard, and `GET /project` reports
  `exists:false` if the saved directory later disappears.
- **Git**: `status` is `--porcelain=v1 -b`, `log` is `--oneline -20`, `diff` is `--stat` plus
  the full diff (64 KB cap). `commit` runs `git add -A` then `git commit -m <message>`.
  This phone has **no git identity**, so a commit with the ambient config answers
  `Author identity unknown (rc 128)`; the module therefore passes
  `-c user.name=Jarvis -c user.email=jarvis@localhost` **only when `user.email` is unset** —
  it never writes to the user's global config.
- **No network git**: `push`/`fetch`/`pull` answer `not_supported`, and every git call runs with
  `GIT_TERMINAL_PROMPT=0`/`GIT_ASKPASS=/bin/false`, so no credential can be asked for or leaked.
- **Shell**: `bash -lc <cmd>` with `cwd` = project root, `start_new_session=True`, and the whole
  process group is `SIGKILL`ed on timeout (`rc:-9`, `timed_out:true`) so a child cannot keep the
  request open. stdout/stderr are capped at 64 KB each (`*_truncated` flags). Every approved run
  appends one JSON line to `~/.hermes/jarvis/project-runs.log`
  (`{ts, dir, cmd, rc, ms, timed_out?}`).
- **Writes** are `temp file + os.replace`; an existing file's old bytes go to
  `~/.hermes/jarvis/project-backups/<sha1(path)[:10]>-<UTC stamp>.bak` and the path is returned in
  `backup` (`""` when the file is new). Missing parent directories inside the project are created.
- **Skipped directories**: `.git node_modules __pycache__ .venv build dist .gradle`.
- **Caps**: read 256 KB · write 512 KB · tree 500 items · `depth` 1–6 · run timeout default 60 s
  (max 600) · 64 KB stdout/stderr/diff.

## Not supported (and why)

- **Network git** — deliberate: no credentials on this device, and a research/console agent must
  not be able to publish code.
- **Binary writes** — `POST /project/file` takes a UTF-8 `text`; a binary file is readable as
  `binary:true` but cannot be written through this route (there is no base64 field in the
  contract). Copying binaries needs the terminal tool.
