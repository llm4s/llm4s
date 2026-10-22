# llm4s-media

The shared media vocabulary of LLM4S, in package `org.llm4s.media`: what kind of media something is, and where its
bytes come from. It is a vocabulary and nothing else: no I/O, no content sniffing and no third-party dependencies, so
every module can depend on it without inheriting anything. Content detection (for example Tika) lives in the module
that needs it, such as `llm4s-rag`, and resolves its result through `MediaType.fromMimeType`.

## Installation

Artifact: `org.llm4s::llm4s-media` (`name := "llm4s-media"` in `build.sbt`). See the
[installation guide](../../docs/getting-started/installation.md) for the current version and how to add it. It is
also on the classpath of `llm4s-image`, which depends on it.

## What is in it

- `MediaType` (with `ImageMediaType`, `AudioMediaType` and `DocumentMediaType`): the known formats, each with its
  `mimeType`, `extension` and `category`; `MediaType.fromMimeType` resolves a MIME type string.
- `MediaCategory`: `Image`, `Audio`, `Video`, `Text` and `Application`, with `MediaCategory.fromMimeType`.
- `MediaSource`: where a piece of media comes from (a URL, inline bytes, or a provider-side file).

## Configuration

None. The module has no `reference.conf` and reads no environment variables.

## Tests

```bash
sbt media/test
```

The suites are pure unit tests: nothing needs an API key or a native library, and none is skipped.

## More

- [`llm4s-image`](../image/README.md), which depends on this module for its image formats
