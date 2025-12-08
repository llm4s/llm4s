# CLAUDE.md - AI Assistant Guide for LLM4S

## Project Overview

**LLM4S** (Large Language Models for Scala) is a framework for building LLM-powered applications in Scala with:
- Multi-provider support (OpenAI, Anthropic, Azure, Ollama)
- Type-safe design with `Result[A]` error handling
- Agent framework with tools, guardrails, handoffs, and memory
- Cross-compilation for Scala 2.13 and 3.x

**Tech Stack:** Scala 2.13/3.x, SBT, ScalaTest, Cats, uPickle, Docker

## Core Principles

1. **Use `Result[A]` instead of exceptions** - `type Result[+A] = Either[LLMError, A]`
2. **Use `ConfigReader` for all config** - Never use `sys.env` or `System.getenv` directly
3. **Use type-safe newtypes** - `ModelName`, `ApiKey`, `ConversationId` etc.
4. **Cross-version compatibility** - Test with `sbt +test`

## Repository Structure

```
llm4s/
├── modules/
│   ├── core/            # Core library (published)
│   ├── samples/         # Usage examples
│   ├── workspace/       # Containerized execution
│   └── crossTest/       # Cross-version tests
├── docs/                # Documentation
├── project/             # SBT config
└── build.sbt
```

**Key paths in `modules/core/src/main/scala/org/llm4s/`:**
- `types/` - Result type, newtypes
- `config/` - ConfigReader
- `llmconnect/` - LLM client and providers
- `agent/` - Agent framework, guardrails, memory, handoffs
- `toolapi/` - Tool calling, built-in tools
- `trace/` - Observability

## Common Commands

```bash
sbt buildAll           # Build all Scala versions
sbt +test              # Test all versions
sbt scalafmtAll        # Format code
sbt cov                # Run coverage
sbt "samples/runMain org.llm4s.samples.basic.BasicLLMCallingExample"
```

## Environment Variables

```bash
# Required
LLM_MODEL=openai/gpt-4o              # or anthropic/claude-sonnet-4-5-latest
OPENAI_API_KEY=sk-...                # or ANTHROPIC_API_KEY

# Optional
TRACING_MODE=langfuse                # langfuse, console, or none
LANGFUSE_PUBLIC_KEY=pk-lf-...
LANGFUSE_SECRET_KEY=sk-lf-...
```

## Code Conventions

### Error Handling

```scala
// GOOD - Return Result
def parseConfig(): Result[Config] = ConfigReader.LLMConfig()

// BAD - Don't throw
def parseConfig(): Config = throw new RuntimeException()

// Convert Try to Result
import org.llm4s.types.TryOps
Try("123".toInt).toResult
```

### Configuration

```scala
// GOOD
val provider: Result[ProviderConfig] = ConfigReader.Provider()

// BAD
val apiKey = sys.env.get("OPENAI_API_KEY")
```

### Naming

- Types: `PascalCase` (`LLMClient`, `CompletionResponse`)
- Values/functions: `camelCase` (`apiKey`, `createClient`)
- Constants: `camelCase` (`defaultTimeout`, not `DEFAULT_TIMEOUT`)

### Scalafix Rules

**Banned patterns** (enforced via `.scalafix.conf`):
- `ConfigFactory.load()`, `sys.env()`, `System.getenv()` - use ConfigReader
- `try/catch/finally` outside safety packages - use `Result`
- Infix operators - use `list.map(f)` not `list map f`

## Agent Framework

### Basic Agent Usage

```scala
for {
  client <- LLMConnect.fromEnv()
  agent = new Agent(client)
  tools = new ToolRegistry(Seq(myTool))
  state <- agent.run("Query here", tools)
} yield state
```

### Multi-Turn Conversations

```scala
for {
  state1 <- agent.run("First query", tools)
  state2 <- agent.continueConversation(state1, "Follow-up")
} yield state2
```

### Built-in Tools

