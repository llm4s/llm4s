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
  `executeCommand` checks each command's executable, options, path arguments and environment (see [Command policy](#command-policy)),
  whether it arrives over the WebSocket protocol (`ContainerisedWorkspace`) or through a direct call

## Configuration

### Environment Variables (Runner)

When running the workspace runner (e.g. in Docker):

| Variable | Description | Default |
|----------|-------------|---------|
| `WORKSPACE_PATH` | Workspace root directory | `/workspace` |
| `WORKSPACE_SANDBOX_PROFILE` | Sandbox profile: `permissive` or `locked`; any other value stops the runner | `permissive` |
| `WORKSPACE_EXTRA_COMMANDS` | Programs added to the profile's `allowedCommands`, separated by commas or whitespace (for example `sbt`); a name that is not a bare program name, or is a shell or launcher, stops the runner | none |

These two variables are the only things that decide what the runner enforces.

### Adding programs to the allowlist

A program the agent needs that is not on the profile's list, such as a build tool, is added with
`WORKSPACE_EXTRA_COMMANDS`. `ContainerisedWorkspace` (and `CodeWorker`) take it as `extraAllowedCommands` and start
the container with it; `CodeGenExample` adds `sbt` this way so its agent can run `sbt compile` and `sbt run`:

```scala
new ContainerisedWorkspace(workspaceDir, imageName, hostPort, extraAllowedCommands = Set("sbt"))
// docker run ... -e WORKSPACE_EXTRA_COMMANDS=sbt ...
```

