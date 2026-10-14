---
layout: page
title: Multimodal content
parent: User Guide
nav_order: 7
---

# Multimodal content: images and documents
{: .no_toc }

Send images and documents to a model in a user message or a tool result, and know before the request goes out
whether the provider can take them.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Content parts

A `UserMessage` and a `ToolMessage` carry `parts: Vector[ContentPart]`. A part is one of:

- `ContentPart.Text(text)`;
- an image, built with `ContentPart.image(source, mediaType)`, `mediaType` an `ImageMediaType` (`Png`, `Jpeg`, `Gif`,
  `WebP`, `Bmp`, `Tiff`);
- a document, built with `ContentPart.document(source, mediaType)`, `mediaType` a `DocumentMediaType` (`Pdf`,
  `PlainText`, `Markdown`, `Html`, `Csv`).

The source, an `org.llm4s.media.MediaSource`, says where the bytes are:

| Source | Meaning |
|---|---|
| `MediaSource.Url(uri)` | The provider fetches it. Must be absolute; a `data:` URL is refused, use `Inline`. |
| `MediaSource.Inline(bytes)` | The bytes themselves, at most `ContentPart.MaxInlineBytes` (5 MiB). |
| `MediaSource.ProviderFile(provider, id)` | A file already uploaded to `provider` (`openai`, `gemini`), by its id. |

```scala
import org.llm4s.llmconnect.model.*
import org.llm4s.media.{ MediaSource, MediaType }
import java.net.URI
import java.nio.file.{ Files, Path }

for {
  chart  <- ContentPart.image(MediaSource.Url(URI.create("https://example.com/q3.png")), MediaType.Png)
  report <- ContentPart.document(MediaSource.Inline(IArray.from(Files.readAllBytes(Path.of("q3.pdf")))), MediaType.Pdf)
  answer <- client.complete(Conversation(Seq(UserMessage(Vector(ContentPart.Text("Summarise these."), chart, report)))))
} yield answer.content
```

`UserMessage(text)` and `ToolMessage(text, toolCallId)` build the one-text-part message, as before. `content` is a
message's text view: its text parts joined by newlines, `""` for a message that is all media.

## Why inline media is bounded

`ContentPart.image` and `ContentPart.document` return `Left(ValidationError)` for inline bytes over 5 MiB (pass
`maxInlineBytes` for another bound). An agent thread's every checkpoint is a full snapshot of its state, so each
retained checkpoint keeps a copy of every inline part - base64-encoded, a third larger - for as long as the message
stays in the thread. Put larger media at a URL, or upload it to the provider and send a `ProviderFile`; both are held
by reference.

## What each provider sends

A client maps every part its API takes and fails the request, before sending anything, with an
`UnsupportedContentPartError` naming the provider, the part (`ContentPart.kind`, e.g. `image (url, image/png)`) and
the role. No part is dropped and none is turned into text. A `ProviderFile` goes only to the provider it names.

| Provider | User message | Tool result |
|---|---|---|
| OpenAI, Azure OpenAI, Requesty | text; image `Url`, `Inline`; PDF `Inline`, own `ProviderFile` | text |
| OpenAI-compatible (generic, Z.ai, OpenRouter, Mistral, Cohere) | text; image `Url`, `Inline` | text |
| OpenRouter, in addition | PDF `Url`, `Inline` | |
| Mistral, in addition | PDF `Url` | |
| DeepSeek | text | text |
| Anthropic | text; image (PNG, JPEG, GIF, WebP) `Url`, `Inline`; PDF `Url`, `Inline`; plain text `Inline` | the same |
| Gemini API, Vertex AI | text; image and document `Inline`, `Url`, own `ProviderFile` | text |
| Ollama | text; image `Inline` | text |
| Bedrock | text; image (PNG, JPEG, GIF, WebP) and document (all five) `Inline` or an `s3://` `Url` | the same |
| watsonx.ai | text; image `Inline` | text |

"Own `ProviderFile`" means a file whose `provider` is the client's own id (`openai`, `azure`, `requesty`, `gemini`,
`vertexai`). Several text parts with no media are sent as one string, joined by newlines, by the OpenAI-compatible
providers and Ollama.

A media type the wire format can name but a model rejects is the provider's error, as it is for text.

## Tools that return media

A tool returns parts with `ToolContent.Parts`:

```scala
import org.llm4s.agent.graph.tool.{ AgentTool, ToolContent, ToolOutcome }

val plot = AgentTool(plotSpec) { (args, _) =>
  ContentPart.image(MediaSource.Inline(IArray.from(render(args))), MediaType.Png) match {
    case Right(image) => ToolOutcome.Success(ToolContent.Parts(Vector(ContentPart.Text("The plot:"), image)))
    case Left(error)  => ToolOutcome.Error(error.message)
  }
}
```

`ToolContent.Json(value)` is the JSON result a tool returned before. The loop puts `Parts` in the call's
`ToolMessage` unchanged. A result with no content, or with more than 5 MiB of inline bytes in total, is the call's
error result instead, so the model is told and the run continues. The live `ToolCallResult` event carries the text
view only.

## Stored conversations

The JSON of a one-text message is unchanged, and JSON written before content parts reads as one text part, so
existing memory stores, saved conversations and agent threads keep working. The semantic cache (`CachingLLMClient`)
embeds a prompt's text, so it passes a conversation with media straight to the model.
