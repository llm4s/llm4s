# Expected Scala 2.13 probe outcomes

What each probe in `probes/` does when a Scala 2.13 program compiles it against **this checkout's** llm4s artifacts,
on every Scala 2.13 version the check runs (2.13.16 and the latest 2.13). The `Scala 2.13 probes` workflow
publishes the build locally, runs `./run-probes.sh --local VERSION --check expected.md`, and fails when any probe's
outcome differs from its row, in either direction: a probe that starts to compile is a fix, and the row, the
[Scala 2.13 page](../../getting-started/scala-2-13.md) and the [design record](../scala-2.13-interop.md) change with
it ([#2301](https://github.com/llm4s/llm4s/issues/2301)).

The outcomes are `compiles and runs`, `does not compile`, `runs, wrong result` and `compiles, crashes`. For a probe
that does not compile, *Error contains* must be part of its first compiler error, so a probe that stops compiling
for another reason (an API it uses was renamed) is not mistaken for the blocker it records.

| Probe | Expected | Error contains | Entry point |
|---|---|---|---|
| A_conversation | compiles and runs |  | `Conversation`, messages, `Result` |
| B_config_client | compiles and runs |  | `Llm4sConfig` + `LLMConnect.getClient` + `complete` |
| C_streaming | does not compile | `Unsupported Scala 3 union in bounds of type ToolHandlerError` | `streamComplete` with a `CompletionOptions` and a 2.13 callback |
| D_options_enums | does not compile | `Unsupported Scala 3 union in bounds of type ToolHandlerError` | `CompletionOptions` builders, `ReasoningEffort` |
| E_tools | does not compile | `Unsupported Scala 3 union in bounds of type ToolHandlerError` | `ToolBuilder` (the handler's `ToolHandlerError`, before the uPickle `ReadWriter` in the signature) |
| E2_tool_registry | compiles and runs |  | `ToolRegistry.execute` |
| F_structured | does not compile | `upickle.core.Types.Reader` | `completeStructured[A]` (uPickle `Reader[A]`) |
| G_agent | compiles and runs |  | `Agent.builder(...).build()`, `run`, `continueConversation` |
| H_subclass_client | does not compile | `Unsupported Scala 3 union in bounds of type ToolHandlerError` | implementing `LLMClient` |
| I_opaque_ids | does not compile | `object ThreadId is not a member of package org.llm4s.agent.graph` | naming an opaque id: `ThreadId("...")` |
| J_java_api_tool | compiles and runs |  | `llm4s-java-api`'s `JTool.builder`, run by an agent from `Llm4s.createAgentWithTools` |
| K_java_api_structured | compiles and runs |  | `llm4s-java-api`'s `JLlmClient.complete(prompt, Record.class)` into a Java record |

`ToolHandlerError` is the union type `String | ToolParameterError` that a tool handler returns (`org.llm4s.toolapi`),
and the TASTy reader of Scala 2.13 reads no union type. `CompletionOptions` carries a request's tools, so a Scala 2.13
program that names `CompletionOptions` reaches it: probes C, D and H stop there, and E before it gets to uPickle's
`ReadWriter`. A call that leaves the options to their default (probes B and G) does not. Scala 2.13 use is deferred to
after 1.0 and llm4s keeps its Scala 3 idioms ([#1126](https://github.com/llm4s/llm4s/issues/1126),
[#1127](https://github.com/llm4s/llm4s/issues/1127)), so the table records the blocker and the code does not work
around it.
