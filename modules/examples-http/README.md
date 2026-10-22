# llm4s-examples-http

Reference apps that serve an llm4s agent over HTTP, each with the same two endpoints: `POST /chat` (the answer as
JSON) and `POST /chat/stream` (the answer as server-sent events). Unpublished: read it and copy what you need.

| App | Main class | Built on |
|---|---|---|
| http4s (Ember) | `org.llm4s.examples.http.http4s.Http4sChatServerMain` | `llm4s-effect` (`AgentIO[IO]`, fs2) |
| ZIO HTTP (Netty) | `org.llm4s.examples.http.ziohttp.ZioChatServerMain` | `llm4s-zio` (`AgentZ`, `ZStream`) |
| Tapir, on http4s (Ember) | `org.llm4s.examples.http.tapir.TapirChatServerMain` | Tapir endpoints (`tapir-http4s-server`) over `AgentIO[IO]`, with OpenAPI docs |

## Run

```bash
sbt "examplesHttp/run --fake"                       # a scripted client: no model, no API key
sbt examplesHttp/run                                # the samples' default: a local Ollama (ollama pull llama3)
LLM4S_PROVIDER=openai-main sbt examplesHttp/run     # with OPENAI_API_KEY set
```

`run` starts the http4s app. The ZIO HTTP and Tapir apps take the same arguments through `runMain`:

```bash
sbt "examplesHttp/runMain org.llm4s.examples.http.ziohttp.ZioChatServerMain --fake"
sbt "examplesHttp/runMain org.llm4s.examples.http.ziohttp.ZioChatServerMain"
sbt "examplesHttp/runMain org.llm4s.examples.http.tapir.TapirChatServerMain --fake"
sbt "examplesHttp/runMain org.llm4s.examples.http.tapir.TapirChatServerMain"
```

The Tapir app also serves its OpenAPI documentation, generated from the endpoint definitions: the Swagger UI at
`http://localhost:8080/docs` and the document at `http://localhost:8080/docs/openapi.yaml`.

`--port N` and `--host H` change the address (default `127.0.0.1:8080`). The provider sections are in
`src/main/resources/application.conf`, loaded through `Llm4sConfig` at startup.

```bash
curl -s localhost:8080/chat -d '{"message": "What is Scala?"}'
curl -N localhost:8080/chat/stream -d '{"message": "What is Scala?"}'
```

## Test

```bash
sbt examplesHttp/test
```

The tests use `ScriptedLLMClient` (`llm4s-testkit`) and a parking fake client: no key, no network beyond loopback.

The guide, with the event format, the error-to-status mapping, how a disconnect cancels the run and the Tapir
endpoint definitions, is
`docs/guide/http-reference-apps.md`.
