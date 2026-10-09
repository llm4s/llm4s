---
layout: page
title: Workspace Sandbox
parent: Reference
nav_order: 17
---

# Workspace Sandbox Configuration

The LLM4S workspace subsystem provides powerful capabilities (read/write files, execute commands, search) that require explicit sandboxing and security configuration.

## Overview

- **WorkspaceSandboxConfig**: Explicit configuration describing allowed paths, resource limits, shell access, and timeouts
- **Validation at startup**: the runner reads `WORKSPACE_SANDBOX_PROFILE` when it starts. Unset or empty, it uses
  `permissive`; an unknown profile name makes `RunnerMain` fail and the runner stop. Only a known profile that fails
  validation (which the built-in profiles cannot) is logged and replaced by `permissive`
- **Enforcement**: Runner enforces limits and shell allowance; file path boundaries are enforced via `resolvePath`;
  `executeCommand` checks each command's executable, options, path arguments and environment (see [Command policy](#command-policy))

## Configuration

### Environment Variables (Runner)

When running the workspace runner (e.g. in Docker):

| Variable | Description | Default |
|----------|-------------|---------|
| `WORKSPACE_PATH` | Workspace root directory | `/workspace` |
| `WORKSPACE_SANDBOX_PROFILE` | Sandbox profile: `permissive` or `locked`; any other value stops the runner | `permissive` |

This variable is the only thing that decides what the runner enforces.

### Profiles

- **permissive**: Current behavior—shell allowed with the read-write command allowlist (`ReadWriteCommands`), standard limits (1MB file size, 500 dir entries, 30s command timeout)
- **locked**: Shell disabled; strict limits (10s timeout). File writes and modifications remain allowed; this profile does not enforce a read-only filesystem.

### HOCON (Client)

`WorkspaceConfigSupport.loadSandboxConfig()` reads a profile from the client's configuration:

```hocon
llm4s.workspace.sandbox {
  profile = "locked"  # or "permissive"
}
```

This does not control enforcement: the client does not pass it to the container, and nothing but tests calls
`loadSandboxConfig`. To lock a runner down, start its container with `WORKSPACE_SANDBOX_PROFILE=locked` (see the
example below). An unknown profile name makes `loadSandboxConfig` return a `Left`.

## WorkspaceSandboxConfig Structure