An added program is held to every check below (no shell, forbidden characters, path arguments inside the workspace,
the environment allowlist), but it has no per-program option rules, so it can do anything its own arguments allow:
`sbt run` runs the project's code. Add only what the agent needs. Shells (`sh`, `bash`, `cmd`, `pwsh`, ...) and
launchers that run a program named in their arguments (`env`, `xargs`, `sudo`, `nohup`, `timeout`, ...) are refused
(`WorkspaceSandboxConfig.NeverAllowedCommands`), as either would bring back the shell
[#1756](https://github.com/llm4s/llm4s/issues/1756) removed.

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

The policy applies to every command the runner executes, by either path: a direct `executeCommand` on
`WorkspaceAgentInterfaceImpl`, and the WebSocket protocol's `ExecuteCommandCommand`, which is how
`ContainerisedWorkspace.executeCommand` and `executeCommandWithStreaming` reach the runner in its container. Both go
through one function, so they share the checks, the codes and the way the program is started
([#1756](https://github.com/llm4s/llm4s/issues/1756)). Before that fix, the WebSocket path ran the raw command string
through `sh -c` (`cmd.exe /c` on Windows) with the client's environment copied in, and none of the checks below
applied to it.

No shell is involved on either path. The command is split into words (single and double quotes group a word, a
backslash escapes the next character), the first word names the program, and the program is started with the other
words as its arguments. Pipes, redirection, `;`, `&&`, `$(...)`, backquotes and variable expansion are not
interpreted; a word holding one of their characters is refused. Write each command as one program and its arguments,
and run a second command as a second request.

`executeCommand` runs a command only when every check passes, in this order, and otherwise fails with the code shown:

| Check | Code |
|-------|------|
| Shell turned off (`shellAllowed = false`) | `SHELL_DISABLED` |
| Working directory, as written, outside the workspace, or not a directory | `PATH_ESCAPE_ATTEMPT`, `INVALID_DIRECTORY` |
| Command with no words | `EMPTY_COMMAND` |
| Executable given as a path | `EXECUTABLE_PATH_NOT_ALLOWED` |
| Executable not in `allowedCommands` | `EXECUTABLE_NOT_ALLOWED` |
| A shell metacharacter (`&`, `\|`, `<`, `>`, `^`, `;`, `` ` ``, `$`, `%`) in any word | `FORBIDDEN_CHARACTERS` |
| Working directory really outside the workspace (a symbolic link out of it) | `PATH_ESCAPE_ATTEMPT` |
| `environment` sets a variable other than `LANG`, `LANGUAGE`, `LC_*`, `TZ`, `TERM`, `COLUMNS`, `LINES`, `NO_COLOR` | `ENVIRONMENT_NOT_ALLOWED` |
| An option the program refuses (below) | `ARGUMENT_NOT_ALLOWED` |
| An argument longer than 4096 characters, or paths that need more than 20000 lookups to check | `ARGUMENT_NOT_ALLOWED` |
| An argument holding a NUL character, on Windows one holding `"`, or one whose check fails with an error | `ARGUMENT_NOT_ALLOWED` |
| On Windows, a cmd.exe built-in's argument holding a character cmd.exe splits or parses (`,` `=` `(` `)` `@` `!`, a control character, a non-ASCII space) | `ARGUMENT_NOT_ALLOWED` |
| `git` with a `.git` file or link between the working directory and the workspace root | `PATH_ESCAPE_ATTEMPT` |
| An argument that names a location outside the workspace | `PATH_ESCAPE_ATTEMPT` |
| On Windows, a form listed under [On Windows](#on-windows) (a device name, a trailing `.` or space, `@`, `~`, glob syntax) | `ARGUMENT_NOT_ALLOWED` |
| `cp` only: a name it would write leads outside, or a recursive copy's destination holds a link that does | `PATH_ESCAPE_ATTEMPT` |

Over the WebSocket protocol a refused command gets a `WorkspaceAgentErrorResponse` carrying the code above, then a
`CommandCompletedMessage` with exit code 1, and no `CommandStartedMessage` or output; `ContainerisedWorkspace`
throws it as a `WorkspaceAgentException` whose message begins with the code. A command that runs streams its output
as before, and is stopped at its timeout, or at the sandbox's `defaultCommandTimeout` when the request sets none.

A command that passes every check runs with its standard input read from the null device (`/dev/null`, or `NUL`
on Windows), as nothing can write to it: a program that reads standard input when given no file (`cat`, `cat -`,
`sort`, `wc`, `grep x`, `findstr x`) sees an empty input and finishes at once instead of waiting until the command
timeout ([#1728](https://github.com/llm4s/llm4s/issues/1728)).

An allowlist names programs; these rules stop a listed program from writing, deleting, running another program or
following links out of the workspace through its own options:

| Program | Refused |
|---------|---------|
| `find` | `-delete`, `-exec`, `-execdir`, `-ok`, `-okdir`, `-fprint`, `-fprint0`, `-fprintf`, `-fls`, `-files0-from`, `-follow`, `-L` (also in `-HL`) |
| `git` | any subcommand but `status`, `log`, `show`, `diff`, `ls-files`, `ls-tree`, `grep`, `blame`, `rev-parse`, `branch`; any global option but `--version`, `--no-pager`, `--no-optional-locks`, `--literal-pathspecs`, `--no-replace-objects` (so `-c`, `-C`, `--exec-path`, `--git-dir`, `--work-tree`, `-p`); `--output`, `--ext-diff`, `--textconv`, `--show-signature` on `log`/`show`/`diff`; `-O`, `--open-files-in-pager`, `--textconv` on `grep`; `--textconv` on `blame`; an argument starting with `:` (pathspec magic, index paths); `branch` with anything but listing options, or with a name unless `--list`/`-l` makes it a pattern (the values of `--merged`, `--no-merged`, `--contains`, `--no-contains`, `--points-at`, `--sort` and `--format` are values, not names) |
| `sort` | `-o`, `--output`, `--compress-program`, `--files0-from`; on Windows also `/O`, `/T`, `-O`, `-T`, `-t`, `--temporary-directory`. The value of `-t` / `--field-separator` is not path-checked only when it really is that value: see [Option values](#option-values) |
| `findstr` (Windows) | a switch with `F` among its letters (`/F:list`), a `/D:` value holding `,` or `;` |
| `uniq` | a second operand (the output file); every argument after the first operand counts as one, as BSD `uniq` does not reorder its arguments, so write options before the file (`uniq -c a.txt`, not `uniq a.txt -c`) |
| `wc` | `--files0-from` |
| `ls` | `-L`, `--dereference` |
| `grep` | `-R`, `--dereference-recursive`, `-S` (BSD) |
| `cp` | `-L`, `--dereference`, `-H`, `-s`, `--symbolic-link`; with `-R`, `-r`, `-a`, `-P` or `-d` (or their long forms) anywhere in the arguments, two sources with the same name, or several sources and one that names a directory's contents (`src/.`, `src/`) |
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
- a short option: the whole argument and every tail after its dash, so an attached value at any position (`-f/x`,
  `-rf/x`) is checked, and so is the name a program opens when it reads the argument as a file (BSD programs stop
  reading options at their first operand, so `cat a.txt -f` opens `-f`);
- `sort -t` and `--field-separator` take a separator, not a path: `sort -t/ -k2`, `sort -t / -k2` and
  `sort --field-separator=/` run (POSIX only; see [Option values](#option-values)).
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
  instead (`findstr "/C:two words" a.txt`): that quoting is removed before the checks;
- on Windows, the built-ins the runner starts through `cmd.exe /c` (`dir`, `type`, `copy`, `move`, `echo`, ...)
  refuse (`ARGUMENT_NOT_ALLOWED`) an argument holding `,`, `=`, `(`, `)`, `@`, `!`, a control character (VT, FF, a
  line feed) or a space other than U+0020 (NBSP, U+00FF): `ProcessBuilder` quotes an argument only for a space, a
  tab, `"`, `<` or `>`, and cmd.exe splits a built-in's arguments on `,`, `;`, `=`, VT, FF and 0xFF as well, so
  `type a.txt,..\outside\f` typed `a.txt` and then `..\outside\f` although the argument checked was one name
  inside. `echo` may still print `,`, `=` and parentheses. Programs that are not built-ins (`findstr`) get their
  arguments from the C runtime, which splits on space and tab only, so `findstr x a,b.txt` runs. A wildcard in the last
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
  `git status` or `git diff` then runs, or point git at files outside through `core.worktree`, `.git/commondir` or
  `.git/objects/info/alternates` ([#1721](https://github.com/llm4s/llm4s/issues/1721)).
- `diff -r` follows symbolic links it meets inside the tree it walks; no portable option stops it.
- A relative link moved or copied to another depth by the read-write list (`mv a/b/rel rel`) can come to point
  outside. Paths through it are refused, and so is a recursive `cp` into its directory, but the link is not removed.
- The checks run before the program starts, so a link made at a checked name by a concurrent command is not seen.
  Windows `copy` gets the path rule but not `cp`'s destination checks.

### Option values

An option that takes a value takes the next argument whatever it is, so the argument after a `-t` is a separator
only if that `-t` is an option and not another option's value: in `sort -T -t /etc/passwd` and
`sort --random-source -t /etc/passwd`, `-T` and `--random-source` take `-t`, and `/etc/passwd` is a file sort reads
([#1763](https://github.com/llm4s/llm4s/issues/1763)). On POSIX the runner therefore parses `sort`'s arguments as
`getopt` does, each option's value consumed exactly once, for the options of both GNU and BSD sort:

| Takes a value | Short | Long |
|---------------|-------|------|
| always | `-k`, `-o`, `-S`, `-t`, `-T` | `--batch-size`, `--buffer-size`, `--compress-program`, `--field-separator`, `--files0-from`, `--key`, `--output`, `--parallel`, `--random-source`, `--sort`, `--temporary-directory` |
| only after `=` | | `--check` |
| GNU only: attached, or a next argument of digits | `-y` | |

A value is attached (`-Tdir`, `-rTdir`, `--temporary-directory=dir`) or the next argument (`-T dir`, `-rT dir`,
`--temporary-directory dir`, and any unambiguous abbreviation such as `--temp dir`); a `--` an option takes as its
value does not end the options. Every operand and every other option's value is checked whole. The separator is
left out only when:

- every option is one GNU or BSD sort has, unambiguously abbreviated, with its value where it needs one;
- no argument starts with `+` (BSD sort rewrites the obsolete `+POS1 -POS2` into `-k` before it reads options, even
  inside another option's value, so `sort -T +0 -1t /etc/passwd` reads `/etc/passwd`);
- the `-t` comes before the first operand (with `POSIXLY_CORRECT` in the runner's environment, GNU sort reads every
  argument after its first operand as a file; write `sort -t / a.txt`, not `sort a.txt -t /`).

Otherwise the separator is checked like any other value, so `-t /` is refused there. `cp` is parsed the same way
(GNU's `-S` / `--suffix` and `-t` / `--target-directory` take a value; macOS cp takes none and stops at its first
operand), so a `--` that `-S` takes does not hide a later `-R` (`cp -S -- -R src dst`), and `--path`, GNU's old
name for `--parents`, gets the `--parents` destination check. `uniq` (its `-f`, `-s`, `-w` values) and `git branch`
(the values of `--merged`, `--contains`, `--sort`, `--format`, ...) already consume each value once. Windows keeps
its own `sort` rules (see [On Windows](#on-windows)); there the argument after `--field-separator` is checked.

### On Windows

On Windows the policy refuses (`ARGUMENT_NOT_ALLOWED`) every form below rather than reasoning about what Win32, cmd.exe
or a program's runtime makes of it. Over-blocking is accepted there: only the forms the rest of this section allows
are supported. Each rule runs after the path rule, so an argument that leads outside is still `PATH_ESCAPE_ATTEMPT`.

- **Device names**, for every program but `echo`, `pwd`, `whoami` and `hostname`: a path component that is `CON`,
  `PRN`, `AUX`, `NUL`, `COM0`-`COM9`, `LPT0`-`LPT9`, `COM¹²³`, `LPT¹²³`, `CONIN$` or `CONOUT$`, in any case, with
  any extension and ignoring trailing dots and spaces (`nul`, `sub\con`, `NUL.txt`, `aux .txt`, `aux:s`). A device
  is opened whatever directory precedes it, so it bypasses the path rule; `copy a.txt nul` is refused too.
- **Trailing dots and spaces**, for the same programs: a component, other than `.` and `..`, that ends in `.` or a
  space (`outside.`, `a.txt `). Win32 strips them, so `outside.` opens `outside`. This also refuses git's open
  range `HEAD..` (write `HEAD..HEAD`) and a `findstr` pattern ending in `.`.
- **Programs that are not cmd.exe built-ins** (`findstr`, `sort`, `git`, `grep`, ...), which may run under a
  runtime that re-parses the command line itself (MSYS2, Cygwin, Git for Windows):
  - an argument starting with `@` (a response file whose lines become arguments: `grep x @args.txt`, `git log @{1}`)
    or `~` (a home directory);
  - an argument holding `{`, `}`, `[`, `]`, `'`, `(` or `)` (glob and quoting syntax): `grep [ab] a.txt` is refused
    on Windows, so the `[/]api` form suggested below for a pattern starting with `/` is not available there;
  - a string the program might open as a path that starts with `/` (`/sub/a.txt`, `-f/x`, `--file=/x`), which such a
    runtime reads from its own root rather than the workspace's drive; `findstr` and `sort` keep their `/X` switches;
  - a wildcard (`*`, `?`) anywhere but the last component (`*/a.txt`, `--exclude=*/target/*`), in an absolute string
    or one with a `..` component, or in a last component with no literal character other than `.` (`*`, `.*`, `??`,
    `*.*`), which can match `..`. `grep x *.txt`, `grep x sub/*.scala` and `findstr /S /I x *.txt` run. The
    built-ins `dir` and `type` keep the wildcard rule above (`dir *` runs).
- **`findstr`**: a switch with `F` among its letters (`/F:list`, `-F:list`, `/SIF:list`; `/OFF[LINE]` is allowed),
  which reads the names of the files to search from a file the path rule cannot see into; and a `/D:` value holding
  `,` or `;` (a directory list). `/D:dir` with a single directory is held to the workspace like any path.
- **`sort`**: `/O[UTPUT]`, `/T[EMPORARY]` (any switch whose letter is `O` or `T`), a short-option cluster holding `o`,
  `O`, `t` or `T`, and `--temporary-directory`, which write the output or temporary files (a GNU `sort` earlier on
  the `PATH` takes `-t` as its field separator; it is refused rather than guessed).
- **Not refused, by reasoning**:
  - *8.3 short names* (`PROGRA~1`). A short name aliases an entry of the directory it is in, so it cannot climb out
    of that directory, and the path rule's final step resolves the existing part of a path with `toRealPath`, which
    expands short names before the comparison with the real root. A short name that does not exist names nothing,
    and refusing `~` followed by a digit would refuse `HEAD~1`.
  - *Alternate data streams* (`file:stream`). The part before the `:` is judged, so `..\outside\f:s` is refused and
    `a.txt:s` runs.

### git's repository

git looks for its repository in the working directory and then in each directory above it, so a workspace that is a
subdirectory of a larger repository ran git on that repository: `git show HEAD:secret`, `git diff`, `git log -p`
and `git status` read files outside the workspace. The runner therefore starts `git` with `GIT_CEILING_DIRECTORIES`
set to the workspace root's parent and without any `GIT_*` variable the runner's own environment may carry - those
that point git at another repository or object store (`GIT_DIR`, `GIT_WORK_TREE`, `GIT_COMMON_DIR`,
`GIT_INDEX_FILE`, `GIT_OBJECT_DIRECTORY`, `GIT_ALTERNATE_OBJECT_DIRECTORIES`, `GIT_NAMESPACE`,
`GIT_DISCOVERY_ACROSS_FILESYSTEM`), add configuration (`GIT_CONFIG_*`, `GIT_CONFIG_PARAMETERS`, `GIT_CONFIG_COUNT`
with `GIT_CONFIG_KEY_n` / `GIT_CONFIG_VALUE_n`) or name a program (`GIT_EXEC_PATH`, `GIT_EXTERNAL_DIFF`): a workspace
without a repository of its own gets `not a git repository`. `GIT_CONFIG_NOSYSTEM` is not set, so the system and
global git configuration of whoever runs the runner still apply. `GIT_CEILING_DIRECTORIES` is a list split on the
path-list separator (`:` on POSIX, `;` on Windows) with no escaping, so where the workspace root's parent path - as
configured or with links resolved - holds that character, git is refused (`PATH_ESCAPE_ATTEMPT`) rather than run
with a ceiling it would misread. On every platform, `git` is also refused (`PATH_ESCAPE_ATTEMPT`) when the nearest
`.git` between the working directory and the workspace root is not a directory, or is one whose real path lies
outside the workspace (a `gitdir:` file, a link or a Windows junction points git at a repository elsewhere), and an
argument starting with `:` is refused (`ARGUMENT_NOT_ALLOWED`): pathspec magic (`:/`, `:(top)`, `:!x`) and index
paths (`:a.txt`) are resolved from the repository's top level, not the working directory. A repository inside the
workspace whose `.git` directory points git at files elsewhere through what git reads from it (`core.worktree` in
`.git/config`, `.git/commondir`, `.git/objects/info/alternates`), or a bare repository written into the workspace,
is the same class of gap as [#1721](https://github.com/llm4s/llm4s/issues/1721): it needs the agent to write files.

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
