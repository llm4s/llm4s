# Implementation plan — Chat TUI demo

> **Owns:** llm4s · **Reviewers:** llm4s contributor (impl), termflow
> contributor (API surface) · **Spec:** [chat-tui-demo-spec.md](chat-tui-demo-spec.md)

This plan turns the termflow handover spec
([chat-tui-demo-spec.md](chat-tui-demo-spec.md)) into a concrete
implementation against llm4s. It assumes termflow `0.4.0` (published) and
the upcoming `1.0.0-RC1` once tagged. The demo is published only as a
sample; nothing under `modules/core/` changes.

## 1. Scope and decisions resolved up front

The spec leaves a handful of open questions. The defaults below are the
ones we'll ship in v1; the spec stays the design record, this doc is the
record of choices we made.

| Question (spec §13) | Decision for v1 | Reason |
|---|---|---|
| Cancellation on `Esc` mid-stream | Abort the request, append `…(aborted)` to the partial assistant entry. | Matches the keystroke users actually want; one extra `Cmd.Exit`-like control path. |
| `/system` semantics | Replace immediately, retain conversation history. Document in `/help`. | The conversation already carries the system message at index 0; replacement is a single `Vector.updated`. |
| Provider abstraction | Use `Llm4sConfig.defaultProvider()` so the demo runs against whatever the user has configured (Anthropic / OpenAI / Ollama / …). `/model` switches the model name only — provider stays as configured. | llm4s already has the unified provider config path. Demo doesn't re-implement provider routing. |
| Tool-denial feedback | Emit a structured `ToolMessage(content = """{"denied": true, "reason": "user denied"}""", toolCallId = id)` so the model can adapt. | Llm4s already validates tool-call/tool-message pairing — sending a denial keeps the conversation valid. |
| Scrollback bound | No app-level cap. Note this as a known limitation in the README banner. | Matches spec recommendation; bounded by context window in practice. |

## 2. File layout

Demo lives in the existing `samples` project (no new sbt module needed):

```
modules/samples/
├── src/main/scala/org/llm4s/samples/chat/tui/
│   ├── ChatTuiMain.scala         # @main entry point — load config, run TuiRuntime
│   ├── ChatTuiApp.scala          # TuiApp[Model, Msg]: init, update, view, toMsg
│   ├── ChatTuiModel.scala        # Model, Msg, Entry, PendingState, Role
│   ├── ChatTuiView.scala         # Layout.Border + LogView render
│   ├── ChatTuiUpdate.scala       # The big match block; dispatches per Msg
│   ├── ChatTuiStreaming.scala    # Token pump + bridge from streamComplete callback
│   ├── ChatTuiTool.scala         # read_file ToolFunction + execution helper
│   └── ChatTuiConfig.scala       # ChatConfig, env-var loading, model allow-list
└── src/test/scala/org/llm4s/samples/chat/tui/
    ├── ChatTuiAppSpec.scala       # End-to-end TuiTestDriver runs
    ├── ChatTuiStreamingSpec.scala # Token pump + scroll/auto-tail invariants
    └── stubs/
        └── ScriptedLlmClient.scala  # Test stub returning a deterministic stream
```

Total budget: ~250 LOC across `ChatTuiApp.scala`, `ChatTuiUpdate.scala`,
`ChatTuiView.scala`. The streaming bridge and tool definition are
separately budgeted (~80 + ~60 LOC).

## 3. Build wiring

Two changes to `build.sbt` and one to `project/Dependencies.scala`:

```scala
// project/Dependencies.scala
val termflowTestkit = "org.llm4s" %% "termflow-testkit" % Versions.termflow
```

```scala
// build.sbt — samples project
lazy val samples = (project in file("modules/samples"))
  .dependsOn(core)
  .settings(
    name := "samples",
    commonSettings,
    publish / skip  := true,
    coverageEnabled := false,
    libraryDependencies ++= Seq(
      Deps.termflow,
      Deps.termflowTestkit % Test
    )
  )

// build.sbt — convenience alias
addCommandAlias("chatTuiDemo", "samples/runMain org.llm4s.samples.chat.tui.ChatTuiMain")
```

No multi-module split. The demo is small and `samples` already
re-exports everything it needs via `core`.

## 4. llm4s API mapping

