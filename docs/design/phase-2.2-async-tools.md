# Phase 2.2: Async Tool Execution

> **Superseded:** `runWithStrategy` and the agent's tool execution strategies described here were removed in [#1328](https://github.com/llm4s/llm4s/issues/1328); parallel tool calls are bounded by `RunBudgets.maxConcurrency` (see [typed-agent-runtime-design.md §4.13](typed-agent-runtime-design.md)). The agent event streaming it mentions returns with [#1329](https://github.com/llm4s/llm4s/issues/1329). Kept as history.

> **Status:** Complete
> **Last Updated:** 2025-11-26
> **Related:** Agent Framework Roadmap

## Executive Summary

Phase 2.2 adds asynchronous tool execution to the agent framework, enabling:
- Parallel execution of independent tools
- Non-blocking tool operations (HTTP calls, database queries)
- Better resource utilization for multi-tool agents
- Configurable execution strategies (parallel vs sequential)

## Motivation

### Current Limitations

The current tool execution is **synchronous and sequential**:

```scala
// Current: Each tool blocks until complete
val toolMessages = toolCalls.map { toolCall =>
  val result = toolRegistry.execute(request)  // BLOCKS
  ToolMessage(result, toolCall.id)
}
```

**Problems:**
1. Tool 2 waits for Tool 1 even if they're independent
2. HTTP/IO-bound tools block the entire agent
3. Multi-tool queries are slow (latency adds up)
4. No parallelization even when safe

### Benefits of Async Execution

1. **Parallel Execution**: Independent tools run simultaneously
2. **Non-blocking**: Agent can progress while tools execute
3. **Better Latency**: Multi-tool queries complete faster
4. **Resource Efficiency**: IO-bound tools don't waste CPU

## Architecture

### Design Principles

1. **Backward Compatible**: Existing sync tools continue to work
2. **Opt-in Async**: New async tools via separate trait
3. **Configurable Strategy**: Choose parallel or sequential
4. **Type Safe**: Use existing `AsyncResult[A]` type

### Execution Strategies

```scala
sealed trait ToolExecutionStrategy
object ToolExecutionStrategy {
  /** Execute tools one at a time (current behavior) */
  case object Sequential extends ToolExecutionStrategy

  /** Execute all tools in parallel */
  case object Parallel extends ToolExecutionStrategy

  /** Execute tools in parallel with concurrency limit */
  case class ParallelWithLimit(maxConcurrency: Int) extends ToolExecutionStrategy
}
```

### AsyncToolFunction

New trait for tools that can execute asynchronously:

```scala
trait AsyncToolFunction[T, R] {
  def name: String
  def description: String
  def schema: SchemaDefinition[T]

  /** Execute asynchronously, returning Future[Result[R]] */
  def executeAsync(args: ujson.Value)(implicit ec: ExecutionContext): AsyncResult[ujson.Value]
}
```

### Unified Tool Execution

The `ToolRegistry` provides unified execution:

```scala
class ToolRegistry(tools: Seq[ToolFunction[_, _]]) {

  /** Synchronous execution (existing) */
  def execute(request: ToolCallRequest): Either[ToolCallError, ujson.Value]

  /** Async execution - wraps sync tools if needed */
  def executeAsync(request: ToolCallRequest)(implicit ec: ExecutionContext): Future[Either[ToolCallError, ujson.Value]]

  /** Execute multiple tools with strategy */
  def executeAll(
    requests: Seq[ToolCallRequest],
    strategy: ToolExecutionStrategy = ToolExecutionStrategy.Parallel
  )(implicit ec: ExecutionContext): Future[Seq[Either[ToolCallError, ujson.Value]]]
}
```

### Agent Integration

New method in Agent for async tool processing:

```scala
class Agent(client: LLMClient) {

  /** Run with configurable tool execution strategy */
  def runWithStrategy(
    query: String,
    tools: ToolRegistry,
    toolExecutionStrategy: ToolExecutionStrategy = ToolExecutionStrategy.Sequential,
    // ... other params
  ): Result[AgentState]

  /** Process tool calls asynchronously */
  private def processToolCallsAsync(
    state: AgentState,
    toolCalls: Seq[ToolCall],
    strategy: ToolExecutionStrategy,
    debug: Boolean
  )(implicit ec: ExecutionContext): Future[AgentState]
}
```

