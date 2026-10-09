# llm4s-testkit

A scriptable fake `LLMClient` for the tests of code that uses llm4s: canned replies, scripted tool calls, injected
errors and recorded requests, with no network and no API key.

It is for people who **use** llm4s. The checks for people who write a provider module are in
`llm4s-provider-testkit`.

## Install

```scala
// same version as llm4s-core; test scope only
libraryDependencies += "org.llm4s" %% "llm4s-testkit" % llm4sVersion % Test
```

Not yet published; it ships in the next release. It depends on `llm4s-core` only, registers no provider, and has no
test framework in its API.

## What it provides

| Type | What it is |
|---|---|
| `ScriptedLLMClient` | The fake `LLMClient`. `sequence(replies*)` answers call N with reply N, `respondingTo { case conversation => reply }` chooses from the conversation, `always(reply)` repeats one. |
| `Reply` | What one call returns: `Reply.text`, `Reply.toolCall`, `Reply.toolCalls` or `Reply.failure(error)`, adjusted with `withModel`, `withUsage`, `withContent`. |
| `RecordedCall` | One request received: the `conversation`, the `options`, and whether it was `streamed`. |

```scala
val client = ScriptedLLMClient.sequence(
  Reply.toolCall("get_temperature", ujson.Obj("city" -> "Oslo")),
  Reply.text("It is 21 degrees in Oslo.")
)
// run the code under test with `client`, then:
client.callCount        // 2
client.calls.head.lastUserText
```

A call the script has no reply for returns a `Left(ValidationError)` that names the call and the last message. The client
is thread-safe.

## Not included

A fake embedding client, a fake tracer, and recording and replaying real provider traffic. A Java-friendly fake is
tracked in [#1497](https://github.com/llm4s/llm4s/issues/1497).

## More

The full guide, with an `Agent` and a tool tested end to end, is `docs/guide/testing-with-the-testkit.md` (its code is
compiled and run by `TestingWithTestkitGuideSpec` in `modules/samples`). The module's own tests are in
`modules/testkit/src/test`.
