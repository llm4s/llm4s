# Phase 3.2: Built-in Tools Module

> **Status:** Complete
> **Last Updated:** 2025-11-26
> **Related:** Agent Framework Roadmap

## Executive Summary

Phase 3.2 adds a set of production-ready built-in tools to llm4s, enabling:
- Web search capabilities without external setup
- File system operations for agent workflows
- HTTP request tools for API integration
- Shell command execution (sandboxed)
- Date/time utilities
- Calculator/math operations

## Motivation

### Current Limitations

Users must implement common tools from scratch:
- Inconsistent quality across implementations
- Longer time-to-production
- Repeated boilerplate for standard operations

### Benefits of Built-in Tools

1. **Faster Development**: Common tools out of the box
2. **Best Practices**: Proper error handling, safety limits
3. **Consistency**: Well-tested, documented tools
4. **Security**: Sandboxed operations where appropriate

## Architecture

### Design Principles

1. **Opt-in**: All tools are imported explicitly, not auto-registered
2. **Configurable**: Tools accept configuration for limits, timeouts, etc.
3. **Safe by Default**: Filesystem and shell tools have safety limits
4. **No External Dependencies**: Core tools work without API keys
5. **Extensible**: Easy to customize or extend built-in tools

### Tool Categories

#### 1. Core Utilities (No API Keys Required)

- **DateTimeTool**: Current date/time, timezone conversion, formatting
- **CalculatorTool**: Math operations (add, subtract, multiply, divide, sqrt, power, percentage)
- **JSONTool**: Parse, format, query, validate JSON data
- **UUIDTool**: Generate UUIDs (standard or compact format)

#### 2. File System Tools

- **ReadFileTool**: Read file contents (with size limits)
- **WriteFileTool**: Write/append to files (with path restrictions)
- **ListDirectoryTool**: List directory contents
- **FileInfoTool**: Get file metadata (size, modified date, etc.)

#### 3. HTTP Tools

- **HTTPTool**: General HTTP client (GET, POST, PUT, DELETE)
  - Domain allowlist/blocklist
  - Method restrictions
  - Timeout configuration

#### 4. Shell Tools (Sandboxed)

- **ShellTool**: Execute shell commands with restrictions
  - Configurable allowed commands (allowlist)
  - Timeout limits
  - Output size limits
  - Working directory configuration

#### 5. Search Tools (No API Keys Required)

- **WebSearchTool**: Web search via DuckDuckGo Instant Answer API

## Implementation

### Package Structure

```
modules/core/src/main/scala/org/llm4s/toolapi/builtin/
├── package.scala           # Package object with tool bundles
├── BuiltinTools.scala      # All tools registry with factory methods
├── core/
│   ├── package.scala       # Core utilities package
│   ├── DateTimeTool.scala
│   ├── CalculatorTool.scala
│   ├── JSONTool.scala
│   └── UUIDTool.scala
├── filesystem/
│   ├── package.scala       # FileConfig, WriteConfig
│   ├── ReadFileTool.scala
│   ├── WriteFileTool.scala
│   ├── ListDirectoryTool.scala
│   └── FileInfoTool.scala
├── http/
│   ├── HTTPTool.scala
│   └── HttpConfig.scala
├── shell/
│   ├── ShellTool.scala
│   └── ShellConfig.scala
└── search/
    └── WebSearchTool.scala
```

### Tool Bundles

The `BuiltinTools` object provides pre-configured bundles for common use cases:

```scala
import org.llm4s.toolapi.builtin.BuiltinTools

// Core utilities only (always safe)
val coreTools = BuiltinTools.core

// Core + safe network tools (web search, HTTP)
val safeTools = BuiltinTools.safe()

// Safe + read-only file access
val fileTools = BuiltinTools.withFiles()

// All tools for development (use with caution in production)
val devTools = BuiltinTools.development()

// Custom configuration
val customTools = BuiltinTools.custom(
  includeSearch = true,
  httpConfig = Some(HttpConfig.readOnly()),
  fileConfig = Some(FileConfig()),
  writeConfig = Some(WriteConfig(allowedPaths = Seq("/tmp"))),
  shellConfig = Some(ShellConfig.readOnly())
)
```

### Usage Examples

#### Basic Usage with Core Tools

```scala
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.builtin._

// Get core tools (DateTime, Calculator, UUID, JSON)
val tools = new ToolRegistry(BuiltinTools.core)

// Execute directly
val dateRequest = ToolCallRequest(
  functionName = "get_current_datetime",
  arguments = ujson.Obj("timezone" -> "America/New_York")
)
tools.execute(dateRequest) match {
  case Right(result) => println(result.render())
  case Left(error) => println(s"Error: $error")
}
```

#### With File System Tools

