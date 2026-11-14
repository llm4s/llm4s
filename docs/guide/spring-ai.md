---
layout: page
title: Spring AI
description: "Use any llm4s client as a Spring AI ChatModel or EmbeddingModel, and a Spring AI ChatModel as an llm4s client, with llm4s-spring-ai."
search_title: "Using LLM4S with Spring AI: ChatModel interop"
parent: User Guide
nav_order: 59
---

# Using LLM4S with Spring AI
{: .no_toc }

`llm4s-spring-ai` lets a Spring AI application adopt llm4s one piece at a time, in either direction:

- **llm4s as a Spring AI model.** `Llm4sChatModel` is a Spring AI `ChatModel` (and `StreamingChatModel`) over any llm4s
  client, and `Llm4sEmbeddingModel` an `EmbeddingModel` over an llm4s embedding client. A `ChatClient`, its advisors and
  evaluators then run over any llm4s provider, a `ReliableClient` (retries, circuit breaker, rate limit), guardrails and
  tracing, with no change to the code that calls them.
- **A Spring AI model as an llm4s client.** `SpringAiLlmClient` is an llm4s `LLMClient` over a Spring AI `ChatModel`, so
  llm4s agents, handoffs and RAG run over the model beans the application already configured.

The module is **Experimental**: Spring AI's API still moves between minor releases. It is tested against
**Spring AI 2.0.0 and the latest 2.0.x (2.0.2)**; Spring AI 2.0 needs Spring Boot 4 (Spring Framework 7).

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

## Add the dependency

Spring AI is a `provided` dependency: the module compiles against it, and the application brings the Spring AI release
it uses (with a Spring AI starter, or `spring-ai-model` on its own).

```xml
<dependency>
  <groupId>org.llm4s</groupId>
  <artifactId>llm4s-spring-ai_3</artifactId>
  <version>${llm4s.version}</version>
</dependency>
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-client-chat</artifactId>
  <version>2.0.2</version>
</dependency>
```

## A ChatModel bean from the llm4s starter

With [`llm4s-spring-boot-starter`](spring-boot) and `llm4s-spring-ai` on the classpath, the context has a `ChatModel`
bean over the starter's `JLlmClient`, configured from the `llm4s.*` properties. A `ChatClient` built from it answers
through llm4s:

```java
@RestController
public class AskController {

    private final ChatClient chat;

    public AskController(ChatModel model) {
        this.chat = ChatClient.create(model);   // the llm4s client, as a Spring AI ChatModel
    }

    @GetMapping("/ask")
    public String ask(@RequestParam("question") String question) {
        return chat.prompt().user(question).call().content();
    }
}
```

The bean is registered only when Spring AI is present, the context has a `JLlmClient` (the starter's, or the
application's own) and no `ChatModel` bean yet: an application that defines its own `ChatModel`, or uses a Spring AI
provider starter that does, keeps it.

## llm4s as a Spring AI model

Without the starter, wrap a client yourself:

```java
ChatModel model = Llm4sChatModel.of(Llm4s.createDefaultClient().get());   // a JLlmClient
String answer = ChatClient.create(model).prompt().system("Be brief").user("What is 2+2?").call().content();

EmbeddingModel embeddings = new Llm4sEmbeddingModel(jEmbeddingClient);   // a JEmbeddingClient
float[] vector = embeddings.embed("hello");
```

From Scala, `new Llm4sChatModel(client)` takes any `LLMClient`, and `Llm4sEmbeddingModel.of(embeddingClient, model)` an
`EmbeddingClient` and the `EmbeddingModelConfig` it asks for.

`call` throws an `LlmException` carrying the llm4s error and its kind (`AUTHENTICATION`, `RATE_LIMIT`, ...); `stream`
signals it as the `Flux`'s error. `stream` emits one response per streamed piece of text, then one with no text that
carries the tool calls, finish reason, model and usage; cancelling the subscription interrupts the llm4s call.

Tools work as Spring AI's own models do: the tools a `ChatClient` is given are sent as llm4s tool definitions, the
model's tool calls come back on the `AssistantMessage` with finish reason `TOOL_CALLS`, and the `ChatClient`'s
tool-calling advisor runs them and sends the results back. `Llm4sChatModel` never runs a tool itself.

## A Spring AI model as an llm4s client

```java
JLlmClient client = Llm4s.wrapClient(new SpringAiLlmClient(chatModel));   // chatModel: any Spring AI ChatModel bean
JAgent agent = Llm4s.agentBuilder("assistant", client).tools(tools).build().get();
```

`new SpringAiLlmClient(chatModel)` assumes a 128k-token context window and keeps 4096 tokens for the reply;
`new SpringAiLlmClient(chatModel, contextWindow, reserveCompletion)` states them. llm4s tools are sent as Spring AI
tool callbacks; the Spring AI model returns its tool calls and the llm4s agent runs them. An exception the model throws,
or a `Flux` error while streaming, is a failed result; an `LlmException` gives back the llm4s error it carries.

A runnable example of both directions, with no API key, is
`sbt "samples/runMain org.llm4s.samples.springai.SpringAiInteropExample"`.

## What is carried across

| Spring AI | llm4s | Carried |
|-----------|-------|---------|
| `SystemMessage`, `UserMessage` | `SystemMessage`, `UserMessage` | The text. Media on a `UserMessage` is not carried. |
| `AssistantMessage` with `ToolCall`s | `AssistantMessage` with `ToolCall`s | Text, and each call's id, name and JSON arguments |
| `ToolResponseMessage` | one `ToolMessage` per response | Response data and call id; llm4s's consecutive tool messages become one `ToolResponseMessage`, named from the call |
| `ChatOptions` temperature, top-p, max tokens, presence and frequency penalty | `CompletionOptions` | Both ways |
| `ChatOptions` model | the llm4s client's configured model | Not sent: the response metadata names the model that answered |
| `ChatOptions` stop sequences, top-k | — | Refused with a `VALIDATION` error: no llm4s client sends them |
| `ToolCallingChatOptions` tool callbacks | `ToolFunction`s | Name, description and input schema (object, string with `enum`, number, integer, boolean, array) |
| `ChatResponseMetadata` id, model, usage | `Completion` id, model, `TokenUsage` | Prompt, completion and total tokens; cache read and write tokens |
| `ChatGenerationMetadata` finish reason | — | `TOOL_CALLS` when the reply calls tools, else `STOP`; llm4s's `Completion` has no finish reason |
| `EmbeddingRequest` texts | one llm4s embedding request | Texts in order; the request's options are not read |
| Advisors, memory advisors, `VectorStore`, evaluators | — | Stay on the Spring AI side, over the adapted model |

## Where next

- [Spring Boot](spring-boot): the starter whose `JLlmClient` the `ChatModel` bean adapts.
- [Moving a Java app from LangChain4j or Spring AI](java-migration): to replace Spring AI rather than run beside it.
- [Java](java): the client, conversations and failures the adapters are built on.
