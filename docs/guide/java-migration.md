---
layout: page
title: Moving a Java app from LangChain4j or Spring AI
description: "Map LangChain4j and Spring AI concepts to llm4s-java-api, with compiled Java for tools, records, RAG, streaming, Spring Boot and tests."
parent: User Guide
nav_order: 39
# Issues this page links, by how it cites them; scripts/check-cited-issues.sh fails when one of them closes.
cites_open_issues: [2277, 2278, 2279]
---

# Moving a Java app from LangChain4j or Spring AI
{: .no_toc }

A Java-to-Java guide: what each LangChain4j or Spring AI concept is called in llm4s, and the llm4s Java code that
replaces it - a first call, structured output, a tool, a conversation, embeddings, RAG, streaming, error handling, a
Spring Boot controller and a test. No Scala is needed.
{: .fs-6 .fw-300 }

Every llm4s Java block on this page is compiled by javac in CI and run against `FakeLlmClient` or a scripted client,
with no network: `JavaMigrationGuideSpec` checks that each block is, word for word, in one of the source files the
build compiles (in `llm4s-java-api`, `llm4s-java-rag`, `llm4s-reactor` and the Spring Boot starter's tests), so the
page cannot drift from the API. The LangChain4j and Spring AI side is named, not coded: the class, method and
property names come from their own documentation, tagged like [L1] and listed under [Sources](#sources). For whether
llm4s fits at all, read the [comparison](comparison) first; the same mapping for Scala is
[Coming from LangChain4j or Spring AI](migrating-from-langchain4j).

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## The dependencies

The artifacts end in `_3`, the Scala binary version, which Maven and Gradle need written out:

| You had | Add | Gives you |
|---|---|---|
| `langchain4j`, `langchain4j-open-ai`, ... or `spring-ai-starter-model-*` | `org.llm4s:llm4s-java-api_3` | The client, tools, agents, structured output, embeddings, streaming and `FakeLlmClient`; brings the OpenAI, Anthropic, Ollama, Gemini and OpenAI-compatible providers ([Java](java#add-the-dependency)) |
| `langchain4j-easy-rag`, an `EmbeddingStore`, a Spring AI `VectorStore` | `org.llm4s:llm4s-java-rag_3` | `JRag`: ingest, search and ask, with an in-memory or SQLite store ([Java RAG](java-rag)) |
| Reactor `Flux` from a model | `org.llm4s:llm4s-reactor_3` | `Streams.toFlux` ([Java Reactor](java-reactor)) |
| A LangChain4j or Spring AI Spring Boot starter | `org.llm4s:llm4s-spring-boot-starter_3` | `LLM4STemplate`, configured from `llm4s.*` properties ([Spring Boot](spring-boot)) |
| `langchain4j-bedrock`, `spring-ai-starter-model-bedrock-converse` | `org.llm4s:llm4s-bedrock_3` | The Bedrock provider (Converse API), configured by name ([providers](providers)) |

llm4s needs **JDK 21**.

## The concepts side by side

| LangChain4j | Spring AI | llm4s (Java) |
|---|---|---|
| `ChatModel`, built with e.g. `OpenAiChatModel.builder()` [L1][L3] | `ChatModel`, and the fluent `ChatClient` [S1] | `JLlmClient`, from `Llm4s.openAi()` and the other builders, or `Llm4s.createDefaultClient()`; in Spring, the `LLM4STemplate` bean |
| An AI Service: an interface `AiServices` implements [L1] | `ChatClient.prompt().user(...).call().content()` [S1] | `client.complete(prompt)`. No declarative interface yet ([#2277](https://github.com/llm4s/llm4s/issues/2277)) |
| A POJO or record as an AI Service return type [L1] | `.call().entity(SomeClass.class)` [S1] | `client.complete(prompt, SomeRecord.class)` |
| `@Tool` methods, `@P` parameters [L2] | `@Tool` methods, `@ToolParam` parameters; `MethodToolCallback`, `FunctionToolCallback` [S2] | `@Tool` methods with `@Param` parameters (`JTool.fromAnnotated`), or `JTool.builder(...)` with a lambda; run by a `JAgent` |
| `ChatMemory`, `MessageWindowChatMemory`, `@MemoryId` with a `ChatMemoryProvider` [L1] | `ChatMemory`, `MessageWindowChatMemory`, `MessageChatMemoryAdvisor`, `ChatMemory.CONVERSATION_ID` [S3] | A `Conversation` you build and pass (`ConversationBuilder`), or a `JAgent` thread you continue (`continueConversation`) |
| `EmbeddingModel` [L4] | `EmbeddingModel` [S4] | `JEmbeddingClient`, from `Llm4s.createEmbeddingClient("provider/model")` |
| `EmbeddingStore`, `EmbeddingStoreIngestor`, `ContentRetriever`, `RetrievalAugmentor` [L4] | `VectorStore`, `QuestionAnswerAdvisor` [S1][S4] | `JRag` in `llm4s-java-rag`: `ingest`, `ingestText`, `search`, `ask` |
| `StreamingChatModel`; `TokenStream` (`onPartialResponse`, `onCompleteResponse`, `onError`, `start`) or `Flux<String>` from an AI Service [L1] | `.stream().content()`, a `Flux<String>` [S1] | `client.stream(prompt, listener)`, `client.streamAsPublisher(prompt)` (a JDK `Flow.Publisher`), `Streams.toFlux` in `llm4s-reactor` |
| Unchecked exceptions under `LangChain4jException`: `RetriableException` (`RateLimitException`, `TimeoutException`, ...), `NonRetriableException` (`AuthenticationException`, ...) [L5] | `TransientAiException` and `NonTransientAiException`, retried per `spring.ai.retry.*` [S5] | An `LlmResult` you check; `get()` throws the unchecked `LlmException`, whose `getKind()` is an `LlmErrorKind` and `isRecoverable()` says whether a retry may succeed |
| `langchain4j.open-ai.chat-model.*` and the other starters' properties [L6] | `spring.ai.openai.*`, `spring.ai.anthropic.*`, ... [S6] | `llm4s.provider`, `llm4s.model`, `llm4s.api-key`, or `llm4s.providers.<name>.*` for any provider ([Spring Boot](spring-boot#configure-it)) |
| `CompletableFuture<T>` as an AI Service return type [L1] | - | `client.completeAsync(prompt)`, `agent.runAsync(query)` ([Java](java#asynchronous-calls)) |

Three differences run through every section below:

- **A call returns a value, not only an exception.** `complete` returns an `LlmResult<T>`: check `isSuccess()`, or
  call `get()`, which returns the value or throws `LlmException` (unchecked). Nothing else is thrown for a provider
  failure.
- **You call the client; nothing implements your interface.** Where a LangChain4j AI Service method or a Spring AI
  `ChatClient` chain was, there is one method call on `JLlmClient` (or `LLM4STemplate`, or `JAgent` when tools run).
- **Configuration is by provider section.** Outside Spring, `Llm4s.createDefaultClient()` reads a named section of
  `application.conf`, or a builder takes plain values; in Spring, the starter reads `llm4s.*` properties. See
  [Configuration](#configuration).

## A first call

LangChain4j builds a `ChatModel` (`OpenAiChatModel.builder().apiKey(...).modelName(...)`) and calls it, or an AI
Service on it [L1][L3]; Spring AI injects a `ChatClient.Builder` and calls `prompt().user(...).call().content()` [S1].
In llm4s, a builder makes the client from plain values, or `createDefaultClient()` from configuration. Neither sends
a request, and neither throws: a missing key is a failed result that names it.

```java
LlmResult<JLlmClient> openai = Llm4s.openAi()
    .apiKey(apiKey)
    .model("gpt-4o-mini")
    .build();
LlmResult<JLlmClient> fromConfig = Llm4s.createDefaultClient();   // application.conf
```

```java
String answer = client.complete("Say hello in French").get();   // LlmException if the call failed
```

`JLlmClient` is `AutoCloseable` (it holds an HTTP client): keep one per provider and close it on shutdown. The test
runs the call against `FakeLlmClient` and checks the prompt it sent.

## Structured output into a record

Where LangChain4j returns a record from an AI Service method [L1] and Spring AI calls `.entity(SomeClass.class)` [S1],
pass the record class to `complete`:

```java
record Invoice(String customer, double total, List<String> items) {}

Invoice invoice = client.complete("Extract the invoice: ACME paid 9.50 for two bolts and a nut", Invoice.class).get();
```

The record's components become the JSON Schema sent as the response format, and the reply is read back into the
record; a reply that does not fit is a failed result naming the field. Components can be strings, numbers, booleans,
enums, records, `List`s and `Optional`s ([Java: structured output](java#structured-output) has the rules and what each
provider does with the schema).

## A tool

LangChain4j's `@Tool` / `@P` [L2] and Spring AI's `@Tool` / `@ToolParam` [S2] map to llm4s's `@Tool` / `@Param`, in
`org.llm4s.javaapi`. The tool's schema is derived from the method; a `JAgent` offers it to the model, runs it when the
model asks, and feeds the output back:

```java
public static final class OrderTools {
    @Tool(description = "The shipping status of an order")
    public String orderStatus(@Param(name = "orderId", description = "The order number") String orderId) {
        return "Order " + orderId + " shipped on Monday";
    }
}
```

```java
List<JTool> tools = JTool.fromAnnotated(new OrderTools()).get();
JAgent agent = Llm4s.createAgentWithTools(client, tools).get();

String answer = agent.run("Where is order 42?").get().answer().orElse("");
```

`@Param` names each parameter, because a class compiled without `-parameters` has no names to read. A lambda tool is
`JTool.builder("name", "description").param(...).handler(args -> ...)` ([Java: tools](java#tools)). The test scripts
a model that asks for `orderStatus` once and checks that the answer is the tool's output.

## A conversation with memory

LangChain4j gives an AI Service a `ChatMemory`, per user with `@MemoryId` and a `ChatMemoryProvider` [L1]; Spring AI
adds a `MessageChatMemoryAdvisor` and passes `ChatMemory.CONVERSATION_ID` on each call [S3]. llm4s has two forms.
The history as a value you keep and pass:

```java
Conversation conversation = ConversationBuilder.create()
    .system("You are a helpful assistant.")
    .user("My name is Ada.")
    .assistant(firstAnswer)
    .user("What is my name?")
    .build();
String answer = client.complete(conversation).get();
```

Or an agent thread that keeps it for you:

```java
JAgent agent = Llm4s.createAgent(client);
JAgentResult first = agent.run("My name is Ada.").get();
JAgentResult second = agent.continueConversation(first, "What is my name?").get();   // same thread
```

Keep the latest `JAgentResult` per user where you kept a memory id; `agent.forget(result)` drops the thread. For a
window like `MessageWindowChatMemory`'s, and a system prompt, build the agent with `Llm4s.agentBuilder`:

```java
JAgent agent = Llm4s.agentBuilder("assistant", client)
    .systemPrompt("You are a helpful assistant.")
    .contextWindow(JContextWindow.lastMessages(20))   // each call is sent the last 20 messages
    .build()
    .get();
```

The window prunes what each model call is sent, not what the thread keeps; the builder also takes tools and guardrails
([Java](java#an-agent-with-a-system-prompt-guardrails-and-a-context-window)). Facts that outlive a conversation are
`llm4s-memory`'s job ([memory](agents/memory)), a Scala API.

## Embeddings

LangChain4j's and Spring AI's `EmbeddingModel` [L4][S4] is `JEmbeddingClient`. Create it from
`Llm4s.createEmbeddingClient("openai/text-embedding-3-small")` (provider and model) or `createDefaultEmbeddingClient()`,
then:

```java
List<float[]> vectors = embedder.embed(List.of("The cat sat on the mat.", "A cat lay on the mat.")).get().vectors();
double similarity = JEmbeddings.cosineSimilarity(vectors.get(0), vectors.get(1));
```

`embed(texts, JEmbeddingPurpose.QUERY)` and `DOCUMENT` tell providers that distinguish the two which one a text is
([Java: embeddings](java#embeddings)).

## RAG

LangChain4j's `EmbeddingStoreIngestor` and `ContentRetriever` [L4], and Spring AI's `VectorStore` with a
`QuestionAnswerAdvisor` [S1][S4], become one `JRag` pipeline in `llm4s-java-rag`: it chunks, embeds, stores, retrieves
and asks the model, and returns the answer with its sources.

```java
try (JRag rag = JRag.builder().embeddings(embedder).llm(client).build().get()) {
    rag.ingestText("Refunds are paid within 14 days.", "refund-policy").get();
    JRagAnswer answer = rag.ask("How long do refunds take?").get();
    return answer.answer() + " [" + answer.sources().get(0).documentId() + "]";
}
```

`rag.ingest(path)` reads files and directories, `search(query, k)` retrieves without the model, and
`.store(JRag.sqlite(path))` keeps the index on disk ([Java RAG](java-rag)). The test runs this block over word-count
embeddings and a model that answers from the first retrieved chunk.

## Streaming

LangChain4j streams through a `TokenStream` or a `Flux<String>` [L1], Spring AI through `.stream().content()`, a
`Flux<String>` [S1]. In llm4s a listener receives the pieces, and the returned `CompletionStream` awaits or cancels
the call; or take the text as a JDK `Flow.Publisher`:

```java
JCompletion reply;
try (CompletionStream stream = client.stream("Tell me a short story", piece -> System.out.print(piece)).get()) {
    reply = stream.await().get();
}

Flow.Publisher<String> pieces = client.streamAsPublisher("Tell me a short story");
pieces.subscribe(subscriber);
```

With `llm4s-reactor`, the same reply is a Reactor `Flux`:

```java
Flux<String> reply = Streams.toFlux(client, "Explain monads in one sentence");
```

Streaming yields text; tool-call deltas are not streamed ([Java: streaming](java#streaming-the-reply)).

## Error handling

LangChain4j throws unchecked exceptions split into `RetriableException` and `NonRetriableException` [L5]; Spring AI
throws `TransientAiException` or `NonTransientAiException` and retries the transient ones per `spring.ai.retry.*`
[S5]. llm4s returns the failure as a value, with one `LlmErrorKind` per kind of failure:

```java
LlmResult<String> result = client.complete("What is 2+2?");
if (result.isFailure()) {
    LlmException error = result.getError();
    return switch (error.getKind()) {
        case AUTHENTICATION, CONFIGURATION -> "Fix the setup: " + error.getMessage();
        case RATE_LIMIT, TIMEOUT, NETWORK -> "Try again later";
        default -> error.isRecoverable() ? "Retry: " + error.getMessage() : "Failed: " + error.getMessage();
    };
}
return result.get();
```

Code that prefers exceptions calls `get()` and catches the unchecked `LlmException`:

```java
try {
    return client.complete("What is 2+2?").get();
} catch (LlmException e) {
    return "Failed (" + e.getKind() + "): " + e.getMessage();
}
```

The kinds are `AUTHENTICATION`, `RATE_LIMIT`, `TIMEOUT`, `NETWORK`, `SERVICE`, `VALIDATION`, `CONFIGURATION`,
`CANCELLED` and `OTHER`; `getRetryAfter()` and `getStatusCode()` carry what the provider said
([Java: handling a failure](java#handling-a-failure)). The client does not retry for you, as Spring AI's retry
template does.

## A Spring Boot controller

Spring AI's controllers inject a `ChatClient.Builder` [S1]; LangChain4j's inject an `@AiService` interface or a
`ChatModel` the starter configured [L6]. With `llm4s-spring-boot-starter`, inject `LLM4STemplate`:

```java
import org.llm4s.spring.LLM4STemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AskController {

    private final LLM4STemplate llm;

    public AskController(LLM4STemplate llm) {
        this.llm = llm;
    }

    @GetMapping("/ask")
    public String ask(@RequestParam("question") String question) {
        return llm.complete(question);
    }
}
```

`complete` blocks and throws `LlmException` on failure, so an `@ExceptionHandler` can map `getKind()` to a status;
`tryComplete` returns the `LlmResult`, and `completeAsync` a `CompletableFuture<String>` on the starter's executor
([Spring Boot](spring-boot#use-the-template)). The starter auto-configures the client, the template, a health
indicator, Micrometer metrics and an agent over your beans' `@Tool` methods
([Spring Boot](spring-boot#an-agent-with-tools)); it does not yet auto-configure embeddings or streaming endpoints
([#2279](https://github.com/llm4s/llm4s/issues/2279)).

## A test with FakeLlmClient

`FakeLlmClient`, in `llm4s-java-api` itself, answers from a script and records the prompts your code sent. Its
`client()` is a real `JLlmClient`, so the code under test needs no interface or mock:

```java
@Test
void greetsInFrench() {
    FakeLlmClient fake = FakeLlmClient.replying("Bonjour");

    String greeting = new Greeter(fake.client()).greet();

    assertEquals("Bonjour", greeting);
    assertEquals(List.of("Say hello in French"), fake.prompts());
}
```

`Greeter` is your class: here one whose `greet()` returns `client.complete("Say hello in French").get()`.
`FakeLlmClient.builder().failWith(LlmErrorKind.RATE_LIMIT, "slow down")` scripts a failure. In a Spring test, a
`JLlmClient` bean of your own (the fake's `client()`) replaces the starter's. The fake answers plain and structured
calls; to script tool calls, wrap `llm4s-testkit`'s `ScriptedLLMClient` with `Llm4s.wrapClient`
([testing](testing-with-the-testkit)).

## Configuration

The model, key and endpoint, provider by provider. LangChain4j's builder methods and starter properties come from
[L3][L6]-[L10], Spring AI's properties from [S6]-[S10].

| Provider | LangChain4j | Spring AI | llm4s Java builder | llm4s Spring Boot properties |
|---|---|---|---|---|
| OpenAI | `OpenAiChatModel.builder().apiKey(..).modelName(..)`; `langchain4j.open-ai.chat-model.api-key`, `.model-name` | `spring.ai.openai.api-key`, `spring.ai.openai.chat.model` | `Llm4s.openAi().apiKey(..).model(..)` | `llm4s.provider=openai`, `llm4s.model`, `llm4s.api-key` |
| Azure OpenAI | `AzureOpenAiChatModel.builder().endpoint(..).apiKey(..).deploymentName(..)`; `langchain4j.azure-open-ai.chat-model.*` | Spring AI 1.x: `spring.ai.azure.openai.api-key`, `.endpoint`, `.chat.options.deployment-name`; from 2.0, its OpenAI integration [S7] | `Llm4s.azureOpenAi().endpoint(..).apiKey(..).model(deployment)` | `llm4s.providers.<name>.provider=azure`, `.model`, `.api-key`, `.endpoint`, `.api-version` |
| Anthropic | `AnthropicChatModel.builder().apiKey(..).modelName(..)`; `langchain4j.anthropic.chat-model.api-key` | `spring.ai.anthropic.api-key`, `spring.ai.anthropic.chat.model` | `Llm4s.anthropic().apiKey(..).model(..)` | `llm4s.provider=anthropic`, `llm4s.model`, `llm4s.api-key` |
| Ollama | `OllamaChatModel.builder().baseUrl(..).modelName(..)`; `langchain4j.ollama.chat-model.base-url`, `.model-name` | `spring.ai.ollama.base-url`, `spring.ai.ollama.chat.model` | `Llm4s.ollama().baseUrl(..).model(..)` | `llm4s.provider=ollama`, `llm4s.model`, `llm4s.base-url` |
| Amazon Bedrock | `BedrockChatModel.builder().modelId(..).region(..)` (Converse API) | `spring.ai.bedrock.aws.region`, `spring.ai.bedrock.converse.chat.model` | none: an `application.conf` section, below | `llm4s.providers.<name>.provider=bedrock`, `.model`, `.region` |

The builders, in Java:

```java
LlmResult<JLlmClient> openai = Llm4s.openAi()
    .apiKey(openAiKey)
    .model("gpt-4o-mini")
    .build();
LlmResult<JLlmClient> azure = Llm4s.azureOpenAi()
    .endpoint("https://my-resource.openai.azure.com")
    .apiKey(azureKey)
    .model("my-gpt-4o-deployment")   // the deployment name
    .build();
LlmResult<JLlmClient> anthropic = Llm4s.anthropic()
    .apiKey(anthropicKey)
    .model("claude-sonnet-4-20250514")
    .build();
LlmResult<JLlmClient> ollama = Llm4s.ollama()
    .baseUrl("http://localhost:11434")
    .model("llama3.2")
    .build();
```

Azure with Microsoft Entra ID instead of a key is `.entraId()` (with `llm4s-azure-identity`). Bedrock has no Java
builder: add `llm4s-bedrock` and name a section in `src/main/resources/application.conf`, which
`Llm4s.createDefaultClient()` loads. Credentials come from the AWS SDK's default chain (environment, `~/.aws`, an
instance or task role) unless the section sets `accessKeyId` and `secretAccessKey`:

```hocon
llm4s {
  providers {
    provider = "claude-bedrock"

    claude-bedrock {
      provider = "bedrock"
      model    = "anthropic.claude-3-5-sonnet-20241022-v2:0"
      region   = "us-east-1"
    }
  }
}
```

The same in Spring Boot, where the starter reads properties rather than `application.conf`:

```properties
llm4s.providers.provider=claude-bedrock
llm4s.providers.claude-bedrock.provider=bedrock
llm4s.providers.claude-bedrock.model=anthropic.claude-3-5-sonnet-20241022-v2:0
llm4s.providers.claude-bedrock.region=us-east-1
```

With `OPENAI_API_KEY`, `ANTHROPIC_API_KEY` or `AZURE_OPENAI_API_KEY` set, a section can leave out its key: each
provider module binds its vendor's variable ([configuration](../getting-started/configuration)).

## What has no direct equivalent yet

Plainly, as of this page's sources:

- **Declarative AI services.** No annotated interface that llm4s implements, as LangChain4j's AI Services and
  `@AiService` do [L1][L6], and so no `@SystemMessage` / `@UserMessage` prompt templates: you call the client.
  Tracked in [#2277](https://github.com/llm4s/llm4s/issues/2277).
- **A chat-memory store keyed by a conversation id**, as Spring AI's `ChatMemoryRepository`s are [S3]. An agent keeps
  its threads in memory until `forget`; you keep the `JAgentResult` that names each one.
- **Guardrails written in Java.** `JGuardrails` offers the built-in ones (length, profanity, regex, JSON); a guardrail
  of your own needs the Scala `Agent.builder` and `Llm4s.wrapAgent`.
- **Spring AI interoperability.** An llm4s client cannot be used as a Spring AI `ChatModel`, nor the reverse
  ([#2278](https://github.com/llm4s/llm4s/issues/2278)).
- **More Spring Boot auto-configuration.** Embeddings and streaming endpoints
  ([#2279](https://github.com/llm4s/llm4s/issues/2279)).
- **Other vector stores from Java.** `JRag` keeps its index in memory or in SQLite; the pgvector, Qdrant,
  Elasticsearch, OpenSearch and Redis stores of `llm4s-rag` are Scala APIs ([vector stores](vector-store)).
- **Automatic retries.** Spring AI retries transient failures for you [S5]; an llm4s Java client reports them
  (`isRecoverable()`) and leaves the retry to you.
- **Breadth.** LangChain4j and Spring AI list many more model providers, vector stores and document loaders than
  llm4s has modules for ([comparison](comparison)).
- **Scala types in a few signatures.** `Conversation` and `ProviderConfig` are Scala classes
  ([Java](java#what-llm4s-java-api-is)).

## Sources

Fetched on 2026-11-04, against LangChain4j 1.23.0 and Spring AI 2.0.2 (and 1.1 for the Azure row).

| Tag | Page | Names used |
|---|---|---|
| L1 | [LangChain4j: AI Services](https://docs.langchain4j.dev/tutorials/ai-services) | `AiServices`, `ChatModel`, `StreamingChatModel`, `TokenStream`, `ChatMemory`, `MessageWindowChatMemory`, `@MemoryId`, `ChatMemoryProvider`, `ContentRetriever`, return types (POJO, `Flux<String>`, `CompletableFuture`) |
| L2 | [LangChain4j: tools](https://docs.langchain4j.dev/tutorials/tools) | `@Tool`, `@P` (`dev.langchain4j.agent.tool`) |
| L3 | [LangChain4j: OpenAI](https://docs.langchain4j.dev/integrations/language-models/open-ai) | `OpenAiChatModel.builder()`, `apiKey`, `modelName`, `langchain4j.open-ai.chat-model.*` |
| L4 | [LangChain4j: RAG](https://docs.langchain4j.dev/tutorials/rag) | `EmbeddingModel`, `EmbeddingStore`, `EmbeddingStoreIngestor`, `EmbeddingStoreContentRetriever`, `RetrievalAugmentor` |
| L5 | [LangChain4j: `dev.langchain4j.exception`](https://docs.langchain4j.dev/apidocs/dev/langchain4j/exception/package-tree.html) | `LangChain4jException`, `RetriableException`, `NonRetriableException` and their subclasses |
| L6 | [LangChain4j: Spring Boot integration](https://docs.langchain4j.dev/tutorials/spring-boot-integration) | `@AiService`, starter properties, a controller injecting an AI service |
| L7 | [LangChain4j: Azure OpenAI](https://docs.langchain4j.dev/integrations/language-models/azure-open-ai) | `AzureOpenAiChatModel`, `endpoint`, `apiKey`, `deploymentName`, `langchain4j.azure-open-ai.chat-model.*` |
| L8 | [LangChain4j: Anthropic](https://docs.langchain4j.dev/integrations/language-models/anthropic) | `AnthropicChatModel`, `langchain4j.anthropic.chat-model.api-key` |
| L9 | [LangChain4j: Ollama](https://docs.langchain4j.dev/integrations/language-models/ollama) | `OllamaChatModel`, `baseUrl`, `modelName`, `langchain4j.ollama.chat-model.*` |
| L10 | [LangChain4j: Amazon Bedrock](https://docs.langchain4j.dev/integrations/language-models/amazon-bedrock) | `BedrockChatModel`, `modelId`, `region`, the Converse API |
| S1 | [Spring AI: ChatClient](https://docs.spring.io/spring-ai/reference/api/chatclient.html) | `ChatClient`, `ChatClient.Builder`, `call().content()`, `entity()`, `stream().content()`, `MessageChatMemoryAdvisor`, `QuestionAnswerAdvisor` |
| S2 | [Spring AI: tool calling](https://docs.spring.io/spring-ai/reference/api/tools.html) | `@Tool`, `@ToolParam`, `MethodToolCallback`, `FunctionToolCallback` |
| S3 | [Spring AI: chat memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html) | `ChatMemory`, `MessageWindowChatMemory`, `ChatMemoryRepository`, `ChatMemory.CONVERSATION_ID` |
| S4 | [Spring AI: vector databases](https://docs.spring.io/spring-ai/reference/api/vectordbs.html) | `VectorStore`, `EmbeddingModel` |
| S5 | [Spring AI: `org.springframework.ai.retry`](https://docs.spring.io/spring-ai/docs/current/api/org/springframework/ai/retry/package-summary.html) | `TransientAiException`, `NonTransientAiException` |
| S6 | [Spring AI: OpenAI chat](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html) | `spring.ai.openai.api-key`, `spring.ai.openai.chat.model`, `spring.ai.retry.*` |
| S7 | [Spring AI 1.1: Azure OpenAI chat](https://docs.spring.io/spring-ai/reference/1.1/api/chat/azure-openai-chat.html), [2.0](https://docs.spring.io/spring-ai/reference/api/chat/azure-openai-chat.html) | `spring.ai.azure.openai.*` (1.x); from 2.0.0-M5, Azure through the OpenAI integration |
| S8 | [Spring AI: Anthropic chat](https://docs.spring.io/spring-ai/reference/api/chat/anthropic-chat.html) | `spring.ai.anthropic.api-key`, `spring.ai.anthropic.chat.model` |
| S9 | [Spring AI: Ollama chat](https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html) | `spring.ai.ollama.base-url`, `spring.ai.ollama.chat.model` |
| S10 | [Spring AI: Bedrock Converse](https://docs.spring.io/spring-ai/reference/api/chat/bedrock-converse.html) | `spring.ai.bedrock.aws.region`, `spring.ai.bedrock.converse.chat.model` |