## Implementation Plan

### Phase 1: Core Infrastructure

1. Add `ToolExecutionStrategy` enum
2. Add `executeAsync` to `ToolRegistry`
3. Add `executeAll` for batch execution

### Phase 2: Agent Integration

1. Add `processToolCallsAsync` method
2. Add `runWithStrategy` method
3. Update streaming methods for async

### Phase 3: Async Tool Definition

1. Create `AsyncToolFunction` trait
2. Add `AsyncToolBuilder` for easy creation
3. Update `ToolRegistry` to handle both types

## Usage Examples

### Parallel Tool Execution

```scala
val result = agent.runWithStrategy(
  query = "Get weather in London, Paris, and Tokyo",
  tools = weatherTools,
  toolExecutionStrategy = ToolExecutionStrategy.Parallel
)
// All 3 weather calls execute simultaneously!
```

### Creating Async Tools

```scala
val asyncWeatherTool = AsyncToolBuilder[WeatherInput, WeatherOutput](
  name = "get_weather_async",
  description = "Get weather asynchronously",
  schema = weatherSchema
).withAsyncHandler { extractor =>
  extractor.getString("city").fold(
    error => Future.successful(Left(error)),
    city => weatherApi.getWeather(city)  // Returns Future[Result[WeatherOutput]]
  )
}.build()
```

### Mixed Sync/Async Registry

```scala
val registry = new ToolRegistry(
  syncTools = Seq(calculatorTool, dateTool),
  asyncTools = Seq(asyncWeatherTool, asyncSearchTool)
)

// executeAsync works for both - sync tools are wrapped
registry.executeAsync(request)
```

## Backward Compatibility

- Existing `Agent.run()` continues to work unchanged
- Existing sync tools work with no modifications
- `ToolRegistry.execute()` remains synchronous
- New async features are opt-in via `runWithStrategy()`

## Testing Strategy

1. **Unit Tests**: Async execution, strategy selection
2. **Integration Tests**: Mixed sync/async tool execution
3. **Performance Tests**: Parallel vs sequential timing
4. **Concurrency Tests**: Thread safety, race conditions

## File Structure

```
modules/core/src/main/scala/org/llm4s/
├── toolapi/
│   ├── ToolExecutionStrategy.scala   # NEW: Execution strategies (Sequential, Parallel, ParallelWithLimit)
│   └── ToolRegistry.scala            # Updated: Add executeAsync(), executeAll()
└── agent/
    └── Agent.scala                   # Updated: Add runWithStrategy(), continueConversationWithStrategy()
```

## Implementation Notes

### What Was Implemented

1. **ToolExecutionStrategy** - Sealed trait with three strategies:
   - `Sequential` - Execute one at a time (default, safest)
   - `Parallel` - Execute all simultaneously (fastest)
   - `ParallelWithLimit(n)` - Max n concurrent executions

2. **ToolRegistry Enhancements**:
   - `executeAsync()` - Single tool async execution
   - `executeAll()` - Batch execution with configurable strategy
   - Private helpers: `executeSequential()`, `executeParallel()`, `executeWithLimit()`

3. **Agent Methods**:
   - `runWithStrategy()` - Run agent with parallel tool execution
   - `continueConversationWithStrategy()` - Continue conversation with strategy
   - `processToolCallsAsync()` - Internal async tool processing

### What Was Deferred

The following features from the original design were deferred for future work:
- `AsyncToolFunction` trait for natively async tools
- `AsyncToolBuilder` for creating async tools
- Mixed sync/async tool registry

These weren't needed for the core use case of parallelizing existing synchronous tools.

## Samples

- **ToolRegistry example**: `samples/runMain org.llm4s.samples.toolapi.ParallelToolExecutionExample`
- **Agent example**: `samples/runMain org.llm4s.samples.agent.AsyncToolAgentExample`

## Tests

- `core/testOnly org.llm4s.toolapi.AsyncToolExecutionSpec` - 11 tests covering all strategies
