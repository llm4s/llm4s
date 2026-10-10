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
| `WORKSPACE_EXTRA_COMMANDS` | Programs added to the profile's `allowedCommands`, separated by commas or whitespace (for example `sbt`); a name that is not a bare program name, or is a shell or launcher (with or without `.exe` / `.com`), stops the runner | none |

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
`sbt run` runs the project's code. Add only what the agent needs. In particular, the policy does not model a program
that makes links or unpacks an archive (`ln`, `tar`, `unzip`, `rsync`, `install`): each of its arguments is checked
against the file system as it is before the command runs, but not the link text it writes, the names in an archive,
or a later argument that goes through a name an earlier one of its operations made (see
[Several sources in one command](#several-sources-in-one-command)). Shells (`sh`, `bash`, `cmd`, `pwsh`, ...) and
launchers that run a program named in their arguments (`env`, `xargs`, `sudo`, `nohup`, `timeout`, ...) are refused
(`WorkspaceSandboxConfig.NeverAllowedCommands`), as either would bring back the shell
[#1756](https://github.com/llm4s/llm4s/issues/1756) removed.

### Profiles

- **permissive**: Current behavior—shell allowed with the read-write command allowlist (`ReadWriteCommands`), standard limits (1MB file size, 500 dir entries, 30s command timeout)
- **locked**: Shell disabled; strict limits (10s timeout). File writes and modifications remain allowed; this profile does not enforce a read-only filesystem.

### Client configuration

The workspace client has no sandbox setting: what the runner enforces is decided only by the two variables above,
set on the runner's container. `WorkspaceConfigSupport.loadSandboxConfig()`, which read an
`llm4s.workspace.sandbox.profile` that nothing passed to the container, was removed
([#1730](https://github.com/llm4s/llm4s/issues/1730)). To lock a runner down, start its container with
`WORKSPACE_SANDBOX_PROFILE=locked` (see the example below); to turn a profile name into a config in your own code,
use `WorkspaceSandboxConfig.fromProfileName`.

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
| A program other than the read-only ones naming `.git` or a path inside one (see [git's own files](#gits-own-files)) | `ARGUMENT_NOT_ALLOWED` |
| An argument that names a location outside the workspace (an `rm` / `unlink` operand or `mv` source naming a link itself: its directory, see [Removing a link](#removing-a-link)) | `PATH_ESCAPE_ATTEMPT` |
| On Windows, a form listed under [On Windows](#on-windows) (a device name, a trailing `.` or space, `@`, `~`, glob syntax) | `ARGUMENT_NOT_ALLOWED` |
| `cp` only: a name it would write leads outside, or a recursive copy's destination holds a link that does | `PATH_ESCAPE_ATTEMPT` |
| `mv` or `cp` of several sources: a path goes through a name another source's operation creates, replaces or removes, or `mv` moves the working directory (see [Several sources in one command](#several-sources-in-one-command)) | `ARGUMENT_NOT_ALLOWED` |
| The program is found only where the runner never starts one from: the workspace, the runner's own working or Java directory, or an empty or relative `PATH` entry (see [Program resolution](#program-resolution)) | `EXECUTABLE_NOT_ALLOWED` |
| The program is found nowhere the runner looks | `EXECUTABLE_NOT_FOUND` |
| `git` only: the repository's configuration has an `include.path` / `includeIf`, a `hook.*` key, or a driver name a `-c` override cannot express (see [git's own files](#gits-own-files)) | `GIT_CONFIG_NOT_ALLOWED` |

`writeFile` and `modifyFile` refuse a path naming `.git` or a path inside one with `PATH_NOT_ALLOWED`.

### Program resolution

The runner never starts a program by its bare name: it looks the program up itself and starts the absolute path it
finds ([#1790](https://github.com/llm4s/llm4s/issues/1790)). Started by a bare name, a program was looked up by the
platform in places an agent can reach. On Windows, `CreateProcess`
([reference](https://learn.microsoft.com/windows/win32/api/processthreadsapi/nf-processthreadsapi-createprocessw))
searches the directory of the runner's `java.exe` and then the runner's **current directory** before the system
directory, appending `.exe`, so a `git.exe` or `cat.exe` an agent wrote there - the runner started in the workspace,
or in any directory the agent could write - ran in place of the allowlisted program. On POSIX the JDK searches `PATH`
after changing to the command's working directory, so an empty or relative `PATH` entry (`.`, `bin`) - or an entry
inside the workspace - found a program the agent wrote there.

The runner searches:

| Host | Where, in order | File names |
|------|-----------------|------------|
| Windows | the system directory (`%SystemRoot%\System32`), the Windows directory (`%SystemRoot%`), then each `PATH` entry | `<name>.exe`, then `<name>.com` (as `CreateProcess`, it does not use `PATHEXT`: no `.bat` or `.cmd`) |
| POSIX | each `PATH` entry | `<name>`, a regular file that is executable |

and never:

- the runner's current directory, even when it is on `PATH` (unless it is the system directory, where a Windows
  service starts);
- the directory of the runner's `java` executable, unless it is on `PATH`;
- an empty or relative `PATH` entry, which would be read from the command's working directory;
- a directory inside the workspace, as written or after following links, nor a file whose real path is inside the
  workspace (a link from a trusted directory into it).

A program found only in one of those places is refused with `EXECUTABLE_NOT_ALLOWED`, and the message names where it
was found; one found nowhere with `EXECUTABLE_NOT_FOUND`. A cmd.exe built-in (`echo`, `dir`, `type`, ...) runs through
the system directory's `cmd.exe`, by its absolute path and never one from `PATH`, with
`NoDefaultCurrentDirectoryInExePath=1`, and is named to it without an extension, so cmd.exe never looks a program up
in the working directory. The `PATH` the runner was started with is the one searched; a command cannot set `PATH`
(`ENVIRONMENT_NOT_ALLOWED`).

On Windows a command's first word and the allowlist's entries are matched by program name: ignoring case and a
`.exe` or `.com` extension, so `git.exe status` runs `git`, an allowlist entry `sort.exe` allows `sort`, and either is
held to `sort`'s rules below (an entry spelled with the extension used to escape them,
[#1761](https://github.com/llm4s/llm4s/issues/1761)). `WORKSPACE_EXTRA_COMMANDS` refuses a shell or launcher under
either spelling (`bash.exe`, `CMD.COM`).

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
| `sort` | `-o`, `--output`, `--compress-program`, `--files0-from`; on Windows also `/O`, `/T`, `-O`, `-T`, `-t`, `--temporary-directory`, and any argument starting with `/` that is not a native `sort.exe` switch (see [On Windows](#on-windows)). The value of `-t` / `--field-separator` is not path-checked only when it really is that value: see [Option values](#option-values) |
| `findstr` (Windows) | a switch with `F` among its letters (`/F:list`), a `/D:` value holding `,` or `;`, any argument starting with `/` that is not a native `findstr.exe` switch (see [On Windows](#on-windows)) |
| `uniq` | a second operand (the output file); every argument after the first operand counts as one, as BSD `uniq` does not reorder its arguments, so write options before the file (`uniq -c a.txt`, not `uniq a.txt -c`) |
| `wc` | `--files0-from` |
| `ls` | `-L`, `--dereference` |
| `grep` | `-R`, `--dereference-recursive`, `-S` (BSD) |
| `cp` | `-L`, `--dereference`, `-H`, `-s`, `--symbolic-link`; with `-R`, `-r`, `-a`, `-P` or `-d` (or their long forms) anywhere in the arguments, two sources with the same name (letter case and Unicode normalisation ignored; on Windows a name with `~` matches any), or several sources and one that names a directory's contents (`src/.`, `src/`) |
| `chmod` | `-L`, `-H`, `--dereference` |
| `hostname` | an operand, `-F`, `--file`, `-b`, `--boot` |

`mv`, `rm`, `mkdir` and `touch` have no option that follows a link out of the workspace, so they get the path rule
only (with the exception for a link itself under [Removing a link](#removing-a-link)).

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
  after `:` (`findstr /G:file`) is checked, and for `findstr` and `sort` only the native tool's switches are
  accepted (see [On Windows](#on-windows));
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

- A repository that arrives with a `.git/config` `core.worktree`, a `.git/commondir` or
  `.git/objects/info/alternates` points git at files outside; see [git's own files](#gits-own-files).
- `diff -r` follows symbolic links it meets inside the tree it walks; no portable option stops it.
- A relative link moved or copied to another depth by the read-write list (`mv a/b/rel rel`) can come to point
  outside. Paths through it in a later command are refused, and so is a recursive `cp` into its directory; within
  the same command, see [Several sources in one command](#several-sources-in-one-command). The agent can remove it
  (`rm rel`, see [Removing a link](#removing-a-link)), but the runner does not.
- The checks run before the program starts, so a link made at a checked name by a concurrent command is not seen.
  Windows `copy` gets the path rule but not `cp`'s destination checks.

### Removing a link

Removing or renaming a symbolic link never touches what it points to: `rm` reads its operand without following a
link at its last component, `unlink` and `rename` act on the name itself. So on POSIX, an operand of `rm` or `unlink`,
or a source of `mv`, whose last component is itself a symbolic link is judged by the directory holding it rather than
by where the link leads, and an agent can clean up a link that points out of the workspace, a dangling one, or the
first of a chain of links ([#1730](https://github.com/llm4s/llm4s/issues/1730)):

```text
rm outlink            # removes the link; its target is untouched
rm -f outlink         # likewise
mv outlink sub/       # moves the link itself to sub/outlink
mv outlink renamed
unlink outlink        # where unlink is added with WORKSPACE_EXTRA_COMMANDS
```

Such an operand is allowed only when all of these hold; otherwise it gets the path rule, which follows the link and
refuses it:

- **Its last component is a name with no trailing `/`** (not `outlink/`, `outlink/.`, `.` or `..`). With a trailing
  slash the kernel follows the link: `mv outlink/ x` moves the directory the link points to, and `rm -r outlink/`
  deletes what is in it.
- **The directory holding it is inside the workspace** under both readings of the path rule (the kernel's, following
  links, and the one that removes `..` as text), and both name the same directory: `rm escape/link` with `escape` a
  link out of the workspace, `rm ../outside/link` and `rm l/../link` with `l` a link to a deeper directory are
  refused.
- **In that directory, the last component is a symbolic link**, read without following it.
- **`rm` is not recursive** (`-r`, `-R`, `--recursive`). GNU and BSD `rm -r` remove a link operand and not its target,
  but not every implementation has been checked, and removing a link needs no `-r`.
- **For `mv`, it is a source, not the destination.** The destination keeps the path rule, links followed:
  `mv a.txt outlink` moves `a.txt` into the directory the link points to and is refused, as are `mv -t outlink a.txt`
  and `mv --target-directory=outlink a.txt`.
- **The arguments read the same to every `rm` / `mv` / `unlink`**: they are parsed by GNU's option table (with and
  without `POSIXLY_CORRECT`) and by macOS / FreeBSD's, and every parse must know every option and agree on which
  arguments are operands and which is the destination. An option only GNU has (`rm --force`, `mv -T`, `mv -t dir`,
  any long option) or only BSD has (`mv -h`) keeps the link refused; write `rm -f outlink`, `mv outlink dir/`. An
  operand starting with `-` is never exempted.

On Windows a link or junction is not exempted: the policy does not model how `del`, `rd`, `move` or an MSYS `rm`
treat them, so such an operand keeps the path rule and is refused when it leads outside. `cp` is unchanged: it
copies what a link points to unless told otherwise, so its sources keep the path rule.

A link moved by an `mv` of several sources cannot be used by a later source of the same command:
`mv evil d/evil/secret.txt d`, with `evil` a link out of the workspace and `d` empty, is refused, since once `evil`
is in `d`, `d/evil/secret.txt` names the file outside ([#1776](https://github.com/llm4s/llm4s/issues/1776)). Move
the link on its own: `mv evil d`.

### Several sources in one command

`mv` and `cp` handle several sources one at a time, so each operation changes the file system the next resolves its
paths in, but every path is checked before the command starts. With `sub/l` -> `../outside`, which names a missing
entry of the workspace while the link sits in `sub`, `mv sub/l l/secret.txt .` first moves the link to `./l`, where
it names the real directory outside, and then moves `outside/secret.txt` into the workspace
([#1776](https://github.com/llm4s/llm4s/issues/1776)). GNU `cp -P`, `-d`, `-R` and `-a` likewise copy a link as a link
and then read a later source through the copy.

So for an `mv` or `cp` of two sources or more (under any of the GNU and macOS / FreeBSD parses of its options), each
source's operation is taken to change:

- in the destination directory, the source's last name and any name starting with it (`--backup` keeps a replaced
  entry as `name~` or `name.~1~`), or any name at all when that is not known (`src/.`, `cp --parents`);
- for `mv`, the source's own entry in the directory holding it.

Every other path argument (the other sources, the destination, a `-t` value) is walked as the path rule walks it,
under both readings, links' targets included, and the command is refused (`ARGUMENT_NOT_ALLOWED`) when the walk looks
up one of those entries. Names are compared without regard to letter case or Unicode normalisation, as on macOS and
Windows file systems (NFKD decomposition, as APFS decomposes before it case-folds, then case mapping, repeated until
the name stops changing, so `ẞ`, `ß`, `ss` and `SS` are one name, as are `ᾳ̃` and `α̃ι`, as on APFS), and directories by identity, so another spelling of the destination (`alias/evil/...` with
`alias` -> `d`) is caught. Windows also takes `x.` and `x ` (trailing dots and spaces) for `x`; these are not folded,
which matters only for a native Win32 `cp`. An `mv` is also refused when the working directory lies inside a source it moves, since a
relative path's `..` then climbs from the source's new place. Argument order is not modelled: a path through a name a
later source creates is refused too. Run one command per source instead; each is then checked against the file
system the previous one left.

```text
mv sub/l l/secret.txt .           # refused: l/secret.txt goes through the ./l the first move makes
mv evil d/evil/secret.txt d       # refused, also through another name for d, or d/EVIL, or d/evil~
mv sub sub/Main.scala d           # refused: sub/Main.scala goes through the sub the first move removes
cp -P sub/l l/secret.txt .        # refused: GNU cp reads l/secret.txt through the copied link
cp -P a/b/x c/X d                 # refused: on macOS d/X is the link d/x, and cp writes c/X through it
cp -P a/b/ẞ c/ss d                # refused: the same on macOS, where ẞ and ss are one name
mv a.txt b.txt d                  # runs
mv outlink dangling d/            # runs: several links moved, nothing reached through them
```

The other programs of the lists need no such rule: `rm` (and `unlink`) only remove entries, so a later operand
through one fails rather than leading elsewhere; `mkdir` and `touch` make directories and files, never links, and the
path rule already takes a missing component as a directory to be made; `chmod` changes no name; the read-only programs
and the read subcommands of `git` change nothing; Windows `copy` and `move` take one source argument (a wildcard's
matches go into the destination, which no other argument goes through, and `copy a+b dest` concatenates into one
file; the `,` of `move a,b dest` is refused). A program added with `WORKSPACE_EXTRA_COMMANDS` is not modelled (see
[Adding programs to the allowlist](#adding-programs-to-the-allowlist)).

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
    runtime reads from its own root rather than the workspace's drive; `findstr` and `sort` keep their native
    switches (see below), and on Windows a path of the form `/x/...` is also judged as drive `x:`'s path
    (`/c/Users` as `C:\Users`), as such a runtime reads it;
  - a wildcard (`*`, `?`) anywhere but the last component (`*/a.txt`, `--exclude=*/target/*`), in an absolute string
    or one with a `..` component, or in a last component with no literal character other than `.` (`*`, `.*`, `??`,
    `*.*`), which can match `..`. `grep x *.txt`, `grep x sub/*.scala` and `findstr /S /I x *.txt` run. The
    built-ins `dir` and `type` keep the wildcard rule above (`dir *` runs).
- **`findstr`**: a switch with `F` among its letters (`/F:list`, `-F:list`, `/SIF:list`; `/OFF[LINE]` is allowed),
  which reads the names of the files to search from a file the path rule cannot see into; and a `/D:` value holding
  `,` or `;` (a directory list). `/D:dir` with a single directory is held to the workspace like any path. An
  argument starting with `/` must be a switch of the native `findstr.exe`
  ([reference](https://learn.microsoft.com/windows-server/administration/windows-commands/findstr)), ignoring case:
  the flags `/B /E /L /R /S /I /X /V /N /M /O /P`, alone or combined (`/SIN`), `/OFF[LINE]`, and `/C:string`,
  `/G:file`, `/D:dir`, `/A:color` (one or two hex digits), which may follow flags (`/IC:x`). The value of `/G:` and
  `/D:` is held to the workspace like any path. Anything else starting with `/` is refused (`findstr x /etc`,
  `findstr /c:foo /c/x/secret`, `/C` without `:`) ([#1738](https://github.com/llm4s/llm4s/issues/1738)).
- **`sort`**: `/O[UTPUT]`, `/T[EMPORARY]` (any switch whose letter is `O` or `T`), a short-option cluster holding `o`,
  `O`, `t` or `T`, and `--temporary-directory`, which write the output or temporary files (a GNU `sort` earlier on
  the `PATH` takes `-t` as its field separator; it is refused rather than guessed). An argument starting with `/`
  must be, whole and ignoring case, a switch of the native `sort.exe`
  ([reference](https://learn.microsoft.com/windows-server/administration/windows-commands/sort)): `/R`, `/REVERSE`,
  `/+n` (digits), `/L` or `/LOCALE`, `/M` or `/MEMORY`, `/REC` or `/RECORD_MAXIMUM` (each of these three takes the
  next argument as its value), and the undocumented `/C`, `/CASE_SENSITIVE` and `/UNIQUE`
  ([ss64](https://ss64.com/nt/sort.html)). Anything else starting with `/` is refused: `sort /secret.txt`,
  `sort /c/Users/me/outside/secret.txt`, `sort /M:100` ([#1738](https://github.com/llm4s/llm4s/issues/1738)).
- **Which `sort` and `findstr` run** ([#1738](https://github.com/llm4s/llm4s/issues/1738)). The `/` rules above
  read arguments as the native tools in the system directory read them; a GNU or MSYS2 build would open
  `/c/Users/...` as `C:\Users\...`. The runner searches the system directory before `PATH` and never its own
  working or Java directory (see [Program resolution](#program-resolution), [#1790](https://github.com/llm4s/llm4s/issues/1790)),
  so it starts the system directory's `sort.exe` and `findstr.exe` whenever that directory has them. On a host whose
  system directory lacks them (some minimal images), `PATH` is reached; the switch rules still hold there.
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
without a repository of its own gets `not a git repository`. `GIT_CEILING_DIRECTORIES` is a list split on the
path-list separator (`:` on POSIX, `;` on Windows) with no escaping, so where the workspace root's parent path - as
configured or with links resolved - holds that character, git is refused (`PATH_ESCAPE_ATTEMPT`) rather than run
with a ceiling it would misread. On every platform, `git` is also refused (`PATH_ESCAPE_ATTEMPT`) when the nearest
`.git` between the working directory and the workspace root is not a directory, or is one whose real path lies
outside the workspace (a `gitdir:` file, a link or a Windows junction points git at a repository elsewhere), and an
argument starting with `:` is refused (`ARGUMENT_NOT_ALLOWED`): pathspec magic (`:/`, `:(top)`, `:!x`) and index
paths (`:a.txt`) are resolved from the repository's top level, not the working directory.

### git's own files

git reads the repository's `.git/config`, its attributes (`.gitattributes`, `.git/info/attributes`) and its hooks,
and runs the programs they name. Before [#1721](https://github.com/llm4s/llm4s/issues/1721) an agent that could write
files, or a repository cloned into the workspace already hostile, made an allowed read run a program:

| Route | Run by | Defence | Test (`GitConfigRoutesSpec`) |
|-------|--------|---------|------------------------------|
| `core.fsmonitor` | `status`, `diff`, `ls-files`, `blame` | `-c core.fsmonitor=false` | not run core.fsmonitor |
| `.git/hooks/post-index-change`, `core.hooksPath` | `status`, `diff` (index refresh) | `-c core.hooksPath=<empty dir>`, `GIT_OPTIONAL_LOCKS=0` | not run a hook |
| `diff.external`, `diff.<driver>.command` | `diff` | `--no-ext-diff`, `-c diff.external=`, driver blanked | not run diff.external |
| `diff.<driver>.textconv` | `diff`, `log -p`, `show`, `blame` | `--no-textconv`, driver blanked | not run a textconv driver |
| `filter.<driver>.clean` / `smudge` / `process` via `.gitattributes` or `.git/info/attributes` | `status`, `diff`, `ls-files -m`, `blame`, `log -p`, `show` | driver blanked (`required=false`) | clean/smudge filter; process filter |
| `gpg.program` (`log.showSignature`) | `log`, `show` | `-c log.showSignature=false`, `gpg.*.program` a directory | not run gpg.program |
| Partial clone lazy fetch (`remote.<n>.url` `ext::`, `core.sshCommand`, a remote helper) | `show`, `log -p`, `blame` | `GIT_ALLOW_PROTOCOL` naming none, `GIT_NO_LAZY_FETCH=1` | not fetch a missing object |
| A submodule's own configuration (`diff.submodule=diff`, a gitlink's drivers) | `status`, `diff`, `log -p`, `show` | `--ignore-submodules=all`, `-c diff.ignoreSubmodules=all`; the agent's `--ignore-submodules` refused | not start git in a submodule |
| A bare repository written into the workspace | any | `-c safe.bareRepository=explicit` | not use a bare repository |
| `$HOME/.gitconfig`, `$XDG_CONFIG_HOME/git/config`, system config | any | `GIT_CONFIG_GLOBAL` the null device, `GIT_CONFIG_NOSYSTEM`, `GIT_ATTR_NOSYSTEM`, `HOME` / `XDG_CONFIG_HOME` an empty directory | `GitSandboxUnitSpec` |
| `core.pager`, `pager.<cmd>` | a terminal only | `--no-pager`, `GIT_PAGER=cat` | every route at once |
| `include.path` / `includeIf`, `hook.*` | any | refused (`GIT_CONFIG_NOT_ALLOWED`) | refuse a repository whose configuration includes another file |

`core.editor`, `sequence.editor`, `alias.*` (which cannot shadow a built-in subcommand), `credential.helper` and
`core.askPass` are not reached by the allowed subcommands with no transport; the test with every route planted at
once includes them. A `%G?` or `%(signature)` format, which would also verify a signature, is refused for its `%`.

So every `git` the runner starts has:

- **Environment**: `GIT_CONFIG_NOSYSTEM=1`, `GIT_CONFIG_GLOBAL` the null device (`/dev/null`, `NUL` on Windows),
  `GIT_ATTR_NOSYSTEM=1`, `HOME` and `XDG_CONFIG_HOME` an empty directory the runner made outside the workspace (git is
  refused, `EXECUTION_FAILED`, if it is not empty or lies inside the workspace), `GIT_TERMINAL_PROMPT=0`,
  `GIT_PAGER=cat`, `PAGER=cat`, `GIT_OPTIONAL_LOCKS=0`, `GIT_NO_LAZY_FETCH=1` and `GIT_ALLOW_PROTOCOL` naming no
  transport, which overrides any `protocol.<name>.allow` the repository sets. The system and global configuration of
  whoever runs the runner no longer apply, so a `safe.directory` set there does not either: run the runner as the
  workspace's owner.
- **Before the subcommand**: `--no-pager` and `-c core.fsmonitor=false`, `-c core.hooksPath=<empty dir>`,
  `-c diff.external=`, `-c log.showSignature=false`, `gpg.program`, `gpg.openpgp.program`, `gpg.x509.program` and
  `gpg.ssh.program` set to the empty directory (which cannot be run), `-c diff.ignoreSubmodules=all`,
  `-c safe.bareRepository=explicit`, `-c protocol.allow=never`. Command-line configuration wins over every file.
- **After the subcommand**: `--no-ext-diff --no-textconv --ignore-submodules=all` for `diff`, `log` and `show`;
  `--ignore-submodules=all` for `status`; `--no-textconv` for `blame` and `grep`. The agent cannot undo them:
  `--ext-diff`, `--textconv` and `--ignore-submodules` are refused.
- **Drivers**: a filter or diff driver is named by an attribute, which can be anywhere in the tree, and defined in
  the configuration. Just before the command the runner lists that configuration (`git config --list --no-includes
  --show-scope -z`, same directory and environment) and, for every driver it defines, adds
  `-c filter.<name>.clean= -c filter.<name>.smudge= -c filter.<name>.process= -c filter.<name>.required=false`
  (git then passes content through) or `-c diff.<name>.textconv= -c diff.<name>.command=`. An `include.path` or
  `includeIf` (an included file may sit in the work tree and change after the listing), a `hook.*` key, or a driver
  name holding `=`, `"` or a control character refuses the command (`GIT_CONFIG_NOT_ALLOWED`); so does a listing
  that fails, takes more than ten seconds or is over 1 MB (`EXECUTION_FAILED`).

Writes into `.git` are refused as well, which keeps an agent from planting any of this in a repository that is not
hostile to begin with: `writeFile` and `modifyFile` refuse (`PATH_NOT_ALLOWED`), and every program but the read-only
ones (`ls`, `cat`, `grep`, `find`, `head`, `tail`, `wc`, `sort`, `uniq`, `diff`, `git`, `dir`, `type`, `findstr`,
...) is refused (`ARGUMENT_NOT_ALLOWED`) when an argument names `.git` or a path inside one - as written, or where it
really leads through links. Names are matched as a file system may take them for `.git`: ignoring case and Unicode
normalisation, the code points HFS+ ignores, an NTFS stream suffix, trailing dots and spaces, and 8.3 short names
(`GIT~1`); `.gitignore`, `.gitattributes` and `.github` are ordinary names. A `.git` file anywhere (`gitdir:`) is
refused the same way.

Limits: a repository that arrives with `core.worktree`, `.git/commondir` or `.git/objects/info/alternates` still
points git at files outside (git reads them, runs nothing). A program added with `WORKSPACE_EXTRA_COMMANDS` that
writes files without naming them can still write into `.git`; the layers above still keep git from running what it
writes. The configuration is listed just before the command, so a concurrent command that swaps in another
repository between the two is not seen.

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