| Spec capability | llm4s API |
|---|---|
| Provider construction | `Llm4sConfig.defaultProvider(): Result[ProviderConfig]` then `LLMConnect.getClient(cfg): Result[LLMClient]` |
| Conversation history | `Conversation(messages: Seq[Message])` with `SystemMessage` / `UserMessage` / `AssistantMessage` / `ToolMessage` |
| Streaming | `LLMClient.streamComplete(conversation, options, onChunk: StreamedChunk => Unit): Result[Completion]` |
| Tool definition | `ToolBuilder[Args, Result](name, description, schema).withHandler(...).buildSafe(): Result[ToolFunction[_, _]]` |
| Tool registry | `new ToolRegistry(Seq(tool))` — passed via `CompletionOptions.copy(tools = registry.tools)` |
| Tool execution | `registry.execute(ToolCallRequest(name, arguments)): Either[ToolCallError, ujson.Value]` |
| Tool-call deltas in stream | `StreamedChunk.toolCall: Option[ToolCall]` — `id`, `name`, `arguments: ujson.Value` |
| Error path | llm4s `LLMError`/`ValidationError` — map to `TermFlowError.Unexpected(err.formatted, None)` at the boundary |

The demo will define a small adapter `Llm4sErrors.toTermFlowError(err: LLMError): TermFlowError` so every error surface maps consistently.

## 5. Streaming pipeline (concrete shape)

The spec calls out a 30 Hz pump draining a thread-safe queue. Concrete
plumbing:

```scala
// ChatTuiStreaming.scala
final class TokenPump:
  private val queue = new java.util.concurrent.ConcurrentLinkedQueue[StreamedChunk]()
  def offer(chunk: StreamedChunk): Unit = queue.offer(chunk): Unit
  /** Drain up to maxPerFrame chunks — called from the Sub.Every tick. */
  def drain(maxPerFrame: Int = 64): Vector[StreamedChunk] =
    val buf = Vector.newBuilder[StreamedChunk]
    var i   = 0
    while i < maxPerFrame do
      val c = queue.poll()
      if c eq null then i = maxPerFrame else { buf += c; i += 1 }
    buf.result()

object ChatTuiStreaming:
  /**
   * Kick off streaming on a background thread. The runtime stays on the
   * calling thread; tokens arrive via the pump. Returns the
   * `Future[Result[Completion]]` so the call site can wire it through
   * `Cmd.asyncResult` for the StreamComplete / StreamError edges.
   */
  def start(
    client: LLMClient,
    conversation: Conversation,
    options: CompletionOptions,
    pump: TokenPump
  )(using ec: ExecutionContext): AsyncResult[Completion] =
    val task = Future {
      client.streamComplete(conversation, options, onChunk = pump.offer)
    }
    AsyncResult.fromFuture(task.flatMap {
      case Right(c) => Future.successful(Right(c))
      case Left(e)  => Future.successful(Left(Llm4sErrors.toTermFlowError(e)))
    })
```

The update layer's `Submit(text)` arm:

1. Append `UserMessage` to the conversation, append empty `AssistantMessage("")` for the streaming target.
2. Construct a fresh `TokenPump`, store it on the model.
3. Register `Sub.Every(33L, () => Msg.PumpTick, ctx)`, store the sub on the model so we can `cancel()` later.
4. Return `Tui(nextModel, Cmd.asyncResult(task = ChatTuiStreaming.start(...), onSuccess = Msg.StreamComplete.apply, onError = Msg.StreamError.apply))`.

`PumpTick`:

1. Drain the pump.
2. For each chunk: if `content.isDefined` → append to live assistant entry; if `toolCall.isDefined` → emit `ToolCallReceived` for the update arm.
3. Re-clamp `scrollOffset` against `LogView.maxScroll`, respect `autoTail`.

`StreamComplete`:

1. Cancel the pump sub, drop the `TokenPump` reference.
2. If `!autoTail` and the reply was non-trivial: emit `Cmd.RequestAttention`.

`StreamError(err)`:

1. Cancel the pump sub.
2. Emit `Cmd.TermFlowErrorCmd(err)` so the §4.1 banner renders for one frame.

## 6. Tool-call flow (concrete shape)

`read_file(path: String): String` is defined in `ChatTuiTool.scala`:

