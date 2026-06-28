# Killer demo — TermFlow chat client

> Status: 2026-04-30 · Owner: open (handover to llm4s) · Target:
> termflow 1.0 README headline.

This document is a handover spec for the chat-client demo described in
[ROADMAP §3.3](ROADMAP.md). It targets an implementer working in the
`llm4s/llm4s` repository against a 1.0-RC of `org.llm4s::termflow`.

## 1. Context

TermFlow's 1.0 README needs a headline screenshot of a real, useful TUI.
A streaming `llm4s` chat client is the right shape: it surfaces every
Stage 1–3 capability — streaming, dialogs, theming, mouse-wheel
scrollback, async tool calls, the recoverable-error path — in one
application that people might actually run.

The demo lives in **`llm4s/llm4s`**, not in `llm4s/termflow`. Hosting it
in termflow would require a (sibling) llm4s dependency in this repo;
because llm4s already depends on termflow for its samples, that creates
a cycle that bites at release time even when the published-artifact
graph stays acyclic. The decision is recorded in ROADMAP §8.

The flow is:

1. llm4s ships the demo as a sample (e.g. `org.llm4s.samples.chat.tui`).
2. termflow's README links to the source and embeds a screenshot.
3. Termflow contributors review the PR for API-surface fit; llm4s
   maintainers own implementation and ongoing maintenance.

## 2. Goals

- **Read like a finished app, not a snippet.** ~200 LOC of glue is the
  budget; the supporting widgets/dialogs come from termflow.
- **Demonstrate the full TermFlow capability surface in one program.**
  See §10 for the ticked checklist.
- **Be a useful starter** that an llm4s user can clone, drop their
  `ANTHROPIC_API_KEY` (or equivalent) into, and start chatting in under
  a minute.
- **Stay deterministic enough to test** with a stub `LlmClient` and the
  termflow-testkit `TuiTestDriver`.

## 3. Non-goals

- **Multi-conversation management.** One conversation per process.
- **Persistent transcript.** Memory only; `Ctrl+L` clears.
- **Multimodal input** (images, files, audio).
- **Production auth flows** — env-var API keys are sufficient.
- **Prompt templating libraries.** A single configurable system prompt
  is enough.
- **Spec compliance with every llm4s feature.** Demo, not reference
  implementation.

## 4. UI shape

```
┌─ termflow ✦ chat — gpt-4o-mini · ● streaming ───────────────────────┐
│ ↑/↓ scroll · End tail · /help · Ctrl+T theme · Ctrl+C quit          │
├─────────────────────────────────────────────────────────────────────┤
│ system: You are a helpful assistant.                                │
│                                                                     │
│ you: list the .scala files under modules/termflow-app               │
│                                                                     │
│ assistant: I'll check the workspace.                                │
│   ⚙ tool: read_file(path="modules/termflow-app/build.sbt")          │
│   ✓ 1.2 KB                                                          │
│   …continuing the response…                                         │
│                                                                     │
│ ─ paused — press End to tail ─                                      │
├─────────────────────────────────────────────────────────────────────┤
│  ready · 47 turns · auto-tail                                       │
│ > _                                                                 │
└─────────────────────────────────────────────────────────────────────┘
```

Three zones — header (model name + streaming indicator + help), middle
(transcript, fills available space), footer (status + prompt).
Implemented as `Layout.Border` wrapped in `Layout.toBudgetedRootNode`
so it reflows on resize.

## 5. Keybindings

| Key | Action |
|---|---|
| `Enter` | Submit prompt (or accept the tool-call dialog) |
| `↑` / `↓` | Scroll transcript ± 1 line |
| `PageUp` / `PageDown` | Scroll transcript ± 1 page |
| Mouse wheel over transcript | Scroll ± 3 lines per detent (`LogView.scrollDelta`) |
| `End` | Jump to bottom and re-enable auto-tail |
| `Ctrl+L` | Clear conversation back to system prompt |
| `Ctrl+T` | Toggle dark / light theme |
| `Esc` | Cancel the current dialog; otherwise quit |
| `Ctrl+C` | Quit |

## 6. Slash commands

Detected on submit when the prompt starts with `/`. Unknown commands
surface through `Cmd.TermFlowErrorCmd(CommandError(...))` so the user
gets the red banner from §4.1.

| Command | Action |
|---|---|
| `/help` | Append a system entry listing every command and key. |
| `/quit`, `/exit` | Same as `Ctrl+C`. |
| `/clear` | Same as `Ctrl+L`. |
| `/model <name>` | Switch the active model. Validation: the value must be in `Config.allowedModels`. |
| `/theme [dark\|light]` | Set or toggle theme. |
| `/system <prompt>` | Replace the system prompt for the current conversation. |
| `/tools` | Append a system entry listing registered tools and their schemas. |