```scala
import org.llm4s.toolapi.builtin.BuiltinTools

BuiltinTools.core          // DateTime, Calculator, UUID, JSON
BuiltinTools.safe()        // + web search, HTTP
BuiltinTools.withFiles()   // + read-only file access
BuiltinTools.development() // All tools (use with caution)
```

### Guardrails

```scala
import org.llm4s.agent.guardrails.builtin._

agent.run(
  query = "Generate JSON",
  tools = tools,
  inputGuardrails = Seq(new LengthCheck(1, 10000), new ProfanityFilter()),
  outputGuardrails = Seq(new JSONValidator())
)
```

Built-in guardrails:
- **Simple validators**: `LengthCheck`, `ProfanityFilter`, `JSONValidator`, `RegexValidator`, `ToneValidator`
- **LLM-as-Judge**: `LLMSafetyGuardrail`, `LLMFactualityGuardrail`, `LLMQualityGuardrail`, `LLMToneGuardrail`
- **Composition**: `CompositeGuardrail.all()`, `CompositeGuardrail.any()`, `CompositeGuardrail.sequence()`

### Handoffs

```scala
import org.llm4s.agent.Handoff

agent.run(
  query = "Complex physics question",
  tools = ToolRegistry.empty,
  handoffs = Seq(Handoff.to(specialistAgent, "Physics expertise required"))
)
```

Use handoffs for simple 2-3 agent delegation. Use DAGs for complex parallel workflows.

### Memory

```scala
import org.llm4s.agent.memory._

val manager = SimpleMemoryManager.empty
for {
  m1 <- manager.recordUserFact("Prefers Scala", Some("user-1"), Some(0.9))
  context <- m1.getRelevantContext("Tell me about Scala")
} yield context
```

### Reasoning Modes

```scala
val options = CompletionOptions()
  .withReasoning(ReasoningEffort.High)  // None, Low, Medium, High
  .copy(maxTokens = Some(4096))

client.complete(conversation, options)
```

### Streaming Events

```scala
import org.llm4s.agent.streaming._

// Get real-time agent execution events
agent.runWithEvents("Query here", tools) { event =>
  event match {
    case TextDelta(text) => print(text)
    case ToolCallStarted(name, _) => println(s"Calling $name...")
    case ToolCallCompleted(name, result, _) => println(s"$name returned: $result")
    case AgentCompleted(state) => println("Done!")
    case _ => ()
  }
}
```

Event types: `TextDelta`, `TextComplete`, `ToolCallStarted`, `ToolCallCompleted`, `ToolCallFailed`, `AgentStarted`, `StepStarted`, `StepCompleted`, `AgentCompleted`, `AgentFailed`, `InputGuardrailStarted`, `InputGuardrailCompleted`, `OutputGuardrailStarted`, `OutputGuardrailCompleted`, `HandoffStarted`, `HandoffCompleted`

## Testing

```scala
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MySpec extends AnyFlatSpec with Matchers {
  "Component" should "return success" in {
    MyComponent.process("valid") shouldBe Right(expected)
  }
}
```

**Best practices:** Deterministic, fast (use mocks), isolated, target 80%+ coverage.

## Adding New Code

### New Sample
1. Create in `modules/samples/src/main/scala/org/llm4s/samples/<category>/`
2. Implement with `extends App`
3. Run with `sbt "samples/runMain org.llm4s.samples.<category>.YourExample"`

### New Provider
1. Create config in `llmconnect/config/`
2. Implement client in `llmconnect/provider/`
3. Update `ProviderSelector`
4. Add tests

### New Tool
1. Define function returning `Result[T]`
2. Register with `ToolRegistry`
3. Add tests and sample

## Resources

- [README.md](README.md) - Getting started
- [docs/AGENTS.md](docs/AGENTS.md) - Agent framework
- [docs/design/](docs/design/) - Design documents
- Discord: https://discord.gg/4uvTPn6qww
- Issues: https://github.com/llm4s/llm4s/issues