```scala
object ChatTuiTool:

  final case class ReadFileResult(content: String, truncated: Boolean, sizeBytes: Long)
  given ReadWriter[ReadFileResult] = macroRW

  private val MaxBytes  = 64 * 1024
  private val WarnBytes = 16 * 1024

  /** Resolve `path` against the project root and forbid traversal. */
  def safeResolve(workspaceRoot: Path, path: String): Either[String, Path] = …

  val schema: ObjectSchema[Map[String, Any]] = Schema
    .`object`[Map[String, Any]]("read_file parameters")
    .withProperty(Schema.property("path", Schema.string("Workspace-relative file path")))

  def handler(workspaceRoot: Path)(params: SafeParameterExtractor): Either[String, ReadFileResult] = …

  def toolSafe(workspaceRoot: Path): Result[ToolFunction[Map[String, Any], ReadFileResult]] =
    ToolBuilder[Map[String, Any], ReadFileResult](
      "read_file",
      "Read up to 64 KB from a workspace-relative file path.",
      schema
    ).withHandler(handler(workspaceRoot)).buildSafe()
```

The update layer's tool arms:

- **`ToolCallReceived(call, intoIdx)`** — set `pending = AwaitingToolApproval(call, intoIdx)`, build a `Dialogs.confirm` overlay describing path + size, expose it via `RootNode.overlays`. The pump stays alive but produces no further output until the LLM emits more chunks (none arrive while a tool is pending).
- **`ToolApprove`** — set `pending = ExecutingTool(call, intoIdx)`, emit `Cmd.asyncResult(task = runTool(call), ...)` where `runTool` boxes `registry.execute` into a `Future[Result[String]]`.
- **`ToolDeny`** — append `ToolMessage(deniedJson, call.id)`; resume streaming by re-issuing `streamComplete` against the augmented conversation.
- **`ToolResult(outcome)`** — append `ToolMessage(outcomeJson, call.id)`; same resumption path as denial.

For v1 we re-issue `streamComplete` after each tool round-trip rather than trying to splice into the original stream — simpler and matches how llm4s providers actually work.

## 7. View — termflow 0.4 shape

Three zones, all wrapped in `Layout.Border` so they reflow on resize:

```scala
def view(m: Model): RootNode =
  given Theme = m.theme
  val transcript = widgets.LogView(
    lines        = transcriptLines(m),
    width        = math.max(20, m.width - 2),
    height       = math.max(3, m.height - 5),
    scrollOffset = m.scrollOffset,
    at           = transcriptOrigin(m),
    wrap         = true
  )
  val statusBar = widgets.StatusBar(
    left   = s" ${m.status} ",
    center = s"${m.config.modelName} • ${m.conversation.size} turns",
    right  = if m.autoTail then " auto-tail " else " paused ",
    width  = m.width
  )
  val overlays = m.pending match
    case PendingState.AwaitingToolApproval(call, _) =>
      List(Dialogs.confirm(s"""Allow read of "${call.path}" (${call.sizeHint})?"""))
    case _ => Nil

  Layout.border(
    top    = Layout.column(gap = 0)(headerNode(m), helpNode(m)),
    center = Layout.Fill(transcriptLayout(transcript)),
    bottom = Layout.column(gap = 0)(statusBar.asLayout, promptNode(m).asLayout)
  ).toBudgetedRootNode(m.width, m.height, input = focusedPromptInput(m))
   .copy(overlays = overlays)
```

Mouse-wheel scrollback re-uses `widgets.LogView.Viewport` exactly as
`Llm4sDashboardUpdate` already does — the same shape, applied to the
single transcript pane.

## 8. Slash commands

Detected by `ChatTuiApp.toMsg(input)` when the prompt starts with `/`.
Returns the appropriate `Msg`; unknown commands return
`Left(TermFlowError.CommandError(input.value))` so the runtime banner
fires automatically.

```scala
def toMsg(input: PromptLine): Result[Msg] =
  val raw = input.value.trim
  raw match
    case ""                              => Right(Msg.NoOp)
    case "/quit" | "/exit"               => Right(Msg.Quit)
    case "/clear"                        => Right(Msg.ClearConversation)
    case "/help"                         => Right(Msg.AppendHelpEntry)
    case "/tools"                        => Right(Msg.AppendToolsEntry)
    case s if s.startsWith("/model ")    =>
      val name = s.stripPrefix("/model ").trim
      if ChatConfig.allowedModels.contains(name) then Right(Msg.SetModel(name))
      else Left(TermFlowError.Validation(s"Unknown model: $name"))
    case s if s.startsWith("/theme")     =>
      val arg = s.stripPrefix("/theme").trim
      arg match
        case ""        => Right(Msg.ToggleTheme)
        case "dark"    => Right(Msg.SetTheme(Theme.dark))
        case "light"   => Right(Msg.SetTheme(Theme.light))
        case other     => Left(TermFlowError.Validation(s"Unknown theme: $other"))
    case s if s.startsWith("/system ")   => Right(Msg.SetSystem(s.stripPrefix("/system ").trim))
    case s if s.startsWith("/")          => Left(TermFlowError.CommandError(s))
    case other                           => Right(Msg.Submit(other))
```