## 7. Architecture

### 7.1 Data model

```scala
final case class Model(
  width: Int,
  height: Int,
  config: ChatConfig,
  conversation: Vector[Entry],
  scrollOffset: Int,
  autoTail: Boolean,
  prompt: Prompt.State,
  pending: PendingState,
  theme: Theme,
  status: String,
  // subs
  input: Sub[Msg],
  resize: Sub[Msg],
  pump: Sub[Msg]              // token-pump ticker, see §7.4
)

enum Role:
  case System, User, Assistant, Tool

final case class Entry(role: Role, content: String, toolCall: Option[ToolCallSummary] = None)

final case class ToolCallSummary(name: String, args: String, result: Option[ToolOutcome])

enum ToolOutcome:
  case Ok(summary: String)
  case Err(message: String)
  case Denied

enum PendingState:
  case Idle
  case Streaming(intoIdx: Int)                                       // tokens flowing into this entry
  case AwaitingToolApproval(call: ToolCall, intoIdx: Int)            // dialog open
  case ExecutingTool(call: ToolCall, intoIdx: Int)                   // tool running, awaiting result
```

`ChatConfig` carries provider, model, system prompt, and feature toggles
(see §9). `ToolCall` is the implementer's own representation; one
small case class is sufficient.

### 7.2 Messages

```scala
enum Msg:
  case Resize(w: Int, h: Int)
  case Key(k: KeyDecoder.InputKey)

  // Prompt → conversation flow
  case Submit(text: String)
  case SlashCommand(cmd: String, args: String)

  // Streaming pipeline
  case Token(text: String)
  case StreamComplete
  case StreamError(err: TermFlowError)

  // Tool flow
  case ToolCallReceived(call: ToolCall, intoIdx: Int)
  case ToolApprove
  case ToolDeny
  case ToolResult(outcome: ToolOutcome)

  // UI
  case ScrollBy(delta: Int)
  case ScrollToEnd
  case ClearConversation
  case ToggleTheme
  case Quit
```

### 7.3 Layout

```scala
def view(m: Model): RootNode =
  given Theme = m.theme
  val transcript = widgets.LogView(
    lines = transcriptLines(m),
    width = m.width - 2,
    height = math.max(3, m.height - 5),
    scrollOffset = m.scrollOffset,
    at = transcriptOrigin
  )
  Layout.border(
    top    = Layout.column(gap = 0)(headerNode(m), helpNode(m)),
    center = Layout.Fill(transcriptLayer(transcript)),
    bottom = Layout.column(gap = 0)(statusNode(m), promptNode(m))
  ).toBudgetedRootNode(m.width, m.height, input = focusedPromptInput(m))
```

The `transcriptLayer` is whatever wraps `LogView`'s vnodes into a
`Layout`-shaped value (e.g. via `Layout.Elem`s composed in a
`Layout.Column`). The implementer can pre-position vnodes if simpler.

### 7.4 Streaming pipeline

llm4s exposes streaming chat completion as a callback / channel /
stream — implementer's choice. The recommended TermFlow integration:

1. **`Submit(text)` →** append a `User` entry, append an empty
   `Assistant` entry, transition to `Streaming(intoIdx)`, kick off the
   client call, attach a token-pump `Sub.Every(33ms)` (≈30 Hz).
2. **Background producer** (whatever shape llm4s uses) pushes tokens
   into a thread-safe queue (`java.util.concurrent.ConcurrentLinkedQueue[String]`)
   owned by the model.
3. **`pump` tick** drains up to N tokens per frame, emits one
   `Token(text)` `Msg` per drained token via `ctx.publish(Cmd.GCmd(...))`.
   This decouples the producer rate from the render rate and prevents
   the UI from stalling on a flood of tokens.
4. **`Token(text)`** appends the text to `conversation(intoIdx).content`
   and re-clamps `scrollOffset` (auto-tail respected).
5. **`StreamComplete`** transitions to `Idle`, cancels the pump,
   optionally fires `Cmd.RequestAttention` if the user has scrolled
   away from the tail (i.e. `!autoTail`).
6. **`StreamError(err)`** transitions to `Idle`, surfaces via
   `Cmd.TermFlowErrorCmd(err)` so the §4.1 banner shows.

The token pump is the only background dependency — everything else
runs through the standard `update`/`view` loop.

### 7.5 Tool-call flow

