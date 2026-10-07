---
layout: page
title: Built-in Tools
parent: User Guide
nav_order: 10
---

# Built-in Tools
{: .no_toc }

Ready-made tools for agents: a calculator, the date and time, UUIDs, JSON, files, HTTP, a shell and web search.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

The built-in tools live in the `llm4s-agent-tools` module (see
[installation](../getting-started/installation.md#for-built-in-tools-web-search-http-filesystem-shell)). They depend
on `llm4s-core` only, not on the agent runtime, so they work with plain tool calling through a `ToolRegistry` as well
as with an `Agent`. Some of them can read your files, call your network or run programs, so decide which ones a model
gets before you register anything: [section 6](#6-safety-what-each-tool-can-do) says what each can do.

What this page says is checked by tests. The bundle tables, the parameter table, the defaults and the safety section
are asserted against the real code by
[`BuiltinToolsGuideSpec`](https://github.com/llm4s/llm4s/blob/main/modules/agent-tools/src/test/scala/org/llm4s/toolapi/builtin/BuiltinToolsGuideSpec.scala).
The snippets are compiled and run there too, with configuration passed in explicitly where a snippet would read
your environment, except the agent snippet, which needs both `llm4s-agent` and `llm4s-agent-tools` and so is run by
[`BuiltinToolsGuideAgentSpec`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/test/scala/org/llm4s/samples/toolapi/BuiltinToolsGuideAgentSpec.scala)
in `samples`. The last snippet, which refers to a tool of yours, is the one exception. If you change the page, change
the specs.

## 1. Pick a bundle

`BuiltinTools` builds a set of tools for you. Every method returns a `Result`, because building a tool can fail.

| Bundle | Tools it contains | Reach |
|--------|-------------------|-------|
| `BuiltinTools.coreSafe` | `get_current_datetime`, `calculator`, `generate_uuid`, `json_tool` | None: no files, no network, no processes |
| `BuiltinTools.withHttpSafe()` | the core tools, plus `http_request` | Read-only HTTP |
| `BuiltinTools.withFilesSafe()` | the above, plus `read_file`, `list_directory`, `file_info` | Read-only HTTP and files |
| `BuiltinTools.developmentSafe()` | the above, plus `write_file`, `shell_command` | Files (read and write) and a shell |
| `BuiltinTools.customSafe(...)` | the core tools, plus a tool for each configuration you pass | Exactly what you configure |

Two things are in no bundle: the web search tools ([section 4](#4-web-search)) and `WeatherTool`
([section 7](#7-things-to-know)). You add them yourself.

Start from the smallest bundle that does the job. `customSafe` is the one to use in production, because it forces
you to say what each tool may touch:

```scala
import org.llm4s.toolapi.builtin.BuiltinTools
import org.llm4s.toolapi.builtin.filesystem.FileConfig
import org.llm4s.toolapi.builtin.http.HttpConfig

val tools = BuiltinTools.customSafe(
  fileConfig = Some(FileConfig(allowedPaths = Some(Seq("/srv/agent-data")))),
  httpConfig = Some(HttpConfig.restricted(Seq("api.example.com")))
) // Result[Seq[ToolFunction[_, _]]]
```

## 2. Register the tools

### With a `ToolRegistry`

```scala
import org.llm4s.toolapi.{ ToolCallRequest, ToolRegistry }
import org.llm4s.toolapi.builtin.BuiltinTools

BuiltinTools.coreSafe.foreach { tools =>
  val registry = new ToolRegistry(tools)

  registry.execute(ToolCallRequest("calculator", ujson.Obj("operation" -> "multiply", "a" -> 6, "b" -> 7))) match {
    case Right(json)  => println(json("result")) // 42.0
    case Left(error)  => println(error.getFormattedMessage)
  }
}
```

`registry.getOpenAITools()` gives the tool definitions in the format every client takes. `execute` returns
`Either[ToolCallError, ujson.Value]`: a refused or failed call is a `Left` you can read, never an exception. For
example, dividing by zero comes back as `Left(...)` whose `getFormattedMessage` includes "Division by zero".

### With an `Agent`

```scala
import org.llm4s.agent.Agent
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.builtin.BuiltinTools

// `client` is an LLMClient: see Basic Usage for how to create one
val result = for {
  tools <- BuiltinTools.coreSafe
  agent <- Agent.builder("calculator-agent", client).withTools(new ToolRegistry(tools)).build()
  state <- agent.run("What is 15% of 850?")
} yield state

result.map(_.answer) // Right(Some("...")) when the run completed
```

`answer` is `None` when the run did not complete (a guardrail blocked it, or it hit the step limit): read `status`
to see why. The runnable version is
[`BuiltinToolsAgentExample`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/agent/BuiltinToolsAgentExample.scala).

## 3. The tools

The names are what the model sees, and the parameters are what it can send. A parameter in **bold** is required:
a call without it comes back as a `Left` naming it. The others have defaults.

| Tool | Bundle | Parameters | What it returns |
|------|--------|------------|-----------------|
| `get_current_datetime` | core | `timezone` (default UTC), `format` (`iso` or `human`) | `datetime`, `timezone`, `timestamp`, `iso8601`, `components` |
| `calculator` | core | **`operation`** (`add`, `subtract`, `multiply`, `divide`, `power`, `sqrt`, `percentage`, `abs`, `min`, `max`, `modulo`), **`a`**, `b` (needed by the two-operand operations) | `expression`, `result`, `formatted` |
| `generate_uuid` | core | `count` (1 to 10, default 1), `format` (`standard` or `compact`) | `uuids`: each with `uuid`, `version`, `variant` |
| `json_tool` | core | **`operation`** (`parse`, `format`, `query`, `validate`), **`json`**, `path` (for `query`) | `success`, `result`, `formatted` |
| `http_request` | http | **`url`**, `method`, `headers`, `body`, `content_type` | `statusCode`, `headers`, `body`, `contentType`, `truncated`, and more |
| `read_file` | files | **`path`**, `max_lines`, `encoding` | `path`, `content`, `size`, `lines`, `truncated` |
| `list_directory` | files | **`path`**, `max_entries` (at most 100), `include_hidden` | `entries`, `totalFiles`, `totalDirectories`, `truncated` |
| `file_info` | files | **`path`** | `exists`, `size`, `sizeHuman`, timestamps, permissions, `isSymlink`, `extension` |
| `write_file` | development | **`path`**, **`content`**, `append`, `encoding` | `path`, `bytesWritten`, `created`, `appended` |
| `shell_command` | development | **`command`** | `exitCode`, `stdout`, `stderr`, `truncated`, `timedOut` |
| `duckduckgo_search` | none | **`search_query`** | `abstract_`, `answer`, `relatedTopics`, and more |
| `brave_web_search` | none | **`search_query`** | web results (`brave_image_search`, `brave_video_search` and `brave_news_search` exist too) |
| `exa_search` | none | **`query`** | results with `title`, `url`, `text`, `highlights`, and more |

`json_tool` queries use dot notation for objects and brackets for arrays: `data.users[0].name`.

## 4. Web search

Three search tools are not in any bundle, because each calls a third party and two of them need a key. Their settings
are read from `llm4s.tools.*` with `ToolsConfigLoader`, then passed to the tool:

```scala
import org.llm4s.config.ToolsConfigLoader
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.builtin.BuiltinTools
import org.llm4s.toolapi.builtin.search.DuckDuckGoSearchTool

val registry = for {
  config <- ToolsConfigLoader.loadDuckDuckGoSearchTool()
  search <- DuckDuckGoSearchTool.create(config)
  core   <- BuiltinTools.coreSafe
} yield new ToolRegistry(core :+ search)
```

The Brave and Exa tools are built the same way:

```scala
import org.llm4s.config.ToolsConfigLoader
import org.llm4s.toolapi.builtin.search.{ BraveSearchCategory, BraveSearchTool, BraveWebSearchResult, ExaSearchTool }

val brave = ToolsConfigLoader
  .loadBraveSearchTool()
  .flatMap(config => BraveSearchTool.create[BraveWebSearchResult](config, BraveSearchCategory.Web))

val exa = ToolsConfigLoader.loadExaSearchTool().flatMap(config => ExaSearchTool.create(config))
```

Brave also has image, video and news categories. These are the keys, as `llm4s-agent-tools` ships them in its
`reference.conf` (set them in your `application.conf`, or let the environment variable fill them):

| Tool | Key under `llm4s.tools` | Default | Environment variable |
|------|-------------------------|---------|----------------------|
| DuckDuckGo | `duckduckgo.apiUrl` | `https://api.duckduckgo.com/` | `DUCK_DUCK_GO_SEARCH_API_URL` |
| Brave | `brave.apiKey` | none: required | `BRAVE_SEARCH_API_KEY` |
| | `brave.apiUrl` | `https://api.search.brave.com/res/v1` | `BRAVE_SEARCH_API_URL` |
| | `brave.count` | `5` | `BRAVE_SEARCH_COUNT` |
| | `brave.safeSearch` | `moderate` | `BRAVE_SAFE_SEARCH` |
| Exa | `exa.apiKey` | none: required | `EXA_API_KEY` |
| | `exa.apiUrl` | `https://api.exa.ai` | `EXA_API_URL` |
| | `exa.numResults` | `10` | `EXA_NUM_RESULTS` |
| | `exa.searchType` | `auto` (or `neural`, `fast`, `deep`) | `EXA_SEARCH_TYPE` |
| | `exa.maxCharacters` | `500` | `EXA_MAX_CHARACTERS` |

`ExaSearchTool.create` rejects an empty API key when it builds the tool, so a missing key is an error at start-up and
not in the middle of a conversation. DuckDuckGo needs no key and is best for definitions, facts and quick lookups,
not for general web search. Whatever the model searches for is sent to the search provider.

## 5. Configure the tools

Each tool that can reach outside the process takes a configuration. The defaults below are what you get when you do
not pass one.

### Files: `FileConfig` and `WriteConfig`

| Setting | `FileConfig` (read tools) | `WriteConfig` (`write_file`) |
|---------|---------------------------|------------------------------|
| `allowedPaths` | `None`: any path outside `blockedPaths` | **required**: no default |
| `blockedPaths` | `/etc`, `/var`, `/sys`, `/proc`, `/dev` (wins over `allowedPaths`) | not a setting |
| `maxFileSize` | 1 MB | 10 MB |
| `followSymlinks` | `false` | not a setting |
| `allowOverwrite` | not a setting | `false` |
| `createDirectories` | not a setting | `true` |

```scala
import org.llm4s.toolapi.builtin.BuiltinTools
import org.llm4s.toolapi.builtin.filesystem.{ FileConfig, WriteConfig }

val tools = BuiltinTools.customSafe(
  fileConfig = Some(FileConfig(allowedPaths = Some(Seq("/srv/agent-data")), maxFileSize = 256 * 1024)),
  writeConfig = Some(WriteConfig(allowedPaths = Seq("/srv/agent-data/out")))
)
```

A path is judged by where it really is: `..` is removed and symbolic links are resolved, then that real location is
compared with each allowed or blocked entry (resolved the same way), one path component at a time. `/srv/agent-data`
covers `/srv/agent-data/notes.txt` but not `/srv/agent-data-secret`, and a link inside an allowed directory that leads
outside it is refused, for reading, listing and writing alike. A link that cannot be resolved (a dangling link) is
refused. `followSymlinks = false` (the default) additionally refuses a path that is itself a link when reading or
listing; it does not decide whether a link may lead out of the allowed area, which is always refused. The tools open
the resolved path; a directory swapped for a link between the check and the open is a race this narrows and does not
close.

`developmentSafe(workingDirectory, fileAllowedPaths)` reads only inside `workingDirectory` when you give one, and
anywhere outside the blocklist when you do not. It writes inside `fileAllowedPaths` (default `/tmp`) and the working
directory, and it **does** allow overwriting.

The default blocklist includes `/var`, which on macOS is where the system temporary directory lives
(`/var/folders/...`): a file there is refused even if you put its directory in `allowedPaths`.

### HTTP: `HttpConfig`

| Setting | Default |
|---------|---------|
| `allowedMethods` | `GET`, `HEAD` |
| `allowedDomains` | `None`: any domain that is not blocked |
| `blockedDomains` | `localhost`, `127.0.0.1`, `0.0.0.0`, `::1`, the cloud metadata hosts and `169.254.169.254` |
| `blockInternalIPs` | `true` |
| `followRedirects` | `false` |
| `timeout` | 30 seconds |
| `maxResponseSize` | 10 MB: at most this many bytes of the body are read; the rest is never read, and `truncated` is `true` |

`HttpConfig.restricted(Seq("api.example.com"))` allows only those domains. `HttpConfig.withWriteMethods()` adds
`POST`, `PUT`, `DELETE` and the rest, and `HttpConfig.unsafe` also turns the address checks off: use that only in a
sandbox.

### Shell: `ShellConfig`

| Setting | Default |
|---------|---------|
| `allowedCommands` | none: an empty list allows nothing |
| `workingDirectory` | the process's own |
| `timeout` | 30 seconds |
| `maxOutputSize` | 100,000 characters |
| `environment` | none: extra variables, set on top of the environment the command receives |
| `inheritedEnvironment` | `PATH`, `LANG`, `LC_ALL`, `TERM` and `SystemRoot` (those that exist); `development()` passes the whole environment |
| `pathPolicy` | none: the file-like arguments of a command are not checked |

`ShellConfig.readOnly()` allows `ls`, `cat`, `head`, `tail`, `pwd`, `echo`, `wc`, `date`, `whoami`, `which` and
`file`. `ShellConfig.readOnlyWithin(policy)` is the same list with the working directory and every file-like argument
held to a `FileConfig` rule, the one the file tools use. `ShellConfig.development()` adds `git`, `sbt`, `make`, `npm`, `grep`, `find`, `cp`, `mv`, `rm` and more:
read the next section before you use it.

## 6. Safety: what each tool can do

A tool call is a request from a model, and a model can be wrong, confused or steered by text it read. Pick the
bundle by what you would be willing to let it do unsupervised.

| Bundle | A model given it can |
|--------|----------------------|
| `coreSafe` | compute, read the clock, make UUIDs and parse the JSON it is given. Nothing leaves the process. |
| `withHttpSafe()` | all of the above, and fetch pages from the public internet with `GET` and `HEAD`: your server's IP address is the one the remote site sees. |
| `withFilesSafe()` | all of the above, and read files: by default any the process can read outside the blocklist (`/etc`, `/var`, ...), so set `allowedPaths`. |
| `developmentSafe()` | all of the above, and write files, and run any program on the allowlist, which for `development()` is most of a developer's toolbox. |

What the controls do, and where they stop:

- **HTTP** refuses methods outside `allowedMethods`, a scheme other than `http` and `https`, any domain outside
  `allowedDomains`, `localhost`, loopback, the cloud metadata addresses, and private ranges such as `10.x`, `172.16.x`
  and `192.168.x`, all before sending a request. Redirects are not followed unless you turn that on. The address
  check resolves the host name, and the connection then resolves it again, so a domain whose DNS answer changes in
  between (DNS rebinding) can pass the check with a public address and connect to a private one. The request still
  goes out from your network, so do not give it to a model that handles untrusted text next to credentials or
  internal services the server can reach; where that matters, also block private ranges at the network level, for
  example with an egress proxy or firewall.
- **Files**: `allowedPaths` and `blockedPaths` judge the real location of a path (links resolved, compared one
  component at a time), so a link cannot lead out of an allowed directory and a sibling that shares a name prefix is
  not inside it. Treat them as a containment check on the file tools, not as a sandbox: the check and the open are
  two steps, which this narrows and does not close, and the shell tool is a separate matter (below). Allow one
  directory made for the agent, and when the files matter, run the process as a user that cannot read anything
  else, or in a container.
- **Shell**: the command is split into words and started directly, **without a shell**. `&&`, `;`, `|`, `>` and
  `$VAR` reach the program as ordinary text and are not interpreted, and only the first word is checked against
  `allowedCommands`. That stops a command from chaining into another one. It does not limit what an allowed
  program does:
  - `readOnly()` is an allowlist of program names, not read-only execution. Its programs only read in ordinary use,
    but most options are passed through unchecked: `date -s` sets the clock when the process is allowed to. The
    options that write a file or read a list of files are refused (`file -C`, `-m` and `-f`, and `wc --files0-from`).
    `cat`, `head` and `tail` can read any file the process can read, **so the file settings above do not apply to the
    shell** unless you use `ShellConfig.readOnlyWithin(policy)`, which holds the working directory and each file-like
    argument to that rule. A command that walks directories itself (`ls -R`, `grep -r`, `find`) is checked only at the
    path it starts from.
  - `development()` is not a sandbox: `sbt`, `make`, `npm`, `git`, `find` and `env` can run arbitrary programs, so a
    model given it can do anything the process can.
  - The started program receives a scrubbed environment: only `PATH`, `LANG`, `LC_ALL`, `TERM` and `SystemRoot`
    (those that exist) plus whatever you put in `environment`, so provider API keys in your process's environment do
    not reach it. `development()` passes the whole environment through, because build tools need it.
- **Search** sends the query text to the search provider.

A reasonable starting point for an agent that answers questions about some documents is `customSafe` with
`fileConfig` set to one dedicated directory, no `writeConfig`, no `shellConfig`, and `httpConfig` set to the domains it
needs. Add the shell only for a developer's own machine.

## 7. Things to know

- **Failures are values.** A refused call returns a `Left`. The message names the reason: for example `Access denied:
  path '...' is not allowed`, `HTTP method 'POST' is not allowed. Allowed: GET, HEAD`, `SSRF_BLOCKED: domain '...' is
  not allowed` or `Command 'rm' is not allowed`. The registry hands the message back to the model, so it can try
  something else.
- **`WeatherTool` is a demo.** It lives in `org.llm4s.toolapi.tools`, returns the same fixed reading (sunny, 22.5)
  for every location and calls no weather service. Use it to see tool calling work, not for weather.
- **The shell lists are POSIX programs.** `readOnly()` and `development()` name programs such as `ls` and `cat`, so
  on Windows give `ShellConfig` a list of your own.
- **A call with a missing parameter is a `Left`.** For example `calculator` without `a` comes back as an error that
  names the parameter, and nothing runs.

## 8. Your own tools

The built-in tools are built with `ToolBuilder`, the same way you build yours, and they register in the same
`ToolRegistry`. Build your tool, then add it to the sequence:

```scala
// myTool: a ToolFunction you built with ToolBuilder
val registry = BuiltinTools.coreSafe.map(core => new ToolRegistry(core :+ myTool))
```

See [Agents](agents/#agent-with-tools) for how a tool is defined and used, and
[Error Handling](error-handling) for working with the `Result` these methods return.

## Related

- [Agents](agents/): running tools in an agent loop, with guardrails
- [Error Handling](error-handling): `Result[A]` and `LLMError`
- [`BuiltinToolsExample`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/toolapi/BuiltinToolsExample.scala):
  every bundle in a runnable sample
