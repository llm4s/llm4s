---
layout: page
title: Java AI services
description: "Declare an annotated Java interface and let llm4s-java-api implement it: prompt templates, typed answers, tools, memory, async and streaming."
search_title: "Declarative AI services for Java with LLM4S"
parent: User Guide
nav_order: 21
# Issues this page links, by how it cites them; scripts/check-cited-issues.sh fails when one of them closes.
cites_open_issues: [1474, 2277]
---

# AI services from Java
{: .no_toc }

Declare a Java interface, annotate its methods with their prompts, and `Llm4s.aiService` returns an implementation
that renders each prompt from the method's arguments, calls the model and converts the answer to the method's return
type - text, an enum, a record - running tools when the service has them. It is the shape LangChain4j calls AI
Services, built on the rest of [`llm4s-java-api`](java): [structured output](java#structured-output),
[tools](java#tools), [agent turns](java#an-agent-turn) and [`FakeLlmClient`](java#testing-your-code).
{: .fs-6 .fw-300 }

AI services are **experimental** ([#2277](https://github.com/llm4s/llm4s/issues/2277)): the annotations and the
builder may change after a release of feedback. Every Java block on this page is compiled by javac in CI and run
against `FakeLlmClient` or a scripted client by `JavaAiServicesGuideSpec`.

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

## Declaring a service

The annotations are in `org.llm4s.javaapi.services`. `@SystemMessage` and `@UserMessage` on a method are templates;
`@V("name")` names the parameter that fills `{{name}}`; `@UserMessage` on a parameter makes that argument the user
message as it is.

```java
enum Sentiment { POSITIVE, NEGATIVE, NEUTRAL }

record Ticket(String title, int priority) {}

interface SupportAssistant {

    @SystemMessage("You are a support agent for {{product}}. Answer briefly.")
    String answer(@UserMessage String question, @V("product") String product);

    @UserMessage("Classify the sentiment of: {{text}}")
    Sentiment classify(@V("text") String text);

    LlmResult<Ticket> draftTicket(@UserMessage String complaint);
}
```

The rules, which `build()` checks:

- Every parameter carries exactly one of `@UserMessage`, `@V` and `@MemoryId`.
- A method has one user message: a `@UserMessage` template on the method, or one `@UserMessage` parameter.
- `@SystemMessage` on the interface applies to each method that has none of its own.
- Templates are a plain `{{name}}` substitution, not a template engine: each placeholder is replaced by
  `String.valueOf` of its `@V` argument, in one pass, so a value that itself contains `{{...}}` is sent as it is. A
  placeholder that no `@V` names is an error. The name is given in `@V`, so no `javac -parameters` flag is needed.
- A `null` argument fails the call with kind `VALIDATION` before any request is sent.

## Building and calling it

```java
SupportAssistant assistant = Llm4s.aiService(SupportAssistant.class)
    .client(client)
    .build();

String answer = assistant.answer("How do I reset my password?", "Acme Cloud");
Sentiment sentiment = assistant.classify("The new release is great");
LlmResult<Ticket> ticket = assistant.draftTicket("I was charged twice this month");
```

`client` is any `JLlmClient` - from `Llm4s.openAi()`, `Llm4s.createDefaultClient()`, or a test's `FakeLlmClient`. The
service does not own it: close the client when the application stops. `build()` returns a `java.lang.reflect.Proxy`,
built once and safe to share between threads.

## Return types

| Return type | What the method returns |
|---|---|
| `String` | The answer's text |
| An enum | The constant the model chose, sent as a structured-output schema listing the constants |
| A record | The record read from a structured answer, as [`complete(prompt, Record.class)`](java#structured-output) reads it |
| `JCompletion` | The whole reply: text, model, token usage, estimated cost |
| `LlmResult<T>` of one of the above | The value, or the failure, never thrown |
| `CompletableFuture<T>` of one of the above | The call made on another thread, as [`completeAsync`](java#asynchronous-calls) makes it |
| `Flow.Publisher<String>` | The answer's text as it streams, as [`streamAsPublisher`](java#streaming-the-reply) streams it |

Any other return type - `int`, `List<String>`, `LlmResult<Integer>` - is an error of `build()`. Default methods of the
interface run as written; `equals`, `hashCode` and `toString` are the proxy's own.

## Failures

A method that returns a value throws `LlmException` when the call fails, with the same
[kind](java#handling-a-failure) a `JLlmClient` call would report:

```java
try {
    return assistant.answer("How do I reset my password?", "Acme Cloud");
} catch (LlmException e) {
    return "failed: " + e.getKind();
}
```

Return `LlmResult<T>` for explicit failure handling instead:

```java
LlmResult<Ticket> ticket = assistant.draftTicket("I was charged twice this month");
if (ticket.isFailure() && ticket.getError().getKind() == LlmErrorKind.RATE_LIMIT) {
    return "try again later";
}
```

An answer that does not fit the return type - a record missing a field, a name that is no constant of the enum - is a
failure of kind `VALIDATION` whose message names the field.

## Asynchronous and streamed answers

```java
interface Writer {

    @UserMessage("Write a haiku about {{topic}}")
    CompletableFuture<String> haiku(@V("topic") String topic);

    Flow.Publisher<String> story(@UserMessage String prompt);
}
```

A `CompletableFuture` method returns at once and runs the call on the client's async executor (a virtual thread by
default, or the one [`withAsyncExecutor`](java#asynchronous-calls) names); `cancel(true)` interrupts it. A
`Flow.Publisher<String>` method asks the model once, so it cannot be used with tools or `@MemoryId`.

## Memory

A `@MemoryId` parameter gives each id its own conversation: the turns stored under the id are sent before the new user
message, and the new turn is stored after a successful answer.

```java
ChatMemoryStore store = ChatMemoryStore.inMemory(20);   // the newest 20 turns of each guest
Concierge concierge = Llm4s.aiService(Concierge.class)
    .client(client)
    .memoryStore(store)
    .build();

concierge.chat("room-101", "My name is Ada.");
concierge.chat("room-202", "My name is Alan.");
return concierge.chat("room-101", "What is my name?");   // sees only room-101's turns
```

where `Concierge` declares `String chat(@MemoryId String guest, @UserMessage String message)`. Ids are compared with
`equals`. The default store, `ChatMemoryStore.inMemory()`, keeps every turn in this JVM; implement `ChatMemoryStore`
(`load`, `append`, `clear`) over a database to keep conversations across restarts. A store keeps each turn's user
message and answer - the JSON of a structured answer - not the tool calls made on the way. A method without a
`@MemoryId` parameter remembers nothing.

## Tools

Give the builder objects of public classes with public `@Tool` methods (or `JTool`s, see [Tools](java#tools)), and every
call is answered by one [agent turn](java#an-agent-turn), which runs the tools the model asks for and returns the final
answer:

```java
public static final class OrderTools {

    @Tool(description = "The shipping status of an order")
    public String orderStatus(@Param(name = "orderId", description = "The order id") String orderId) {
        return "Order " + orderId + " shipped yesterday";
    }
}
```

```java
OrderAssistant assistant = Llm4s.aiService(OrderAssistant.class)
    .client(client)
    .tools(new OrderTools())
    .build();
return assistant.ask("Where is order 42?");
```

With tools, an enum or record answer is asked for in the system prompt, as JSON matching its schema, and read from the
turn's final answer. A turn that ends without an answer - its step limit reached - fails. `JCompletion` and
`Flow.Publisher` methods cannot be used with tools.

## Mistakes fail at build()

`build()` reads every method once and reports every mistake at once, each naming its method, as an `LlmException` of
kind `VALIDATION`: an unsupported return type, a parameter without a role, a method with no user message or two, an
unknown placeholder, a `@V` name used twice, two `@MemoryId` parameters, a streaming method with tools or memory, a
`JCompletion` method with tools, and tools that cannot be offered together.

```java
interface Mistaken {

    @UserMessage("Summarise {{document}}")
    String summarise(@V("doc") String document);   // {{document}} is named by no @V
}
```

So a mistake stops the application at startup, not at the first call.

## Testing a service

`FakeLlmClient` scripts the model's answers; for an enum or a record, script the JSON:

```java
FakeLlmClient fake = FakeLlmClient.replying("{\"value\":\"POSITIVE\"}");
SupportAssistant assistant = Llm4s.aiService(SupportAssistant.class)
    .client(fake.client())
    .build();

Sentiment sentiment = assistant.classify("Love it");

assert sentiment == Sentiment.POSITIVE;
assert fake.lastPrompt().orElseThrow().equals("Classify the sentiment of: Love it");
```

An enum answer is the object `{"value": "<CONSTANT>"}`, since structured output needs an object at the root.

## Not here yet

- Spring Boot: the starter does not yet discover AI-service interfaces and register them as beans
  ([#2277](https://github.com/llm4s/llm4s/issues/2277)); declare a `@Bean` that calls
  `Llm4s.aiService(...).client(client).build()` with the starter's `JLlmClient`.
- GraalVM native image needs proxy metadata for each AI-service interface (`proxy-config.json`).
- A Scala equivalent is the subject of the [typed facade RFC](https://github.com/llm4s/llm4s/issues/1474).

## Where next

- [Java](java): the client, structured output, tools and agents these services are built on.
- [Moving a Java app from LangChain4j or Spring AI](java-migration): each LangChain4j concept mapped to llm4s.
- The runnable sample, with no API key: `sbt "samples/runMain org.llm4s.samples.java.JavaAiServiceExample"`.