The implementer chooses how llm4s signals a tool call (most clients
emit a structured "tool_call" delta in the stream). When detected:

1. The streaming pipeline emits **`ToolCallReceived(call, intoIdx)`**
   instead of a `Token`. The current assistant entry is augmented with
   `toolCall = Some(ToolCallSummary(...))`.
2. **`update`** transitions to `AwaitingToolApproval(call, intoIdx)`,
   pauses the token pump, opens `Dialogs.confirm(...)` with text like
   `Allow read of "build.sbt" (1.2 KB)?`.
3. **User accepts (`Enter`/`Y`)** → `ToolApprove` →
   `ExecutingTool(call, intoIdx)`, fire `Cmd.asyncResult(runTool(call))`
   that resolves to `ToolResult(outcome)`.
4. **User denies (`Esc`/`N`)** → `ToolDeny` → append a tool-result
   entry with `outcome = Denied`, send a "tool denied" message back to
   llm4s so the model can adjust, resume streaming.
5. **`ToolResult(outcome)`** → append a `Tool` role entry summarising
   the outcome, feed the result back to llm4s, transition back to
   `Streaming(intoIdx)`, re-arm the pump.

**v1 tool surface — one tool, `read_file(path: String)`.** Returns the
first 64 KB of the file's content (truncated with a marker), errors on
missing or out-of-workspace paths. The confirmation modal shows the
path and a size warning if the file is >16 KB.

Justification for one tool: keeps the demo within budget while
demonstrating the full async-tool-call cycle. The implementer can add
more tools later (`list_files`, `grep`, …) without changing the demo's
shape.

## 8. termflow API surface used

| Capability | API | File |
|---|---|---|
| Runtime | `TuiRuntime.run(app)` | `termflow-app/.../TuiRuntime.scala` |
| App contract | `TuiApp[Model, Msg]` | `termflow-app/.../Tui.scala` |
| Async work | `Cmd.asyncResult` | `termflow-app/.../Tui.scala` |
| Errors | `Cmd.TermFlowErrorCmd`, banner | `termflow-app/.../Tui.scala` + `SimpleANSIRenderer.scala` |
| Notifications | `Cmd.RequestAttention` | `termflow-app/.../Tui.scala` |
| Dialogs | `Dialogs.confirm` | `termflow-app/.../Dialogs.scala` |
| Prompt | `Prompt.State`, `Prompt.handleKey`, `Prompt.renderWithPrefix` | `termflow-app/.../Prompt.scala` |
| Layout | `Layout.border`, `Layout.toBudgetedRootNode`, `Fill` | `termflow-screen/.../Layout.scala` |
| Scrollback | `widgets.LogView`, `LogView.scrollDelta`, `LogView.Viewport` | `termflow-widgets/.../LogView.scala` |
| Theming | `Theme.dark`, `Theme.light`, `Theme.themed` | `termflow-app/.../Theme.scala` |
| Subs | `Sub.InputKey`, `Sub.TerminalResize`, `Sub.Every` | `termflow-app/.../Sub.scala` |
| Capabilities | `Capabilities.notifications` for graceful fallback | `termflow-terminal/.../Capabilities.scala` |
| Prelude | `termflow.tui.TuiPrelude.*`, `ScreenPrelude.*` | `termflow-app/.../TuiPrelude.scala` |

The rule of thumb: anything in this table is supported public API. If
the implementer reaches for an internal symbol (anything in a
`private[tui]` package), flag it in the PR for either (a) promotion to
public API or (b) replacement.

## 9. llm4s API surface (placeholder)

The implementer fills this in. The demo needs:

- **Chat client construction** — provider + model + API key. Env-var
  defaults: `TERMFLOW_CHAT_PROVIDER`, `TERMFLOW_CHAT_MODEL`,
  `<PROVIDER>_API_KEY`.
- **Streaming chat completion** — given a system prompt + message
  history, emit a stream of (text-delta | tool-call) events plus a
  terminal "done" / "error" signal.
- **Tool definition** — register `read_file(path: String): String`
  with a JSON-schema-ish description.
- **Tool result feedback** — a way to send the tool's output back so
  the model can continue the response.
- **Error mapping** — surface llm4s errors as `TermFlowError.Unexpected`
  or a more specific variant (e.g. `Validation` for an invalid model
  name, `ConfigError` for missing creds).

## 10. Acceptance criteria

Mirrors ROADMAP §3.3 and the §3.6 DoD:

- ☐ Demo source merged to `llm4s/llm4s`, runnable via `sbt
  samples/chatTuiDemo` (or equivalent alias).
