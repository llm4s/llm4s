---
layout: page
title: Coming from Python
description: "LLM code in Python (OpenAI SDK, LangChain, LlamaIndex, Pydantic) next to the same thing in Scala 3 with LLM4S, one Scala concept per step."
search_title: "From Python LangChain and OpenAI SDK to Scala 3 with LLM4S"
parent: User Guide
nav_order: 53
# scripts/check-cited-issues.sh fails when an issue listed here is closed: refresh the tooling section then.
cites_open_issues: [2296]
---

# Coming from Python
{: .no_toc }

You have written LLM code in Python with the OpenAI SDK, LangChain, LlamaIndex or Pydantic, and your team's
services are in Scala. This page puts the Python you know next to the same thing in Scala 3 with LLM4S, and at each
step introduces the one Scala feature that step's code needs, with a link to its page in the
[Scala 3 book](https://docs.scala-lang.org/scala3/book/introduction.html). It is a reference map, not a course: read
the section you need.
{: .fs-6 .fw-300 }

1. TOC
{:toc}

---

Every Scala block below is compiled by a test in this repository (`PythonDevelopersGuideSpec`, in `modules/samples`),
which also checks that the code on this page is, word for word, the code it compiles. Every block that calls a model
is run against `llm4s-testkit`'s `ScriptedLLMClient`, a scripted model, with no API key and no network. Each block
carries its own imports, so you can copy one on its own.

The Python is shown for comparison only and is not run. Each Python block names the library and version it was
checked against (on 2026-11-11) and links to the documentation it is taken from.

## The words side by side

| Python | Scala 3 with LLM4S |
|---|---|
| `x = 1` | `val x = 1` (cannot be reassigned); `var x = 1` if it must change |
| `None` | `None`, inside an `Option[A]`: `Some(value)` or `None`, never `null` |
| a raised exception | a returned `Left(error)`: `Result[A]` is `Either[LLMError, A]` |
| `try` / `except` | `match` on the `Result`, or a `for` comprehension that stops at the first `Left` |
| `@dataclass`, a Pydantic `BaseModel` | `case class` |
| `f"Hello {name}"` | `s"Hello $name"` |
| `lambda x: x.content` | `x => x.content`, or `_.content` |
| `list`, `[f(x) for x in xs]` | `Seq`, `xs.map(f)` |
| `dict` | `Map` |
| `def f(x: int) -> str:` | `def f(x: Int): String =` |
| a decorator (`@tool`) | a value built with a builder (`ToolBuilder`) |
| `async def` / `await` | a callback, or an effect type (`IO`, `ZIO`) from an [effect-system module](effect-systems) |
| `pytest` | ScalaTest, MUnit or another test framework, run by `sbt test` |
| `unittest.mock` | `llm4s-testkit`'s `ScriptedLLMClient` |
| `pip`, `requirements.txt` | dependencies in `build.sbt`, or `//> using dep` lines in a Scala CLI file |
| a Jupyter notebook | the [Scala CLI REPL or a worksheet](../getting-started/repl) |

## 1. A first call

```python
from openai import OpenAI

client = OpenAI()

completion = client.chat.completions.create(
    model="gpt-5.5",
    messages=[
        {"role": "developer", "content": "Talk like a pirate."},
        {
            "role": "user",
            "content": "How do I check if a Python object is an instance of a class?",
        },
    ],
)

print(completion.choices[0].message.content)
```

*(From [openai-python 3.49.0, README](https://github.com/openai/openai-python#usage).)*

In Python a failed call raises an `openai.APIError`. In LLM4S a call that can fail returns a `Result[A]`: a
`Right(value)` when it worked, a `Left(error)` describing what went wrong when it did not. Nothing is thrown.

```scala
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.types.Result

def ask(client: LLMClient, question: String): Result[String] =
  for {
    conversation <- Conversation.fromPrompts("You answer in one sentence.", question)
    completion   <- client.complete(conversation)
  } yield completion.content
```

**New in Scala:** the `for` comprehension
([Scala 3 book: for expressions](https://docs.scala-lang.org/scala3/book/control-structures.html#for-expressions)).
Each `<-` takes the value out of a `Right` and goes on to the next line; the first `Left` stops the chain and becomes
the result. Read it as a sequence of steps, each of which may fail, without an `if error: return` after each one.
The `client` parameter is where Python's `OpenAI()` would go; section 8 shows how to build one from configuration.

## 2. Typed output

```python
from typing import List
from pydantic import BaseModel
from openai import OpenAI


class Step(BaseModel):
    explanation: str
    output: str


class MathResponse(BaseModel):
    steps: List[Step]
    final_answer: str


client = OpenAI()
completion = client.chat.completions.parse(
    model="gpt-4o-2024-08-06",
    messages=[
        {"role": "system", "content": "You are a helpful math tutor."},
        {"role": "user", "content": "solve 8x + 31 = 2"},
    ],
    response_format=MathResponse,
)

message = completion.choices[0].message
if message.parsed:
    print(message.parsed.steps)
    print("answer: ", message.parsed.final_answer)
else:
    print(message.refusal)
```

*(From [openai-python 3.49.0, helpers.md](https://github.com/openai/openai-python/blob/main/helpers.md#auto-parsing-response-content-with-pydantic-models), with [Pydantic 2.14 models](https://docs.pydantic.dev/latest/concepts/models/).)*

The Pydantic model becomes a case class. `llm4s-schema-derivation` derives the JSON schema sent to the model from it
(`derives SchemaOf`), and upickle derives the parser (`ReadWriter`):

```scala
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.schema.*
import org.llm4s.types.Result
import upickle.default.ReadWriter

case class Capital(country: String, capital: String) derives SchemaOf, ReadWriter

def capitalOf(client: LLMClient, country: String): Result[Capital] =
  for {
    conversation <- Conversation.fromPrompts("Answer with the country and its capital.", s"Capital of $country?")
    capital      <- client.completeStructuredOf[Capital](conversation)
  } yield capital
```

**New in Scala:** the `case class`
([Scala 3 book: case classes](https://docs.scala-lang.org/scala3/book/domain-modeling-tools.html#case-classes)), an
immutable record with named, typed fields, equality and a readable `toString`, like a frozen dataclass. A reply that
does not fit the shape is a `Left`, not an exception. [Derived schemas](derived-schemas) covers
nested classes, optional fields and enums.

## 3. A tool

```python
from langchain.tools import tool

@tool
def search_database(query: str, limit: int = 10) -> str:
    """Search the customer database for records matching the query.

    Args:
        query: Search terms to look for
        limit: Maximum number of results to return
    """
    return f"Found {limit} results for '{query}'"
```

*(From [LangChain 1.4.9, Tools](https://docs.langchain.com/oss/python/langchain/tools).)*

LangChain reads the tool's schema from the function's signature and docstring. In LLM4S you declare it with the
`Schema` DSL and give `ToolBuilder` a handler that reads the arguments:

```scala
import org.llm4s.toolapi.{ SafeParameterExtractor, Schema, ToolBuilder, ToolFunction }
import org.llm4s.types.Result

def searchDatabase: Result[ToolFunction[Map[String, Any], String]] = {
  val schema = Schema
    .`object`[Map[String, Any]]("A search of the customer database")
    .withRequiredField("query", Schema.string("What to search for"))
    .withOptionalField("limit", Schema.integer("The most records to return (default 10)"))

  def handler(params: SafeParameterExtractor): Either[String, String] =
    for {
      query <- params.getString("query")
      limit <- params.getOptionalInt("limit").left.map(_.getMessage)
    } yield s"Found ${limit.getOrElse(10)} results for '$query'"

  ToolBuilder[Map[String, Any], String](
    "search_database",
    "Search the customer database for records matching the query.",
    schema
  ).withHandler(handler).buildSafe()
}
```

**New in Scala:** `Option`
([Scala 3 book: using Option to replace null](https://docs.scala-lang.org/scala3/book/fp-functional-error-handling.html#using-option-to-replace-null)).
Python's `limit: int = 10` is a value that may be missing. Scala says so in the type: `getOptionalInt` gives an
`Option[Int]`, either `Some(2)` or `None`, and `limit.getOrElse(10)` supplies the default. A missing required
argument (`query`) is a `Left` with a message the model can act on. (`_.getMessage` is a short function, explained in
section 5.) [Build your own tool](custom-tools) covers the rest of the DSL, and `Tool.of` derives the schema from a
case class instead.

## 4. An agent loop

```python
from langchain.agents import create_agent
from langgraph.checkpoint.memory import InMemorySaver


def get_user_info() -> str:
    """Look up information about the current user."""
    return "No user profile on file."


agent = create_agent(
    model="google_genai:gemini-3.6-flash",
    tools=[get_user_info],
    checkpointer=InMemorySaver(),
)

thread_config = {"configurable": {"thread_id": "1"}}
response = agent.invoke(
    {"messages": [{"role": "user", "content": "Hi! My name is Bob."}]},
    thread_config,
)["messages"][-1].content
```

*(Abridged from [LangChain 1.4.9 with LangGraph 1.2.20, Short-term memory](https://docs.langchain.com/oss/python/langchain/short-term-memory).)*

An LLM4S `Agent` runs the same loop: call the model, run the tools it asks for, feed back their results, until it
answers. `agent.run` starts a conversation (a thread) and `continueConversation` adds a turn to it, as invoking with
the same `thread_id` does:

```scala
import org.llm4s.agent.{ Agent, AgentResult, AgentStatus }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.Result

def describe(result: AgentResult): String =
  result.status match {
    case AgentStatus.Completed(answer)  => answer
    case AgentStatus.Blocked(_, reason) => s"Blocked: $reason"
    case other                          => s"Stopped: $other"
  }

def twoTurns(client: LLMClient, tools: ToolRegistry): Result[Seq[String]] =
  for {
    agent  <- Agent.builder("support", client).withTools(tools).withSystemPrompt("You help support staff.").build()
    first  <- agent.run("Find customers named Ada")
    second <- agent.continueConversation(first, "Only the first two, please")
  } yield Seq(describe(first), describe(second))
```

Pass it `new ToolRegistry(Seq(tool))`, with the tool from section 3.

**New in Scala:** the `match` expression
([Scala 3 book: match expressions](https://docs.scala-lang.org/scala3/book/control-structures.html#match-expressions)).
A run ends in one of a fixed set of statuses (completed, blocked by a guardrail, out of steps, waiting for a human),
and `match` picks the case and pulls out its fields, like Python's `match` statement. Without the last `case other`, the
compiler would warn about the statuses no case covers. [Agents](agents/) covers guardrails, handoffs and graphs.

## 5. RAG

```python
from llama_index.core import VectorStoreIndex, SimpleDirectoryReader

documents = SimpleDirectoryReader("YOUR_DATA_DIRECTORY").load_data()
index = VectorStoreIndex.from_documents(documents)
query_engine = index.as_query_engine()
query_engine.query("YOUR_QUESTION")
```

*(Abridged from [llama-index-core 0.14.25, README](https://github.com/run-llama/llama_index/blob/main/README.md).)*

`llm4s-rag` is the index and the query engine in one: it chunks and embeds what you ingest, retrieves the closest
chunks, and asks the model to answer from them.

```scala
import org.llm4s.rag.RAG
import org.llm4s.types.Result

def askTheDocs(rag: RAG, question: String): Result[String] =
  for {
    _      <- rag.ingestText("Refunds are paid within 14 days of a return.", "refunds")
    _      <- rag.ingestText("Orders over 50 EUR ship for free.", "shipping")
    answer <- rag.queryWithAnswer(question)
  } yield answer.answer

def sources(rag: RAG, question: String): Result[Seq[String]] =
  rag.query(question).map(results => results.map(_.content))
```

Building the `RAG` (`RAG.builder()`, with an embedding provider, a vector store and the chat client) is shown on
[RAG in Scala](../scala-ai/rag); `withDocuments(path)` loads a directory, as `SimpleDirectoryReader` does.

**New in Scala:** anonymous functions
([Scala 3 book: anonymous functions](https://docs.scala-lang.org/scala3/book/fun-anonymous-functions.html)).
`results => results.map(_.content)` is Python's `lambda results: [r.content for r in results]`. `_.content` is the
short form of `r => r.content`, for a function that uses its argument once. `map` on a `Result` changes the value in a
`Right` and passes a `Left` through.

## 6. Async and streaming

```python
import asyncio
from openai import AsyncOpenAI

client = AsyncOpenAI()


async def main():
    stream = await client.responses.create(
        model="gpt-5.5",
        input="Write a one-sentence bedtime story about a unicorn.",
        stream=True,
    )

    async for event in stream:
        print(event)


asyncio.run(main())
```

*(From [openai-python 3.49.0, README: Streaming responses](https://github.com/openai/openai-python#streaming-responses).)*

`streamComplete` hands each piece of the answer to a function you pass, as it arrives, and returns the whole
completion at the end:

```scala
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation }
import org.llm4s.types.Result

def streamAnswer(client: LLMClient, question: String, onText: String => Unit): Result[String] =
  for {
    conversation <- Conversation.fromPrompts("You answer in one sentence.", question)
    completion   <- client.streamComplete(conversation, CompletionOptions(), chunk => chunk.content.foreach(onText))
  } yield completion.content
```

Call it as `streamAnswer(client, "What is Scala?", print)`.

**New in Scala:** function types
([Scala 3 book: higher-order functions](https://docs.scala-lang.org/scala3/book/fun-hofs.html)). `String => Unit`
is the type of a function that takes a `String` and returns nothing useful (`Unit`, Python's `None` return), so
`onText` is a callback parameter, typed.

**Which effect system will my team use?** The call above blocks its thread until the answer is complete, which is
fine in a script or a plain service. A Scala team that writes asynchronous code usually standardises on one library,
the way a Python team standardises on `asyncio`: [cats-effect](cats-effect) (`IO`, with fs2 streams),
[ZIO](zio), or [Pekko Streams](pekko). LLM4S has a thin module for each; [Pick your stack](effect-systems) shows one
program in all of them, so you can follow whichever your team already uses.

## 7. Tests

```python
from unittest.mock import MagicMock


def test_ask_returns_the_answer():
    client = MagicMock()
    client.chat.completions.create.return_value.choices[0].message.content = "Paris"

    assert ask(client, "Capital of France?") == "Paris"
    client.chat.completions.create.assert_called_once()
```

*(Written with [pytest 9.2.0](https://docs.pytest.org/en/stable/getting-started.html) and Python's
[`unittest.mock`](https://docs.python.org/3/library/unittest.mock.html); `ask` wraps section 1's call.)*

A mock reproduces the SDK's object graph by hand. `llm4s-testkit`'s `ScriptedLLMClient` is a real `LLMClient` that
answers from a script and records what was sent, so the test exercises your code, not a mock of the SDK. This
ScalaTest class tests `ask` from section 1:

```scala
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AskSpec extends AnyFlatSpec with Matchers {
  "ask" should "return the model's answer" in {
    val client = ScriptedLLMClient.sequence(Reply.text("Paris"))

    ask(client, "Capital of France?") shouldBe Right("Paris")
    client.callCount shouldBe 1
    client.calls.head.messages.last.content shouldBe "Capital of France?"
  }
}
```

**New in Scala:** traits
([Scala 3 book: traits](https://docs.scala-lang.org/scala3/book/domain-modeling-tools.html#traits)).
`AnyFlatSpec with Matchers` mixes two traits into the class: one supplies the `"..." should "..." in` test syntax,
the other `shouldBe`. A trait is like a Python mixin class. [Testing with the testkit](testing-with-the-testkit)
scripts tool calls and failures, and shows MUnit, Weaver and ZIO Test.

## 8. Tooling

The Python setup you know:

```bash
python -m venv .venv
source .venv/bin/activate
pip install openai
export OPENAI_API_KEY=sk-...
python main.py
```

The closest Scala equivalent is [Scala CLI](https://scala-cli.virtuslab.org/): one `.scala` file whose first lines
declare its dependencies (`//> using dep org.llm4s::llm4s-core:...`), run with `scala-cli run main.scala`. No virtual
environment: dependencies are resolved per file and cached. The [Scala CLI quick start](../getting-started/scala-cli)
has a complete one-file program. For the notebook workflow, [Try llm4s in the REPL](../getting-started/repl) covers
the Scala CLI REPL and Metals worksheets; Almond (Jupyter) notebooks are tracked in
[#2296](https://github.com/llm4s/llm4s/issues/2296). A service uses sbt and a `build.sbt` instead
([installation](../getting-started/installation)).

Where Python reads `OPENAI_API_KEY` in `OpenAI()`, LLM4S reads a named provider section from `application.conf`
(`llm4s-openai` binds `OPENAI_API_KEY` for you):

```hocon
llm4s {
  providers {
    provider = "openai-main"

    openai-main {
      provider = "openai"
      model    = "gpt-4o-mini"
    }
  }
}
```

and builds the client every section above takes as `client`:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result

def connect(): Result[LLMClient] =
  for {
    providerConfig  <- Llm4sConfig.defaultProvider()
    registryService <- Llm4sConfig.modelRegistryService()
    given ModelRegistryService = registryService
    client <- LLMConnect.getClient(providerConfig)
  } yield client
```

**New in Scala:** `given` instances
([Scala 3 book: context parameters](https://docs.scala-lang.org/scala3/book/ca-context-parameters.html)). Some
methods take a parameter that is passed for you rather than written at each call. `given ModelRegistryService =
registryService` makes the model registry that value for the lines after it, and `LLMConnect.getClient` picks it up.
[Configuration](../getting-started/configuration) covers other providers and keys.

## Common mistakes coming from Python

{: .warning }
> **Reaching for `var` and mutable collections.** Python names can be rebound and lists changed in place; idiomatic
> Scala uses `val` and immutable `Seq`/`Map`, and returns a new value instead of changing the old one
> ([Scala 3 book: two types of variables](https://docs.scala-lang.org/scala3/book/taste-vars-data-types.html#two-types-of-variables)).
> A `var` is not wrong, but an agent or a stream that shares one across threads is a race.
>
> **Using `null` for "no value".** It compiles, because Scala runs on the JVM, but nothing in LLM4S returns `null`:
> a value that may be missing is an `Option`, and a call that may fail is a `Result`. Match on them, or use
> `getOrElse`, instead of checking for `null` or catching an exception.
>
> **Blocking inside effect code.** `client.complete` and `agent.run` block their thread until the model answers. In
> plain code that is fine. Inside cats-effect `IO` or ZIO, calling them directly is like calling a blocking function
> inside an `async def`: it stalls the runtime's threads. Use the effect module's wrappers (`LLMClientIO`, `LLMClientZ`
> and the others on [Pick your stack](effect-systems)), which run the call on a blocking pool.
>
> **Expecting exceptions.** `try`/`except` around an LLM4S call catches nothing useful: the failure is the `Left` the
> call returned. Handle it where you handle the value.

## Next steps

- [First example](../getting-started/first-example): a complete program, run with sbt.
- [Replacing a Python sidecar](replacing-a-python-sidecar): keep Python models or tools while the LLM logic moves to
  Scala.
- [Comparing LLM4S](comparison): how LLM4S compares with other JVM libraries.
