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
- **Validation at startup**: Config is validated when the runner starts; invalid config falls back to permissive
- **Enforcement**: Runner enforces limits and shell allowance; file path boundaries are enforced via `resolvePath`;
  `executeCommand` checks each command's executable, options, path arguments and environment (see [Command policy](#command-policy))

## Configuration

### Environment Variables (Runner)

When running the workspace runner (e.g. in Docker):

| Variable | Description | Default |
|----------|-------------|---------|
| `WORKSPACE_PATH` | Workspace root directory | `/workspace` |
| `WORKSPACE_SANDBOX_PROFILE` | Sandbox profile: `permissive` or `locked` | `permissive` |

### Profiles

- **permissive**: Current behavior—shell allowed with the read-write command allowlist (`ReadWriteCommands`), standard limits (1MB file size, 500 dir entries, 30s command timeout)
- **locked**: Shell disabled; strict limits (10s timeout). File writes and modifications remain allowed; this profile does not enforce a read-only filesystem.

### HOCON (Client)

For the workspace client, sandbox config can be loaded via `WorkspaceConfigSupport.loadSandboxConfig()`:

```hocon
llm4s.workspace.sandbox {
  profile = "locked"  # or "permissive"
}
```

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
| An argument that names a location outside the workspace | `PATH_ESCAPE_ATTEMPT` |

An allowlist names programs; these rules stop a listed program from writing, deleting or running another program
through its own options:

| Program | Refused |
|---------|---------|
| `find` | `-delete`, `-exec`, `-execdir`, `-ok`, `-okdir`, `-fprint`, `-fprint0`, `-fprintf`, `-fls`, `-files0-from`, `-follow`, `-L` (also in `-HL`) |
| `git` | any subcommand but `status`, `log`, `show`, `diff`, `ls-files`, `ls-tree`, `grep`, `blame`, `rev-parse`, `branch`; any global option but `--version`, `--no-pager`, `--no-optional-locks`, `--literal-pathspecs`, `--no-replace-objects` (so `-c`, `-C`, `--exec-path`, `--git-dir`, `--work-tree`, `-p`); `--output`, `--ext-diff`, `--textconv`, `--show-signature` on `log`/`show`/`diff`; `-O`, `--open-files-in-pager`, `--textconv` on `grep`; `--textconv` on `blame`; `branch` with anything but listing options, or with a name unless `--list`/`-l` makes it a pattern |
| `sort` | `-o`, `--output`, `--compress-program`, `--files0-from`; `/O` on Windows |
| `uniq` | a second operand (the output file) |
| `wc` | `--files0-from` |
| `ls` | `-L`, `--dereference` |
| `grep` | `-R`, `--dereference-recursive` |
| `hostname` | an operand, `-F`, `--file`, `-b`, `--boot` |

Every argument is scanned, including those after `--`, because an option that takes a value can consume the `--`
itself. A short option is refused anywhere in a cluster (`sort -ro out`), and a long one under any abbreviation of at
least one letter (`sort --outp=out`), as GNU programs and git accept an unambiguous prefix.

Every argument of every program except `echo`, `pwd`, `whoami` and `hostname` is then held to the workspace: the
argument, and inside an option each tail after its dash (the `/x` of `--file=/x` or `-f/x`), is resolved from the
real working directory the way the kernel resolves it, one component at a time, following each symbolic link where it
is met (so `link/..` is the parent of the link's target), and must stay inside the real workspace root. That applies
to programs added to a custom allowlist too. On Windows, the `/X` switches of `dir`, `findstr`, `copy`, `move` and
`sort` are switches, not paths, but a value after `:` (`findstr /G:file`) is checked.

What these checks do not cover: `git` reads the repository's own `.git/config`, so where the agent can write files
it can set `core.fsmonitor`, `diff.external` or a filter driver that `git status` or `git diff` then runs; `diff -r`
follows symbolic links inside the tree it walks; and a `grep` pattern that looks like a path outside the workspace
(`/api`, `../x`) is refused - write `[/]api`.

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