- ☐ ≤ ~250 lines of Scala in the main app file (allowing some
  headroom over the 200-LOC target for the tool definition).
- ☐ Submit a message → assistant reply streams in, scrollback works,
  auto-tail toggles on ↑ and back on `End`.
- ☐ Mouse wheel over the transcript scrolls; mouse wheel elsewhere is a
  no-op.
- ☐ Tool call triggers a `Dialogs.confirm`; deny appends a denied entry,
  accept executes the tool and feeds the result back.
- ☐ Theme toggle visibly changes the colour palette.
- ☐ Long reply finishing while user is scrolled away fires
  `Cmd.RequestAttention` (verifiable in iTerm2/kitty/VTE; falls back to
  BEL elsewhere — no test for this, just confirm by demo).
- ☐ Submitting `/bogus` shows the red error banner for one frame.
- ☐ README screenshot in `llm4s/termflow` updated; link to the demo
  source added below it.
- ☐ termflow contributor signoff on the API-surface usage.

## 11. File layout (recommended)

```
samples/
├── chat-tui/
│   ├── src/main/scala/org/llm4s/samples/chat/tui/
│   │   ├── ChatTuiApp.scala       # TuiApp[Model, Msg], view, update
│   │   ├── ChatModel.scala        # Model, Msg, Entry, PendingState, ...
│   │   ├── ChatTool.scala         # read_file definition + execution
│   │   └── ChatConfig.scala       # env-var loading, defaults
│   └── src/test/scala/org/llm4s/samples/chat/tui/
│       └── ChatTuiAppSpec.scala   # TuiTestDriver-backed tests
└── build.sbt                       # adds `chatTuiDemo` alias
```

`ChatTuiApp.scala` is the bulk of the LOC budget; the others are small
data carriers that won't grow.

## 12. Testing strategy

Use `termflow-testkit`'s `TuiTestDriver` with a stub `LlmClient` that
emits a deterministic, scripted token stream. Three test classes,
roughly:

1. **Streaming** — submit a message, run N pump ticks, assert the
   assistant entry's content matches the script and that
   `observedNotifications` is empty (user is auto-tailing); scroll up
   mid-stream and assert auto-tail flips off; let the stream finish and
   assert `attentionCount == 1`.
2. **Tool call** — script a tool-call delta in the stream, assert the
   model transitions to `AwaitingToolApproval` and a `Dialogs.confirm`
   overlay appears; accept it, assert the stub tool runs and the
   resulting entry's content matches; deny it on a second run, assert
   the denied entry shape.
3. **Errors and slash commands** — submit `/bogus`, assert
   `observedErrors` contains a `CommandError`; submit `/model
   not-on-list`, assert a `Validation` error; submit `/theme dark` and
   assert `model.theme == Theme.dark`.

Goldens: one optional `RenderFrame` snapshot per major state (idle,
streaming, awaiting tool, error banner) is plenty.

## 13. Open questions

The implementer should resolve these in the PR; flagged here so they
don't get lost.

1. **Cancellation during streaming.** Should `Esc` mid-stream abort the
   request and roll back the in-flight assistant entry, or just leave
   it partial? Recommended: abort + leave the partial reply with an
   `…(aborted)` suffix.
2. **System-prompt persistence.** Does `/system` overwrite immediately,
   or only take effect on the next conversation? Recommended:
   immediate, document the trade-off.
3. **Provider abstraction.** If llm4s's client interface is generic
   over provider, the demo can swap providers via `/provider <name>`.
   If not, ship single-provider, document the swap path in a comment.
4. **Tool denial feedback.** When a tool is denied, do we send the
   model a structured "user denied" tool result, or terminate the turn?
   Recommended: send a structured denial so the model can adapt
   ("Understood, here's a different approach…").
5. **Scrollback bound.** Cap retained entries at e.g. 1,000 to bound
   memory? Or trust the LLM context window to be the bound? Recommended:
   no app-level cap for v1; document as a known limitation.

## 14. Coordination

1. Termflow tags `1.0.0-RC1` (after ROADMAP §4 hardening + §3.5
   migration guide are complete).
2. llm4s opens an issue referencing this spec; implementer claims it.
3. Implementation PR cross-links to this spec; one termflow contributor
   reviews API surface, one llm4s contributor reviews implementation.
4. After merge, this spec moves to "implemented" status — keep it
   around as the design record.
5. Termflow's README PR adds the screenshot and link as part of
   ROADMAP §3.6 DoD signoff.

## 15. Status changelog

- *2026-04-30* — Spec authored, roadmap §3.3 updated to point here.