```scala
import org.llm4s.toolapi.builtin.filesystem._

val fileConfig = FileConfig(
  allowedPaths = Some(Seq("/tmp", System.getProperty("user.home"))),
  blockedPaths = Seq("/etc", "/var", "/sys", "/proc")
)

val writeConfig = WriteConfig(
  allowedPaths = Seq("/tmp"),
  allowOverwrite = true
)

val tools = BuiltinTools.custom(
  includeSearch = false,
  fileConfig = Some(fileConfig),
  writeConfig = Some(writeConfig)
)
```

#### With Shell Tools

```scala
import org.llm4s.toolapi.builtin.shell._

// Read-only shell (safe commands only)
val readOnlyConfig = ShellConfig.readOnly()

// Development shell (common dev tools)
val devConfig = ShellConfig.development()

// Custom allowlist
val customConfig = ShellConfig(
  allowedCommands = Seq("ls", "cat", "pwd", "echo"),
  timeout = 5.seconds,
  maxOutputSize = 10000,
  workingDirectory = Some("/home/user/project")
)
```

#### With HTTP Tools

```scala
import org.llm4s.toolapi.builtin.http._

// Read-only HTTP (GET, HEAD, OPTIONS only)
val readOnlyConfig = HttpConfig.readOnly()

// Restricted to specific domains
val restrictedConfig = HttpConfig.restricted(Seq("api.example.com"))

// Full HTTP with custom settings
val customConfig = HttpConfig(
  allowedMethods = Seq("GET", "POST"),
  allowedDomains = Some(Seq("api.example.com")),
  blockedDomains = Seq("localhost", "127.0.0.1"),
  timeout = 30.seconds,
  maxResponseSize = 1024 * 1024
)
```

#### With Agent

```scala
import org.llm4s.agent.Agent
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.builtin._

for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  tools = new ToolRegistry(BuiltinTools.safe())
  agent = new Agent(client)
  state <- agent.run("What is 15% of 850?", tools)
} yield state
```

## Safety Considerations

### File System Safety

1. **Size Limits**: Default 1MB read limit prevents memory exhaustion
2. **Path Restrictions**: Configurable allowed/blocked paths
3. **Blocked by Default**: System paths (/etc, /var, /sys, /proc, /dev) blocked
4. **Write Restrictions**: Write tool requires explicit path allowlist

### Shell Safety

1. **Command Allowlist**: Only explicitly allowed commands can run
2. **Timeout**: Commands timeout after configurable duration (default 30s)
3. **Output Limits**: Command output is truncated to prevent memory issues
4. **Working Directory**: Can be restricted to specific directory
5. **No Dangerous Commands by Default**: `readOnly()` only allows safe commands

### HTTP Safety

1. **Domain Allowlist/Blocklist**: Prevent SSRF attacks
2. **Localhost Blocked**: By default, localhost and internal IPs are blocked
3. **Response Size Limits**: Prevent memory exhaustion
4. **Timeout**: Configurable request timeout
5. **Method Restrictions**: Can limit to read-only methods (GET, HEAD, OPTIONS)

## Testing

All tools have comprehensive tests:

- **CoreToolsSpec**: Tests for DateTime, Calculator, UUID, JSON tools
- **FileSystemToolsSpec**: Tests for file read/write/list/info with path restrictions
- **HttpToolsSpec**: Tests for HTTP requests with domain/method restrictions
- **ShellToolsSpec**: Tests for shell execution with allowlist, timeout, truncation
- **BuiltinToolsSpec**: Tests for tool bundle factory methods

Total: 73 tests across 5 test suites, all passing on Scala 2.13 and 3.x.

## Samples

Two sample applications demonstrate built-in tools usage:

1. **BuiltinToolsExample**: Demonstrates direct tool usage without an LLM
   ```bash
   sbt "samples/runMain org.llm4s.samples.toolapi.BuiltinToolsExample"
   ```

> **Superseded:** configuration no longer reads `LLM_MODEL`; providers are named sections, whose keys come from each vendor's variable (`OPENAI_API_KEY`, ...) through `llm4s.credentials`; see [Configuration](../getting-started/configuration.md#named-provider-sections).

2. **BuiltinToolsAgentExample**: Demonstrates tools with an LLM agent
   ```bash
   export LLM_MODEL=openai/gpt-4o
   export OPENAI_API_KEY=sk-...
   sbt "samples/runMain org.llm4s.samples.agent.BuiltinToolsAgentExample"
   ```

## Success Criteria (All Complete)

- [x] All core utility tools implemented and tested
- [x] File system tools with proper safety limits
- [x] HTTP tools with SSRF protection
- [x] Shell tool with command allowlist
- [x] Web search tool (DuckDuckGo)
- [x] Documentation for each tool
- [x] Sample applications demonstrating usage
- [x] All tests passing on Scala 2.13 and 3.x