`ChatConfig.allowedModels` is loaded from a `META-INF/llm4s/chat-tui-models.txt`
resource shipped with the sample. v1 ships a small, hand-curated list
(GPT-4o-mini, Claude 3.5 Haiku, Ollama defaults). Users override by
editing the file in their fork.

## 9. Configuration loading (`ChatTuiConfig`)

```scala
final case class ChatConfig(
  providerConfig: ProviderConfig,
  modelName: String,
  systemPrompt: String,
  workspaceRoot: java.nio.file.Path,
  allowedModels: Vector[String]
)

object ChatTuiConfig:
  def load(): Result[ChatConfig] =
    for
      providerConfig <- Llm4sConfig.defaultProvider()
      systemPrompt    = sys.env.getOrElse("CHAT_TUI_SYSTEM_PROMPT", DefaultSystem)
      workspaceRoot   = sys.props.get("user.dir").map(Path.of(_)).getOrElse(Path.of("."))
      allowedModels   = loadAllowedModels()
    yield ChatConfig(providerConfig, providerConfig.model, systemPrompt, workspaceRoot, allowedModels)
```

`Llm4sConfig.defaultProvider()` already pulls `LLM_MODEL` and
`<PROVIDER>_API_KEY` from the environment per CLAUDE.md, so the demo
inherits all the standard llm4s env conventions.

## 10. Testing strategy

Three test classes, all using `termflow-testkit`'s `TuiTestDriver`:

### 10.1 `ChatTuiAppSpec` — end-to-end happy paths

- Submit a prompt, drive `Sub.Every` for N pump ticks, assert the
  assistant entry's content matches the scripted stream.
- Submit a prompt while scrolled away, finish the stream, assert
  `driver.attentionCount == 1`.
- Submit `/bogus`, assert `driver.observedErrors` contains a
  `TermFlowError.CommandError`.
- Submit `/theme light`, assert `model.theme == Theme.light`.
- Submit `/clear`, assert the conversation drops back to the system
  message and `model.scrollOffset == 0`.

### 10.2 `ChatTuiStreamingSpec` — pump invariants

- Pump produces tokens at >30 Hz; the drained-per-frame cap prevents
  starving the render loop.
- Auto-tail flips off when `ScrollBy(-1)` arrives mid-stream and back on
  when the scroll offset hits `maxScroll` again.
- Cancelling the pump sub mid-stream stops further `Token` messages.

### 10.3 `ChatTuiToolSpec` — tool flow

- Script a tool-call delta, drive the pump, assert
  `model.pending == AwaitingToolApproval`.
- Send `Msg.ToolApprove`, assert the stub tool runs, the resulting
  `ToolMessage` appears in the conversation, and the next
  `streamComplete` is invoked with the augmented history.
- Send `Msg.ToolDeny` instead, assert the denial `ToolMessage` is
  appended.

The stub `ScriptedLlmClient extends LLMClient` returns chunks from a
`Vector[StreamedChunk]` script. Streaming is synchronous in tests
(`onChunk` called directly inside `streamComplete`); no real `Future`
scheduling, so the tests stay deterministic.

Goldens: one `RenderFrame` snapshot per major state (idle, streaming,
awaiting tool, error banner) — total ~4 snapshots, low maintenance cost.

## 11. Phasing — landable PR breakdown

The whole demo is too large for a single PR. Suggested split:

1. **PR 1 — scaffolding.** `ChatTuiConfig`, `ChatTuiModel`,
   `ChatTuiMain`, an empty `ChatTuiApp` that renders a static "wired
   up" frame. Adds `chatTuiDemo` alias and the `termflow-testkit` test
   dep. ~300 LOC, all skeleton.
2. **PR 2 — non-streaming chat.** Use `client.complete(...)`
   synchronously to land the prompt → conversation → assistant-reply
   loop with no streaming yet. `Layout.Border`, `widgets.LogView`,
   theming, slash commands, `Cmd.TermFlowErrorCmd`. ~400 LOC including
   tests.
3. **PR 3 — streaming + auto-tail.** `ChatTuiStreaming`, `TokenPump`,
   `Sub.Every` pump, `LogView.scrollDelta` mouse-wheel,
   `Cmd.RequestAttention` on completion-while-scrolled-away. ~250 LOC.
