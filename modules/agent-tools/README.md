# llm4s-agent-tools

Ready-to-use tools for common agent tasks: core utilities, file system access, HTTP requests,
shell commands, and web search. They implement `ToolFunction` and work with plain tool calling
through `ToolRegistry`, with or without an `Agent`.

## Quick Start

Add to your `build.sbt`:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-agent-tools" % "<version>"
```

```scala
import org.llm4s.toolapi._
import org.llm4s.toolapi.builtin._

val result = for {
  tools <- BuiltinTools.coreSafe
} yield {
  val registry = new ToolRegistry(tools)
  registry.execute(
    ToolCallRequest(
      functionName = "calculator",
      arguments = ujson.Obj("operation" -> "sqrt", "a" -> 144.0)
    )
  )
}
```

## Main packages

| Package | Contains |
|---|---|
| `org.llm4s.toolapi.builtin` | `BuiltinTools` - pre-configured bundles (`coreSafe`, `withHttpSafe()`, `withFilesSafe()`, `developmentSafe()`, `customSafe(...)`) |
| `org.llm4s.toolapi.builtin.core` | `CalculatorTool`, `DateTimeTool`, `UUIDTool`, `JSONTool` - no external dependencies |
| `org.llm4s.toolapi.builtin.filesystem` | `ReadFileTool`, `WriteFileTool`, `ListDirectoryTool`, `FileInfoTool`, with `FileConfig`/`WriteConfig` for path allow/block lists |
| `org.llm4s.toolapi.builtin.http` | `HTTPTool`, with `HttpConfig` for domain/method restrictions |
| `org.llm4s.toolapi.builtin.shell` | `ShellTool`, with `ShellConfig` (e.g. `ShellConfig.readOnly()`) for an allowlist of commands |
| `org.llm4s.toolapi.builtin.search` | `DuckDuckGoSearchTool`, `BraveSearchTool`, `ExaSearchTool` |
| `org.llm4s.config` | `ToolsConfigLoader`, `ToolsConfigKeys` - reads the search tools' API keys |

## Configuration

The search tools read their settings from `llm4s.tools.*` (see
[`ToolsConfigKeys`](src/main/scala/org/llm4s/config/ToolsConfigKeys.scala)):

| Key | Environment variable | Required by |
|---|---|---|
| `llm4s.tools.brave.apiKey` | `BRAVE_SEARCH_API_KEY` | `BraveSearchTool` |
| `llm4s.tools.exa.apiKey` | `EXA_API_KEY` | `ExaSearchTool` |

Load them with, for example, `ToolsConfigLoader.loadBraveSearchTool()`.

## Learn more

See the [agents guide](../../docs/guide/agents/index.md#built-in-tools) for the full list of
available tools and how they combine with `Agent`.