| Field | Type | Description |
|-------|------|-------------|
| `limits` | WorkspaceLimits | maxFileSize, maxDirectoryEntries, maxSearchResults, maxOutputSize |
| `excludePatterns` | List[String] | Glob patterns excluded from explore/search (e.g. node_modules, .git) |
| `shellAllowed` | Boolean | Whether executeCommand is allowed |
| `defaultCommandTimeout` | FiniteDuration | Default timeout for shell commands (more than zero, at most 1 hour) |
| `readOnlyPaths` | List[String] | Paths under workspace that are read-only (Phase 2) |
| `allowedPaths` | List[String] | If non-empty, only these paths accessible (Phase 2) |
| `networkAllowed` | Boolean | Documentation only; Phase 2: enforce network restrictions |
| `allowedCommands` | Set[String] | Executable names `executeCommand` may run; field default `ReadOnlyCommands`; the permissive profile (the default profile) uses `ReadWriteCommands`, which adds write-capable ones (`cp`, `mv`, `rm`, `mkdir`, …). Arguments are checked too: see [Command policy](#command-policy) |

## Command policy

`executeCommand` runs a command only when every check passes, in this order, and otherwise fails with the code shown:

| Check | Code |
|-------|------|
| Shell turned off (`shellAllowed = false`) | `SHELL_DISABLED` |
| Executable given as a path | `EXECUTABLE_PATH_NOT_ALLOWED` |
| Executable not in `allowedCommands` | `EXECUTABLE_NOT_ALLOWED` |
| A shell metacharacter (`&`, `\|`, `<`, `>`, `^`, `;`, `` ` ``, `$`, `%`) in any word | `FORBIDDEN_CHARACTERS` |
| Working directory really outside the workspace (a symbolic link out of it) | `PATH_ESCAPE_ATTEMPT` |
| `environment` sets a variable other than `LANG`, `LANGUAGE`, `LC_*`, `TZ`, `TERM`, `COLUMNS`, `LINES`, `NO_COLOR` | `ENVIRONMENT_NOT_ALLOWED` |
| An option the program refuses (below) | `ARGUMENT_NOT_ALLOWED` |
| An argument longer than 4096 characters, or paths that need more than 20000 lookups to check | `ARGUMENT_NOT_ALLOWED` |
| An argument holding a NUL character, on Windows one holding `"`, or one whose check fails with an error | `ARGUMENT_NOT_ALLOWED` |
| An argument that names a location outside the workspace | `PATH_ESCAPE_ATTEMPT` |
| `cp` only: a name it would write leads outside, or a recursive copy's destination holds a link that does | `PATH_ESCAPE_ATTEMPT` |

An allowlist names programs; these rules stop a listed program from writing, deleting, running another program or
following links out of the workspace through its own options:

| Program | Refused |
|---------|---------|
| `find` | `-delete`, `-exec`, `-execdir`, `-ok`, `-okdir`, `-fprint`, `-fprint0`, `-fprintf`, `-fls`, `-files0-from`, `-follow`, `-L` (also in `-HL`) |
| `git` | any subcommand but `status`, `log`, `show`, `diff`, `ls-files`, `ls-tree`, `grep`, `blame`, `rev-parse`, `branch`; any global option but `--version`, `--no-pager`, `--no-optional-locks`, `--literal-pathspecs`, `--no-replace-objects` (so `-c`, `-C`, `--exec-path`, `--git-dir`, `--work-tree`, `-p`); `--output`, `--ext-diff`, `--textconv`, `--show-signature` on `log`/`show`/`diff`; `-O`, `--open-files-in-pager`, `--textconv` on `grep`; `--textconv` on `blame`; `branch` with anything but listing options, or with a name unless `--list`/`-l` makes it a pattern (the values of `--merged`, `--no-merged`, `--contains`, `--no-contains`, `--points-at`, `--sort` and `--format` are values, not names) |
| `sort` | `-o`, `--output`, `--compress-program`, `--files0-from`; `/O` on Windows |
| `uniq` | a second operand (the output file); every argument after the first operand counts as one, as BSD `uniq` does not reorder its arguments, so write options before the file (`uniq -c a.txt`, not `uniq a.txt -c`) |
| `wc` | `--files0-from` |
| `ls` | `-L`, `--dereference` |
| `grep` | `-R`, `--dereference-recursive`, `-S` (BSD) |
| `cp` | `-L`, `--dereference`, `-H`, `-s`, `--symbolic-link`; with `-R`, `-r`, `-a`, `-P` or `-d`, two sources with the same name, or several sources and one that names a directory's contents (`src/.`, `src/`) |
| `chmod` | `-L`, `-H`, `--dereference` |
| `hostname` | an operand, `-F`, `--file`, `-b`, `--boot` |

`mv`, `rm`, `mkdir` and `touch` have no option that follows a link out of the workspace, so they get the path rule
only.

Every argument is scanned for these options, including those after `--`, because an option that takes a value can
consume the `--` itself. A short option is refused anywhere in a cluster (`sort -ro out`), and a long one under any
abbreviation of at least one letter (`sort --outp=out`), as GNU programs and git accept an unambiguous prefix.

Every argument of every program except `echo`, `pwd`, `whoami` and `hostname` is then held to the workspace. Each
candidate path is resolved from the real working directory the way the kernel resolves it, one component at a time,
following each symbolic link where it is met (so `link/..` is the parent of the link's target), and must stay inside
the real workspace root. It must also stay inside under the reading Windows uses, which removes `.` and `..` as text
before following any link (so `link/..` is the directory holding the link): a path is refused on every platform
unless both readings are inside, so with `l` -> `a/b`, `l/../../x` (`a/x` on POSIX, `x` beside the workspace on
Windows) is refused. Only a `..` after a symbolic link makes the two differ. That applies to programs added to a custom allowlist too. The candidates are:

- a positional argument, or an option's value given as the next argument: the whole argument;
- a long option `--name=value`: the whole argument and the value, so `git log --since=2024/01/01`, `--grep=feat/x`,
  `git ls-files --exclude=*/target/*`, `grep --include=sub/*.scala` and `ls --hide=x/y` run, while
  `--exclude-from=../x` or a value through a link out of the workspace is refused;
- a short option: every tail after its dash, so an attached value at any position (`-f/x`, `-rf/x`) is checked;
- `sort -t` and `--field-separator` take a separator, not a path: `sort -t/ -k2` and `sort -t / -k2` run;
- on Windows, the `/X` switches of `dir`, `findstr`, `copy`, `move` and `sort` are switches, not paths, but a value
  after `:` (`findstr /G:file`) is checked;
- on Windows, a string the platform cannot parse as a path is judged by the part before the first character a path
  cannot hold (`HEAD:src/x` by `HEAD`, `..\*` by `..\`); one that starts with `\` or `/` and has no such part
  (`\\?\C:\x`, `\??\C:\x`) is refused, and so is a drive-relative path on a drive other than the workspace's
  (`D:x`, which the program would resolve from that drive's own working directory). Windows removes `..` as text
  before it opens a name or matches a wildcard, so such a string is also refused when it has a `..` component after
  that character (`x*\..\..\outside\f`, `x?\..\..`, `ab:c\..\..`, which open `..\outside\f` although their
  prefix is inside), or when, with each such character replaced by `_`, it leads outside; `dir *.txt`,
  `type a?.txt` and `findstr /C:x a.txt` run;
- on Windows, an argument holding `"` is refused (`ARGUMENT_NOT_ALLOWED`) before any path or option check: the C
  runtime's argument parser and cmd.exe delete `"` as a quote, so `"..\outside\f` opens `..\outside\f` and
  `"C:\outside\f` an absolute path, and a Windows file name cannot hold one. Quote an argument in the command string
  instead (`findstr "/C:two words" a.txt`): that quoting is removed before the checks. A wildcard in the last
  component (`dir .*`) can match the `..` entry, but `dir` only lists it and `type` and `findstr` cannot read a
  directory, so it is not refused.

A working directory, or a file operation's path, that is not a valid path (a NUL character, or on Windows a `:` or
wildcard in it) is refused with `PATH_ESCAPE_ATTEMPT` rather than failing with an exception.

A relative value with no `..` component can only leave the workspace through a link, so the over-blocking is limited
to text that is absolute or climbs out with `..`: a `grep` pattern `/api` or `../x`, or an option value such as
`git log --grep=/x` or `--grep /x`, is refused although it is not a path (write `[/]api`, `[/]x`).

`cp` writes through a symbolic link it finds at the name it writes, so its destinations are checked as well. Any
operand may be the target, so for every pair of operands the name the source takes under the target (and, for
`src/` or `src/.`, the target itself; with `--parents`, the target joined with the source) must resolve inside the
workspace. A recursive copy also writes below those names, so each that is an existing directory is searched, without
following links, for a link that leads outside.

What these checks do not cover:

- `git` reads the repository's own `.git/config` and runs its hooks, so where the agent can write files it can set
  `core.fsmonitor`, `diff.external` or a filter driver, or add a hook such as `.git/hooks/post-index-change`, that
  `git status` or `git diff` then runs ([#1721](https://github.com/llm4s/llm4s/issues/1721)).
- `diff -r` follows symbolic links it meets inside the tree it walks; no portable option stops it.
- A relative link moved or copied to another depth by the read-write list (`mv a/b/rel rel`) can come to point
  outside. Paths through it are refused, and so is a recursive `cp` into its directory, but the link is not removed.
- The checks run before the program starts, so a link made at a checked name by a concurrent command is not seen.
  Windows `copy` gets the path rule but not `cp`'s destination checks.

## Security Gaps Addressed

| Gap | Phase 1 | Phase 2 |
|-----|---------|---------|
| Explicit config | ✓ WorkspaceSandboxConfig | |
| Validation at startup | ✓ | |
| Shell allow/block | ✓ shellAllowed | |
| Resource limits | ✓ limits configurable | |
| Read-only areas | Config present | Enforcement |
| Allowed/blocked paths | Config present | Enforcement |
| Network restrictions | Documentation only | Enforcement |

## Example: Locked-Down Sandbox

Run the minimal demo (local filesystem, no Docker):

```bash
sbt "workspaceSamples/runMain org.llm4s.samples.workspace.LockedDownSandboxDemo"
```

Run the containerized runner with locked sandbox:

1. After `sbt workspaceRunner/docker:publishLocal`, get the image tag:
   ```bash
   {% raw %}docker images llm4s/workspace-runner --format "{{.Tag}}"{% endraw %}
   ```
   Use that tag (for example `0.3.2` or a dynver snapshot such as `0.3.2+abc123-SNAPSHOT`) in place of `TAG` below.

2. Run the container (replace `TAG` and the host path to your workspace):
   ```bash
   docker run --rm -e WORKSPACE_SANDBOX_PROFILE=locked -v /path/to/workspace:/workspace -p 8080:8080 llm4s/workspace-runner:TAG
   ```
   On Windows with Docker Desktop, use a path Docker can mount (e.g. `C:\Users\you\workspace` or `//c/Users/you/workspace` depending on your setup).

## Phased Implementation Plan

### Phase 1: Config + docs + sample ✓
- **Affected**: workspaceShared, workspaceRunner, workspaceClient, workspaceSamples, docs
- **Complexity**: Low
- **Risks**: Minimal; backward compatible (default = permissive)

### Phase 2: Enforcement
- **Affected**: WorkspaceAgentInterfaceImpl (readOnlyPaths, allowedPaths), tools
- **Complexity**: Medium
- **Risks**: Path validation edge cases; breaking changes if strict

### Phase 3: Advanced policies (optional)
- **Affected**: New policies module, profiles (dev/staging/prod)
- **Complexity**: High
- **Risks**: Over-engineering; maintenance burden