4. **PR 4 — `read_file` tool.** `ChatTuiTool`, `Dialogs.confirm`
   approval flow, `Cmd.asyncResult` execution, `ToolMessage` feedback.
   ~250 LOC.
5. **PR 5 — README + screenshot.** Add a `chat-tui` README in
   `docs/examples/`, link from top-level README, capture screenshot for
   the termflow README. Cross-link from termflow side.

Each PR is independently releasable: PR 2 alone is already a usable
demo, just not streaming.

## 12. Acceptance criteria

Mirrors the spec's §10 with the v1 decisions inlined:

- ☐ `sbt chatTuiDemo` runs the demo against `Llm4sConfig.defaultProvider()`.
- ☐ `ChatTuiApp.scala` ≤ 300 LOC (raised from the spec's 250 to give
  room for the slash-command parser).
- ☐ Streaming works end-to-end against at least one real provider
  (Anthropic and OpenAI tested in the implementer's PR).
- ☐ Auto-tail toggles on scroll-up, restores on `End`. Mouse-wheel
  scrollback works in iTerm2 and kitty.
- ☐ `read_file` tool round-trip: `Dialogs.confirm` → approve →
  `Cmd.asyncResult` → `ToolMessage` feedback → continued reply.
- ☐ Tool denial appends a structured denied `ToolMessage` and the model
  adapts.
- ☐ `Ctrl+T` toggles `Theme.dark` ↔ `Theme.light`, all widgets repaint
  with the new palette in one frame.
- ☐ `Cmd.RequestAttention` fires when a stream completes while
  `!autoTail`. Verifiable via `TuiTestDriver.attentionCount`.
- ☐ `/bogus` → red error banner for one frame.
- ☐ Conversation persists across multiple `/system` updates within the
  process; `/clear` resets back to system-only.
- ☐ All three test specs pass under `sbt samples/test`.
- ☐ Termflow's README is updated with the screenshot + link in a
  follow-up PR on the termflow side.

## 13. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Streaming deadlocks if the pump's queue is unbounded and the LLM emits faster than 30 Hz × 64. | Cap the queue at 8192; drop with a warning log if exceeded — protects the UI even if a runaway stream arrives. |
| Mid-stream tool calls confuse the conversation history (provider sends tool delta, then keeps streaming). | After `ToolCallReceived`, stop appending text to the live assistant entry and treat subsequent tokens as belonging to the *next* assistant turn after the tool round-trip. The provider re-issues a new turn header on resumption. |
| `read_file` traversal escape (`../../../etc/passwd`). | `safeResolve` requires the resolved real path to start with the workspace root; otherwise return `"path outside workspace"`. |
| Different providers handle tool-call streaming differently (Anthropic uses a separate `tool_use` block; OpenAI sends function-call deltas). | The demo only consumes `StreamedChunk.toolCall: Option[ToolCall]` — llm4s already normalises this. If a provider doesn't expose tool calls in the stream, fall back to `complete()` for that provider only. |
| Test flakiness from the 33ms pump interval. | The streaming-spec tests drive `Msg.PumpTick` synchronously rather than waiting for `Sub.Every`. The `Sub.Every` integration is exercised by exactly one happy-path golden. |

## 14. Out of scope for v1

- Multi-turn tool chains (a tool call producing another tool call). v1
  handles one round-trip per assistant turn; nested calls fall through
  with a warning entry.
- Token cost / latency display. Add in a follow-up PR if useful.
- Retry on transient provider errors. The spec recommends
  `Cmd.TermFlowErrorCmd` + manual re-submit; we'll keep that.
- Saving the transcript. `Ctrl+L` clears; closing the process discards.
- Provider switching at runtime. `/model` only renames; `/provider`
  isn't in v1.

## 15. Coordination checklist

1. ☐ Termflow tags `1.0.0-RC1` (per termflow ROADMAP §4 hardening).
2. ☐ Open llm4s issue referencing this plan + the spec.
3. ☐ Bump `Versions.termflow` to the RC tag in `project/Dependencies.scala`.
4. ☐ Land PR 1–4 in order against an llm4s feature branch.
5. ☐ Termflow contributor reviews PR 4's API-surface usage; flags any
   `private[tui]` reach-throughs for promotion or replacement.
6. ☐ Land PR 5 (README + screenshot) on llm4s and the matching README
   PR on termflow.
7. ☐ Mark this plan and the spec as "implemented"; keep both as design
   records.

## 16. Status changelog

- *2026-04-30* — Plan authored. Spec moved from termflow to
  `docs/design/chat-tui-demo-spec.md` in this repo. v1 decisions
  recorded in §1.
