---
layout: page
title: Media Types
description: "llm4s-media: the MediaType, MediaCategory and MediaSource vocabulary that multimodal messages, image generation and RAG extraction share, and its lookups."
parent: Core
grand_parent: User Guide
nav_order: 108.5
---

# Media types
{: .no_toc }

`llm4s-media` is the vocabulary the multimodal modules share: which format a piece of media is (`MediaType`), what
broad kind that is (`MediaCategory`) and where its bytes are (`MediaSource`).
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## The module

```sbt
libraryDependencies += "org.llm4s" %% "llm4s-media" % llm4sVersion
```

You rarely declare it: `llm4s-core` depends on it, so every module has it. Declare it yourself only when your own
signatures name these types and you depend on nothing else from LLM4S. The package is `org.llm4s.media`, and the
module is Beta ([1.0 scope](../reference/v1-scope)).

It holds data only. It has no dependency, does no I/O and never looks at bytes: deciding what a file *is* from its
content needs Tika, which is in `llm4s-rag`; that code detects a MIME string and asks `MediaCategory.fromMimeType`
which kind it names.

## `MediaType`

A `MediaType` has a `mimeType`, a canonical file `extension` and a `category`. Each is a `case object` of
`MediaType`, under one of three sub-traits, so an API can require the kind it accepts in its signature:

| Trait | Types (`mimeType`, `extension`) | `MediaType.` list |
|---|---|---|
| `ImageMediaType` | `Png` (`image/png`, `png`), `Jpeg` (`image/jpeg`, `jpg`), `Gif` (`image/gif`, `gif`), `WebP` (`image/webp`, `webp`), `Bmp` (`image/bmp`, `bmp`), `Tiff` (`image/tiff`, `tiff`) | `images` |
| `AudioMediaType` | `Wav` (`audio/wav`, `wav`), `Mp3` (`audio/mpeg`, `mp3`), `Flac` (`audio/flac`, `flac`), `Ogg` (`audio/ogg`, `ogg`) | `audio` |
| `DocumentMediaType` | `Pdf` (`application/pdf`, `pdf`), `PlainText` (`text/plain`, `txt`), `Markdown` (`text/markdown`, `md`), `Html` (`text/html`, `html`), `Csv` (`text/csv`, `csv`) | `documents` |

`MediaType.all` is the three lists together. No video type is modelled.

### Lookups

Every lookup returns an `Option`: an unknown name is `None`, never a guess.

| Function | Reads | Notes |
|---|---|---|
| `MediaType.fromExtension(extension)` | A file extension | With or without the leading dot, any case. Also `jpeg`, `tif`, `wave`, `oga`, `markdown` and `htm` |
| `MediaType.fromPath(path)` | The extension of a file path or URL | A URL's query string and fragment are ignored |
| `MediaType.fromMimeType(mimeType)` | A MIME string | Any case, parameters such as `; charset=utf-8` ignored. Also `image/jpg`, `audio/x-wav`, `audio/mp3`, `audio/x-flac` and `text/x-markdown` |
| `MediaType.imageFromExtension`, `imageFromPath`, `imageFromMimeType` | The same | `Some` only for an `ImageMediaType` |

```scala
import org.llm4s.media.{ MediaCategory, MediaType }

MediaType.fromPath("https://example.com/q3.PNG?size=large") // Some(MediaType.Png)
MediaType.fromExtension(".jpeg")                             // Some(MediaType.Jpeg)
MediaType.fromMimeType("text/markdown; charset=utf-8")       // Some(MediaType.Markdown)
MediaType.imageFromPath("report.pdf")                        // None: a document, not an image
MediaType.fromMimeType("video/mp4")                          // None: no video type is modelled
MediaCategory.fromMimeType("video/mp4")                      // Some(MediaCategory.Video)
```

## `MediaCategory`

The part of a MIME type before the slash: `MediaCategory.Image`, `Audio`, `Video`, `Text` and `Application`, each
with its `name` (`"image"`, ...), and `MediaCategory.all`. It is what code branches on to decide how to decode a
payload, or whether to accept it at all.

`MediaCategory.fromMimeType(mimeType)` gives the category of any MIME string, including one `MediaType` does not
model (`video/mp4` above). It is `None` for a top-level type outside the five and for a string with no subtype, such
as `"image"`.

A `MediaType`'s `category` is not always its trait: `Pdf` is a `DocumentMediaType` whose category is `Application`,
and the other document types have the category `Text`.

## `MediaSource`

Where the bytes of a piece of media are:

| Source | Meaning |
|---|---|
| `MediaSource.Url(uri)` | At a `java.net.URI` that the consumer, usually a provider, fetches |
| `MediaSource.Inline(bytes)` | The bytes themselves, an `IArray[Byte]`. Two are equal when their bytes are, and `toString` prints the length, never the bytes |
| `MediaSource.ProviderFile(provider, id)` | A file already uploaded to the provider with that id (`openai`, `gemini`) |

A source is data too: nothing in `llm4s-media` fetches or checks it. `ContentPart.image` and `ContentPart.document`
in `llm4s-core` check a source when they build a part from it, and return a `Result`
([Multimodal content](multimodal#content-parts)).

## Where the types are used

- **[Multimodal content](multimodal)**: `ContentPart.image(source, mediaType)` takes an `ImageMediaType` and
  `ContentPart.document(source, mediaType)` a `DocumentMediaType`, each with a `MediaSource`.
- **[Image Generation](image-generation)**: the `format` option is an `ImageMediaType`.
- **RAG extraction** (`llm4s-rag`): `MediaExtractor.extractAny` detects a file's MIME type from its content and
  branches on `MediaCategory.fromMimeType`: an image is decoded, and text, application and uncategorised types go
  to the document extractor.

These types replaced three overlapping image-format enumerations that `llm4s-core` used to carry; the
[migration guide](../reference/migration#slice-3-llm4s-media) has the mapping.
