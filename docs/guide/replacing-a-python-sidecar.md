---
layout: page
title: Replacing a Python Sidecar
description: "Move a Python sidecar's LLM logic into Scala with LLM4S: vLLM, TGI and llama.cpp models as OpenAI-compatible sections, Python functions as MCP tools."
search_title: "Replace a Python LLM sidecar with LLM4S in Scala"
parent: User Guide
nav_order: 43
---

# Replacing a Python Sidecar

Many Scala teams that ship an LLM feature do it with a Python service next to the Scala one: the Scala service
handles the request, and the Python sidecar holds the prompts, the tool calls, the retries, the agent loop and the
retrieval. That costs a second language to review, a second deployment to run and an HTTP or gRPC contract that both
sides must keep in step.

LLM4S moves that logic into the Scala service. Some of the Python is worth keeping: a fine-tuned model served by
vLLM, TGI or llama.cpp, a scikit-learn or spaCy function, an internal library. This page shows how to keep calling
those without rewriting them, and without writing a contract by hand:

1. **[A Python-served model](#bridge-1-a-python-served-model)** is an `openai-compatible` provider section: a
   `baseUrl` and a model name, no code.
2. **[A Python-only function](#bridge-2-a-python-function-as-an-agent-tool)** becomes an agent tool through MCP: a few
   lines of the official MCP Python SDK turn it into a server that LLM4S starts as a subprocess. The tool's schema comes
   from the Python type hints.

Then [move responsibilities one at a time](#a-staged-migration), with the sidecar running until nothing is left in it.

## Table of contents

- [What moves where](#what-moves-where)
- [Bridge 1: a Python-served model](#bridge-1-a-python-served-model)
- [Bridge 2: a Python function as an agent tool](#bridge-2-a-python-function-as-an-agent-tool)
- [A staged migration](#a-staged-migration)
- [Or drop the Python model altogether](#or-drop-the-python-model-altogether)
- [Not covered here](#not-covered-here)

## What moves where

| In the sidecar today | In the Scala service with LLM4S |
|----------------------|---------------------------------|
| Prompts and model calls (OpenAI, Anthropic, Gemini, ... SDKs) | `LLMClient` from `llm4s-core` and one provider module per vendor; see [Providers](providers) |
| A model you serve yourself (vLLM, TGI, llama.cpp) | The `openai-compatible` provider in `llm4s-openai-compatible`: [Bridge 1](#bridge-1-a-python-served-model) |
| Tool calling and the agent loop | `ToolBuilder` in `llm4s-core` and `Agent` in `llm4s-agent`; see [Build Your Own Tool](custom-tools) and [Agents](agents/) |
| A Python function the model calls as a tool | An MCP server in Python, called through `llm4s-mcp`: [Bridge 2](#bridge-2-a-python-function-as-an-agent-tool) |
| Retries, timeouts and rate limits | `ReliableClient` in `llm4s-core`; see the [reliability guide](https://github.com/llm4s/llm4s/blob/main/docs/reliability-guide.md) |
| Typed (JSON) replies parsed by Pydantic | `completeStructured` with a `Schema`, or a schema derived from a case class; see [Structured Output](structured-output) |
| Embeddings, chunking, vector search, reranking | `llm4s-rag`; see [Embeddings](embeddings) and [Vector Store](vector-store) |
| Input and output checks | Guardrails in `llm4s-agent`; see [Guardrails](agents/guardrails) |
| Tracing and token cost | `llm4s-observability` (Langfuse) or `llm4s-observability-otel`; see [Monitoring](observability/) |
| Tests with a mocked model | `ScriptedLLMClient` in `llm4s-testkit`; see [Testing with the Testkit](testing-with-the-testkit) |

## Bridge 1: a Python-served model

vLLM, TGI (Hugging Face Text Generation Inference) and llama.cpp's `llama-server` each serve a model behind the OpenAI
`/v1/chat/completions` API. To LLM4S such a server is a named provider section with
`provider = "openai-compatible"` and the server's `baseUrl`. Add the module:

```sbt
libraryDependencies += "org.llm4s" %% "llm4s-openai-compatible" % llm4sVersion
```

Each server below is started with the command in its comment. One `application.conf` can hold all three sections; the
default is the one `provider` names:

```hocon
llm4s {
  providers {
    provider = "python-vllm"

    # vllm serve Qwen/Qwen2.5-7B-Instruct --max-model-len 32768 --enable-auto-tool-choice --tool-call-parser hermes
    python-vllm {
      provider      = "openai-compatible"
      baseUrl       = "http://localhost:8000/v1"
      model         = "Qwen/Qwen2.5-7B-Instruct"   # the --served-model-name, if you set one
      contextWindow = 32768                        # the --max-model-len
    }

    # docker run --gpus all --shm-size 1g -p 8080:80 -v $volume:/data \
    #   ghcr.io/huggingface/text-generation-inference:3.3.5 --model-id $model --max-total-tokens 8192
    python-tgi {
      provider      = "openai-compatible"
      baseUrl       = "http://localhost:8080/v1"
      model         = "tgi"                        # TGI serves one model; its own examples send "tgi"
      contextWindow = 8192                         # the --max-total-tokens
    }

    # llama-server -hf <user>/<model>:<tag> --alias local-model -c 16384 --port 9931
    python-llamacpp {
      provider      = "openai-compatible"
      baseUrl       = "http://localhost:9931/v1"
      model         = "local-model"                # the --alias
      contextWindow = 16384                        # the -c / --ctx-size
    }
  }
}
```

The service then loads a section by name and gets an ordinary `LLMClient`, the same type a hosted provider gives, so
everything built on it (agents, tools, structured output, RAG) does not change when the model moves:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result

val pythonModel: Result[LLMClient] =
  for {
    config   <- Llm4sConfig.provider("python-vllm")
    registry <- Llm4sConfig.modelRegistryService()
    given ModelRegistryService = registry
    client <- LLMConnect.getClient(config)
  } yield client
```

The [OpenAI-compatible endpoints](providers#openai-compatible-endpoints) section of the providers guide lists every
key. What differs between the three servers:

| | vLLM | TGI | llama.cpp `llama-server` |
|---|---|---|---|
| Default port | 8000 | 80 in the container; the command above maps it to 8080 | 9931 in current builds (`--port` sets it) |
| `model` | the `--model` argument, or `--served-model-name` | any name; TGI serves the one model it was started with | the model file's path, or `--alias` |
| `contextWindow` | `--max-model-len` | `--max-total-tokens` | `-c` / `--ctx-size`; 0, the default, takes the model's own |
| Tool calling | needs `--enable-auto-tool-choice` and a `--tool-call-parser` for the model family (`hermes` for Qwen2.5) | on `/v1/chat/completions`, as grammar-constrained generation | through the Jinja chat template (`--jinja`, on by default); some models need `--chat-template-file` |
| Streamed token usage | reported for `stream_options.include_usage` | `stream_options.include_usage` is in its API | `stream_options.include_usage` is accepted |
| API key | none unless started with `--api-key` | none unless started with `--api-key` | none unless started with `--api-key` |

- **Set `contextWindow`.** LLM4S cannot ask these servers for the window, and the model registry does not know a model
  you serve yourself, so without it the provider assumes 8192, deliberately small. Use the length the server was
  started with, which may be less than the model's maximum.
- **Streamed usage.** LLM4S asks for usage on a streamed request with `stream_options.include_usage`, which all three
  accept. A gateway in front of them that rejects unknown fields fails the stream with a 400 or 422 naming
  `stream_options`; set `streamUsage = false` in the section.
- **Tools.** Whether the model calls a tool depends on the server flags and the model's chat template, not on LLM4S.
  The generic provider never sends `tool_choice`, so the server's default applies (`auto` when tools are offered).
- **No key.** Leave `apiKey` out for a server started without one: LLM4S then sends no `Authorization` header. If the
  server was started with `--api-key`, set `apiKey = ${?YOUR_VARIABLE}` in the section.
- **TGI is in maintenance mode.** Hugging Face accepts only minor fixes for it and points new deployments to vLLM,
  SGLang, llama.cpp or MLX. A TGI server keeps working with the section above; for a new one, prefer vLLM or llama.cpp.

The sources for the table: vLLM's [OpenAI-compatible server](https://docs.vllm.ai/en/latest/serving/online_serving/openai_compatible_server/),
[`vllm serve`](https://docs.vllm.ai/en/latest/cli/serve/) and [tool calling](https://docs.vllm.ai/en/latest/features/tool_calling/);
TGI's [README](https://github.com/huggingface/text-generation-inference), [Messages API](https://huggingface.co/docs/text-generation-inference/messages_api),
[guidance](https://huggingface.co/docs/text-generation-inference/basic_tutorials/using_guidance) and
[launcher options](https://huggingface.co/docs/text-generation-inference/reference/launcher); llama.cpp's
[`llama-server` README](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md).

## Bridge 2: a Python function as an agent tool

The model is in Scala now, but a function it should call is in Python: a classifier, a spaCy pipeline, a call into an
internal library. Instead of a sidecar endpoint for it, make it an [MCP](https://modelcontextprotocol.io) server with
the official [MCP Python SDK](https://github.com/modelcontextprotocol/python-sdk), and let LLM4S start it as a
subprocess and talk to it over its stdin and stdout. There is no port, no HTTP route and no request type to keep in
step: the tool's name comes from the function, its description from the docstring, and its JSON schema from the type
hints.

### The Python side

This is `modules/samples/src/main/python/sentiment_tool.py`, a deliberately tiny pure-Python function standing in for
the real one:

```python
import re
from typing import TypedDict

from mcp.server.mcpserver import MCPServer

POSITIVE = {"good", "great", "excellent", "love", "happy", "fast", "helpful", "reliable", "easy"}
NEGATIVE = {"bad", "poor", "terrible", "hate", "slow", "broken", "unhelpful", "unreliable", "hard"}


class Sentiment(TypedDict):
    label: str
    score: float
    positive: int
    negative: int


def score(text: str) -> Sentiment:
    """The plain Python function: no MCP in it, so it stays testable and reusable as it is."""
    words = re.findall(r"[a-z']+", text.lower())
    positive = sum(1 for w in words if w in POSITIVE)
    negative = sum(1 for w in words if w in NEGATIVE)
    total = positive + negative
    value = 0.0 if total == 0 else (positive - negative) / total
    label = "positive" if value > 0 else "negative" if value < 0 else "neutral"
    return {"label": label, "score": round(value, 3), "positive": positive, "negative": negative}


server = MCPServer("sentiment")


@server.tool()
def analyze_sentiment(text: str) -> Sentiment:
    """Classify the sentiment of a piece of English text as positive, negative or neutral."""
    return score(text)


if __name__ == "__main__":
    # stdio is the default transport: MCP messages on stdout, so nothing else may print there.
    server.run()
```

Install the SDK into a virtual environment, from the pinned `requirements.txt` beside the script (Python 3.10 or
later):

```bash
python3 -m venv .venv
.venv/bin/pip install -r modules/samples/src/main/python/requirements.txt
```

- **SDK versions.** The sample pins `mcp==2.3.0`. In version 2 of the SDK the server class is
  `mcp.server.mcpserver.MCPServer`; in 1.x it was `FastMCP`, imported from `mcp.server.fastmcp`, with the same
  `@tool()` decorator and `run()`. Code written for 1.x keeps working if you pin `mcp<2`.
- **Keep stdout for MCP.** Over stdio the server's stdout carries the protocol, so a stray `print` corrupts it. Log to
  stderr: LLM4S logs each line a server writes there, with secrets redacted.
- **Typed results.** A function that returns a `TypedDict`, a Pydantic model or a dataclass gets an output schema, and
  LLM4S hands the tool's structured result to the model as that JSON object. The SDK wraps a plain return type, such
  as `str`, as `{"result": ...}`.

### The Scala side

Add the MCP client and the agent runtime:

```sbt
libraryDependencies ++= Seq(
  "org.llm4s" %% "llm4s-mcp"   % llm4sVersion,
  "org.llm4s" %% "llm4s-agent" % llm4sVersion
)
```

`MCPServerConfig.stdio` names the command that starts the server. An `MCPToolRegistry` starts it, lists its tools and
calls them; it is a `ToolRegistry`, so it goes to an agent like any other:

```scala
import org.llm4s.agent.Agent
import org.llm4s.llmconnect.LLMClient
import org.llm4s.mcp.{ MCPServerConfig, MCPToolRegistry }
import org.llm4s.types.Result

import scala.concurrent.duration._
import scala.util.Using

val sentiment = MCPServerConfig.stdio(
  name = "python-sentiment",
  command = Seq(".venv/bin/python", "python/sentiment_tool.py"),
  timeout = 30.seconds
)

def classify(client: LLMClient, review: String): Result[Option[String]] =
  Using.resource(new MCPToolRegistry(Seq(sentiment))) { tools =>
    for {
      agent  <- Agent.builder("review-analyst", client).withTools(tools).build()
      result <- agent.run(s"What is the sentiment of this review? $review")
    } yield result.answer
  }
```

- **One process per registry.** The registry starts the server when it is created and stops it on `close()`, so in a
  service create it once at startup and close it on shutdown, rather than per request as this short example does.
- **Name the interpreter.** Use the virtual environment's `python` as the command, not whatever `python3` is on the
  `PATH`, so the server runs with the packages you installed.
- **A server that fails to start contributes no tools.** The failure is logged and the registry offers the model only
  the tools it can call. Check at startup that the tools you expect are there, as the sample does.
- **`timeout`** bounds one call, reply included; a slow model inside the Python function needs a longer one.
- **Annotations are untrusted by default.** A server's `readOnlyHint` and similar annotations are ignored unless the
  config sets `trustAnnotations = true`, which is for a server you operate, as this one is.

The [MCP examples](/examples/#mcp-examples) show the client with more servers, and MCP tools mixed with local ones.

### Test it without a model

The agent can be tested with a scripted model from `llm4s-testkit`, against the real Python tool: the script asks for
the tool, and the second reply is the answer the model would give.

```scala
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }

val scripted = ScriptedLLMClient.sequence(
  Reply.toolCall("analyze_sentiment", """{"text": "fast and reliable"}"""),
  Reply.text("The review is positive.")
)
```

After the run, `scripted.calls(1).messages` holds the tool's result as the Python function returned it. See
[Testing with the Testkit](testing-with-the-testkit).

### Run the sample

`org.llm4s.samples.sidecar.PythonToolBridgeExample` puts the pieces together with a scripted model, so it needs no
API key. From the repository root, after installing the SDK as above:

```bash
sbt "samples/runMain org.llm4s.samples.sidecar.PythonToolBridgeExample .venv/bin/python"
```

It prints the tools the Python server offers, then the agent's answer, which quotes what the Python function
returned. Without an argument it runs `python3`. `PythonToolBridgeExampleSpec` runs the same bridge in CI: it starts
the server, lists its tools and runs an agent turn in which the scripted model calls the Python tool and receives its
result.

## A staged migration

Replace the sidecar the way the strangler pattern replaces any service: put the new one in front, then move one
responsibility at a time, each a release you can roll back.

1. **Front the sidecar.** The Scala service takes the LLM requests it used to forward, and forwards them unchanged.
   Nothing changes for users, and you now own the entry point.
2. **Move the model calls.** Call the provider from Scala with a provider section, or with
   [Bridge 1](#bridge-1-a-python-served-model) for a model the sidecar served itself. Compare the replies of both paths
   on the same traffic before switching.
3. **Move the tools.** Rewrite in Scala the tools that are plain HTTP or database calls
   ([Build Your Own Tool](custom-tools)). Keep the Python-only ones and serve them over MCP,
   [Bridge 2](#bridge-2-a-python-function-as-an-agent-tool).
4. **Move the loop.** Run the agent loop, retries, guardrails and memory in LLM4S. The sidecar is now only the MCP
   tool servers and, perhaps, a model server.
5. **Move retrieval.** Index and search with `llm4s-rag`, against the vector store you already run where it has a
   backend ([Vector Store](vector-store)).
6. **Retire the sidecar.** What is left in Python runs as subprocesses of the Scala service or as a model server
   behind a URL, with no contract of your own between them.

## Or drop the Python model altogether

Some sidecars exist only to run a model locally. Before keeping one, check whether the JVM can do it:

- **Ollama** serves open models without Python; see the [Ollama quick start](/getting-started/ollama-quickstart).
- **In-process local models** with no model server at all are
  [#2284](https://github.com/llm4s/llm4s/issues/2284) (an `llm4s-jlama` provider).
- **ONNX embeddings** in process, for the sidecars that only embed text, are
  [#695](https://github.com/llm4s/llm4s/issues/695).

## Not covered here

- **gRPC bridges** to an existing Python service: MCP over stdio needs no service and no generated stubs, so this page
  does not cover them.
- **Python in the JVM process** (Jep, GraalPy) could remove the subprocess; it is not supported today.
- **A user guide for the MCP client and server** is [#1442](https://github.com/llm4s/llm4s/issues/1442).
