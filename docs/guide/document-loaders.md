---
layout: page
title: Document Loaders
description: "Load documents into LLM4S RAG from text, files, directories, URLs, a web crawl or S3, combine loaders, and keep an index in step with ingest, sync and refresh."
search_title: "Document loaders for Scala RAG: files, URLs, web crawler, S3"
parent: RAG and Data
nav_order: 303
grand_parent: User Guide
---

# Document Loaders
{: .no_toc }

Get documents from files, web pages and S3 into a RAG pipeline, and keep the index in step with them.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

`llm4s-rag` reads documents through one trait, `DocumentLoader`, in `org.llm4s.rag.loader`. A
loader says where documents come from; `RAG` decides what to do with them: chunk, embed and store
them (`ingest`), update only what changed (`sync`), or rebuild the index (`refresh`).

| Loader | Reads | Package |
|--------|-------|---------|
| `TextLoader` | Strings or `Document`s you already hold | `org.llm4s.rag.loader` |
| `FileLoader` | One file, extracted with Tika (text, Markdown, HTML, PDF, DOCX, ...) | `org.llm4s.rag.loader` |
| `DirectoryLoader` | Every matching file under a directory | `org.llm4s.rag.loader` |
| `UrlLoader` | A list of HTTP(S) URLs | `org.llm4s.rag.loader` |
| `WebCrawlerLoader` | Pages reached by following links from seed URLs | `org.llm4s.rag.loader` |
| `S3Loader` | Objects in an S3 bucket (or LocalStack, MinIO) | `org.llm4s.rag.loader.s3` |

The samples in
[`modules/samples/src/main/scala/org/llm4s/samples/rag/`](https://github.com/llm4s/llm4s/tree/main/modules/samples/src/main/scala/org/llm4s/samples/rag)
show each of them running: `DocumentLoaderExample`, `WebCrawlerExample` and `S3LoaderExample`.

---

## The DocumentLoader contract

```scala
import org.llm4s.rag.loader.LoadResult

trait DocumentLoader {
  def load(): Iterator[LoadResult]
  def estimatedCount: Option[Int] = None
  def description: String
  def ++(other: DocumentLoader): DocumentLoader
}
```

`load()` returns an iterator, so a large source is streamed one document at a time, and it yields
one `LoadResult` per document rather than failing as a whole, so one unreadable file does not stop
the rest from loading.

### Document

A loaded document is a `Document`:

| Field | Meaning |
|-------|---------|
| `id` | Stable identifier. `sync` matches documents across runs by it: `FileLoader` uses the absolute path, `UrlLoader` the URL. |
| `content` | The extracted text |
| `metadata` | `Map[String, String]`: `source`, `path`, `url`, `content_type`, ... depending on the loader, plus your own |
| `hints` | Optional `DocumentHints`: a suggested chunking strategy and config, batch size, priority, a skip reason |
| `version` | Optional `DocumentVersion`: SHA-256 `contentHash`, plus a `timestamp` and an HTTP `etag` where the source has them |

`DocumentHints` has presets for common content: `DocumentHints.markdown`, `DocumentHints.prose`,
`DocumentHints.code`, `DocumentHints.rateLimited(batchSize)` and `DocumentHints.skip(reason)`.
`FileLoader` picks one from the file extension (Markdown, code, otherwise prose).

### LoadResult

| Result | Meaning |
|--------|---------|
| `LoadResult.Success(document)` | The document was loaded. |
| `LoadResult.Failure(source, error, recoverable, documentId)` | One document could not be read: a missing file, a failed extraction, an HTTP error. |
| `LoadResult.Skipped(source, reason)` | The loader chose not to load it: blocked by robots.txt, a content type the crawler does not accept, a page with no text. |
| `LoadResult.ListingFailure(source, error)` | The loader could not enumerate its documents at all: a directory that does not exist, an S3 listing with no credentials or a missing bucket. |

**Why a failure names its document.** `source` is a path or a URL, not necessarily the `id` the
document is indexed under, so a `Failure` can also carry `documentId`. `sync` uses it to tell
"this document is still there but could not be read this time" from "this document is gone":

- A `Failure` with a `documentId` keeps that document's indexed version - it is not deleted and
  not counted as changed. The next sync that reads it picks up any change. `FileLoader` sets it
  when a file exists but cannot be extracted; `UrlLoader` sets it for every failed URL (the URL is
  the id); the S3 loader sets it when an object's read or extraction fails.
- A `Failure` without a `documentId` could be about any document, so `sync` cannot tell which
  unlisted documents are really gone: it skips its deletion pass for that run and logs a warning.
  Adds and updates still apply.
- A `ListingFailure` is not one failed document among successes: the set of documents is unknown.
  `ingest`, `sync` and `refresh` return its error as a `Left`, `sync` deletes nothing, and
  `refresh` leaves the index as it was.

---

## Loaders

### TextLoader

For content you already have in memory: database rows, API responses, test fixtures.

```scala
import org.llm4s.rag.loader.*

val fromPairs = TextLoader.fromPairs(
  "doc-1" -> "Scala is a programming language that combines OOP and FP.",
  "doc-2" -> "LLM4S is a framework for building LLM-powered applications in Scala."
)

val built = TextLoader
  .builder()
  .add("faq-1", "How do I install it? Add the sbt dependency.", Map("section" -> "faq"))
  .add(Document("faq-2", "Which JDK? 21."))
  .build()
```

`TextLoader("content", "id")`, `TextLoader.fromMap(...)` and `TextLoader.fromContents(...)` (random
ids) are also available. Every document gets a content-hash version, so `sync` works with it too.
Use stable ids if you intend to `sync`: a random id makes every run look like new documents.

### FileLoader

One file, extracted with Apache Tika, so it reads plain text, Markdown, HTML, JSON, XML, PDF and
Word documents.

```scala
import org.llm4s.rag.loader.FileLoader

val readme = FileLoader("./README.md", Map("project" -> "llm4s"))
```

The document's id is the file's absolute path, its version is the content hash plus the file's
last-modified time, and its metadata holds `source` (the file name), `path`, `extension`,
`lastModified` and `size`, merged over yours. A missing path or a directory gives a `Failure` with
no `documentId`; a file that exists but cannot be extracted gives a `Failure` naming it.

### DirectoryLoader

Every file under a directory whose name ends in one of `extensions`, each loaded with `FileLoader`.

```scala
import org.llm4s.rag.loader.DirectoryLoader

val docs = DirectoryLoader("./docs")
  .withExtensions(Set("md", "pdf"))
  .withRecursive(true)
  .withMaxDepth(3)
  .withMetadata(Map("collection" -> "handbook"))

val markdownOnly = DirectoryLoader.markdown("./docs")
```

| Option | Default |
|--------|---------|
| `extensions` | `DirectoryLoader.defaultExtensions`: `txt`, `md`, `markdown`, `pdf`, `docx`, `json`, `xml`, `html`, `htm`, `rst` |
| `recursive` | `true` |
| `maxDepth` | `10` (`0` is the directory itself only) |
| `metadata` | empty; each document also gets `directory` (the directory's name) |
| `confineToRoot` | `true`: a symbolic link whose real path is outside the directory is skipped (`withConfineToRoot(false)` follows it) |

`DirectoryLoader.markdown`, `.code`, `.text`, `.pdf` and `.docx` preset the extensions. A
directory that does not exist, or a path that is not a directory, is a `ListingFailure`. Only regular files are
read: a FIFO, device or socket is skipped, even when a link points at it. A link to a file or directory inside the
root is followed, and a directory reached twice through links is listed once.

### UrlLoader

Fetches each URL once, checked by its [`UrlPolicy`](#ssrf-protection) and connected to the address the policy
approved.

```scala
import org.llm4s.rag.loader.UrlLoader
import org.llm4s.security.Secret
import scala.concurrent.duration.*

val pages = UrlLoader(
  "https://example.com/guide.md",
  "https://example.com/faq.html"
).withTimeout(10.seconds)
  .withHeaders(Map("Accept-Language" -> "en"))

val privateDocs = UrlLoader.withAuth(Seq("https://docs.example.com/internal.md"), Secret("token"))
```

| Option | Default |
|--------|---------|
| `headers` | none; `User-Agent` is `LLM4S-RAG/1.0` unless you set it (`UrlLoader.withUserAgent`) |
| `timeout` | 30 seconds, for connecting and for reading |
| `metadata` | empty; each document also gets `source`, `url`, `content_type`, and `etag` / `lastModified` when the server sends them |
| `retryCount` | `2`; stored on the loader (`withRetries`), but `load()` does not currently retry - a failed URL is a `Failure` for that run |
| `urlPolicy` | `UrlPolicy.default`: public addresses only; see [SSRF protection](#ssrf-protection) (`withUrlPolicy`) |

The document's id is the URL. Its version is the content hash with the response's `ETag` and
`Last-Modified`, when present. Up to 5 redirects are followed, each checked as described in
[SSRF protection](#ssrf-protection); any status other than 200 after redirects is a `Failure`.
`toString` masks credential-named headers such as `Authorization`, so a loader can be logged.

### WebCrawlerLoader

Starts from seed URLs and follows links breadth-first, converting each HTML page to text.

```scala
import org.llm4s.rag.loader.{ CrawlerConfig, WebCrawlerLoader }
import scala.concurrent.duration.*

val crawler = WebCrawlerLoader(
  Seq("https://docs.example.com/"),
  CrawlerConfig()
    .withMaxDepth(2)
    .withMaxPages(200)
    .withFollowPatterns("docs.example.com/*")
    .withExcludePatterns("*/archive/*")
    .withDelay(1.second)
)

val onePage = WebCrawlerLoader.singlePage("https://example.com/about")
```

`CrawlerConfig` options:

| Option | Default | Meaning |
|--------|---------|---------|
| `maxDepth` | `3` | Link depth from the seeds; `0` fetches only the seeds |
| `maxPages` | `1000` | Pages to fetch in total |
| `followPatterns` / `excludePatterns` | `*` / none | Glob patterns (`*` wildcards) a URL must match / must not match |
| `respectRobotsTxt` | `true` | See [robots.txt](#robotstxt) |
| `delay` | `500.millis` | Pause between requests; a longer robots.txt `Crawl-delay` wins |
| `timeout` | `30.seconds` | Connect and read timeout |
| `userAgent` | `LLM4S-Crawler/1.0` | Sent with every request, and used to pick robots.txt rules |
| `maxQueueSize` | `10000` | Cap on queued URLs |
| `includeQueryParams` | `false` | Whether URLs that differ only in their query string are different pages |
| `sameDomainOnly` | `true` | Only follow links to the seeds' domains (and their subdomains) |
| `acceptContentTypes` | `text/html`, `application/xhtml+xml` | Other content types are `Skipped` |
| `urlPolicy` | `UrlPolicy.default` | Checks every seed, link, redirect hop and robots.txt; see [SSRF protection](#ssrf-protection) |

Presets: `CrawlerConfig.default`, `CrawlerConfig.polite` (depth 2, 100 pages, 1 second delay),
`CrawlerConfig.fast` (depth 5, 5000 pages, 100 ms delay) and `CrawlerConfig.singlePage`.
`WebCrawlerLoader.forDocs(url)` uses `polite`. `estimatedCount` is `None`: the size of a crawl is
unknown until it ends. A crawled page that fails is a `Failure` without a `documentId`, because the
pages it links to were never seen, so a `sync` with a crawler skips its deletion pass for that run.

### S3Loader

Objects in a bucket, extracted like files. It needs the AWS SDK, which `llm4s-rag` already depends
on.

```scala
import org.llm4s.rag.loader.s3.S3Loader
import org.llm4s.security.Secret

// The AWS default credential chain
val bucketDocs = S3Loader("my-bucket", prefix = "docs/", region = "eu-west-1")

// Explicit keys
val withKeys = S3Loader.withCredentials(
  bucket = "my-bucket",
  accessKeyId = Secret("AKIA..."),
  secretAccessKey = Secret("..."),
  region = "eu-west-1",
  prefix = "docs/"
)

// LocalStack on localhost:4566, credentials test/test
val local = S3Loader.forLocalStack("test-bucket")
```

`credentials` is an `Option[AwsCredentialsProvider]`. When it is not set, the client uses the AWS
SDK's `DefaultCredentialsProvider`: environment variables (`AWS_ACCESS_KEY_ID`,
`AWS_SECRET_ACCESS_KEY`), system properties, the `~/.aws/credentials` file, then an instance or
container role. If none of them yields credentials, or the bucket does not exist, listing fails and
the loader reports a `ListingFailure`, so `sync` deletes nothing.

`region` defaults to `us-east-1`. `extensions` defaults to `S3DocumentSource.defaultExtensions`
(`txt`, `md`, `markdown`, `pdf`, `docx`, `doc`, `json`, `xml`, `html`, `htm`, `csv`, `rst`). For
another endpoint, such as MinIO, configure the source and wrap it:

```scala
import org.llm4s.rag.loader.SourceBackedLoader
import org.llm4s.rag.loader.s3.S3Loader

val minio = SourceBackedLoader(S3Loader.source("my-bucket", "docs/").withEndpoint("http://minio:9000"))
```

Each document's version carries the object's ETag alongside its content hash.

---

## Composing loaders

`++` concatenates two loaders; `DocumentLoaders` has the rest.

```scala
import org.llm4s.rag.loader.*

val handbook = DirectoryLoader.markdown("./handbook")
val faq      = TextLoader.fromPairs("faq-1" -> "Ask in #help.")
val all      = handbook ++ faq ++ UrlLoader("https://example.com/changelog.md")

val tagged  = DocumentLoaders.withMetadata(all, Map("tenant" -> "acme"))
val prose   = DocumentLoaders.withHints(tagged, DocumentHints.prose)
val small   = DocumentLoaders.filter(prose)(_.length < 100000)
val first   = DocumentLoaders.take(small, 50)
val trimmed = DocumentLoaders.map(first)(doc => doc.copy(content = doc.content.trim))
```

| Combinator | Does |
|------------|------|
| `combine(loaders)` | Loads from each loader in turn (what `++` uses) |
| `map(loader)(f)` / `filter(loader)(p)` | Transforms / keeps successfully loaded documents; failures pass through |
| `withMetadata(loader, map)` / `withHints(loader, hints)` | Adds metadata / merges hints into every document |
| `take(loader, n)` / `drop(loader, n)` | Limits / skips the first `n` results |
| `successesOnly(loader)` | Drops `Failure`s and `Skipped`s; keeps a `ListingFailure` |
| `fromDocuments(docs)` / `fromIterator(iter)` / `defer(loader)` / `empty` | Builds a loader from documents, a lazy iterator, or a by-name loader |

`successesOnly` also drops what `sync` needs to keep an unreadable document's indexed version, so
`sync` then deletes such a document as if it were gone from the source. Sync the unwrapped loader
to keep it.

---

## Feeding a loader to RAG

A loader can be passed at build time, with `RAG.builder().withDocuments(loader)`, or to a built
`RAG`:

```scala
import org.llm4s.rag.RAG
import org.llm4s.rag.loader.*
import org.llm4s.types.Result

def index(rag: RAG): Result[Unit] =
  for {
    loaded <- rag.ingest(DirectoryLoader("./docs"))
    _ = println(s"${loaded.successful}/${loaded.totalAttempted} loaded, ${loaded.failed} failed")
    _ = if (loaded.hasErrors) println(loaded.formattedErrors)
    synced <- rag.sync(DirectoryLoader("./docs"))
    _ = println(s"+${synced.added} ~${synced.updated} -${synced.deleted} =${synced.unchanged}")
  } yield ()
```

| Method | Returns | Does |
|--------|---------|------|
| `ingest(loader)` | `Result[LoadStats]` | Chunks, embeds and stores every loaded document |
| `sync(loader)` | `Result[SyncStats]` | Adds new documents, re-ingests changed ones, removes ones the source no longer lists, leaves the rest |
| `refresh(loader)` | `Result[SyncStats]` | Clears the index and re-ingests everything (all counted as `added`) |

Each has an `...Async` form (`ingestAsync`, `syncAsync`, `refreshAsync`) returning a
`Future[Result[...]]`.

**`LoadStats`** reports `totalAttempted`, `successful`, `failed`, `skipped` and `errors` (source and
error for each failure), with `successRate`, `hasErrors` and `formattedErrors`. A failed document is
counted, not returned as a `Left`, unless the RAG was configured with `failOnLoadError(true)`.

**`SyncStats`** reports `added`, `updated`, `deleted` and `unchanged`, with `total`, `changed` and
`hasChanges`.

**How `sync` skips unchanged documents.** `sync` keeps a registry of the version each document id
was indexed at. For each loaded document it takes the loader's `version` (or a content hash, if the
loader set none) and compares its `contentHash` with the registry's: an unknown id is added, a
different hash is re-ingested (its chunks replaced), the same hash is left alone. A timestamp or
ETag change with the same content does not count as a change. After the loader is exhausted, ids
in the registry that the loader did not produce are deleted - unless the run had a `Failure`
without a `documentId` (deletion is skipped) or a `ListingFailure` (sync stops and returns the
error). `sync` streams the loader, holding one document at a time; `refresh` holds every loaded
document in memory before clearing the index, so that a listing failure leaves the index untouched.
For a very large source prefer `sync`.

**Keeping the registry across restarts.** The registry is in memory unless the config names another,
so a new `RAG` remembers nothing: its first `sync` re-embeds every document, and a document deleted
while the process was down stays in the index. For an index that outlives the process, give the
pipeline a registry that does too - and keep both the vector store and the keyword index persistent.
A sync skips a document the registry knows as unchanged, so a store that starts empty (`withPgVector`
alone keeps its keyword index in memory) is never refilled for it:

```scala
import javax.sql.DataSource
import org.llm4s.rag.{ RAG, RAGConfig }
import org.llm4s.rag.loader.{ PgDocumentRegistry, SQLiteDocumentRegistry }
import org.llm4s.security.Secret
import org.llm4s.types.Result

// Over the pool the application already has (doobie's HikariTransactor.kernel, a Play or Slick pool, ...);
// the registry's table is llm4s_document_registry
def pgConfig(dataSource: DataSource, jdbcUrl: String, user: String, password: Secret): Result[RAGConfig] =
  PgDocumentRegistry.fromDataSource(dataSource).map { registry =>
    RAG.builder()
      .withPgHybrid(jdbcUrl, user, password, "vectors", "documents") // vectors and keywords both in Postgres
      .withDocumentRegistry(registry)
  }

// A single process: the registry in a SQLite file beside the SQLite stores
def sqliteConfig: Result[RAGConfig] =
  SQLiteDocumentRegistry("./rag-registry.db").map(RAG.builder().withSQLite("./rag.db").withDocumentRegistry(_))
```

**Several loaders in one `RAG`.** `sync(loader)` treats the loader as the whole source, so syncing a
second loader would delete the first one's documents. Give each loader a stable `sourceId`:
`rag.sync(s3Loader, "s3://bucket/docs")`, `rag.sync(wikiLoader, "wiki")`. A sync then deletes only the
documents its own source registered. `refresh` still clears every source.

---

## Safety

### SSRF protection

`UrlLoader` and `WebCrawlerLoader` ask a `UrlPolicy` before every request they make: each URL or seed, each crawled
link, every redirect hop and the crawler's `robots.txt`. The policy returns the addresses the host may be reached at,
and the loader connects to one of those addresses itself, sending the original `Host` header and, for `https`, the
original host name for SNI and the certificate check. The host is not resolved again at connect time, so a name
whose DNS answer changes after the check (DNS rebinding) cannot reach an address the policy refused. Redirects are
followed by the loader, at most five, each checked again: a refused hop fails with "Redirect blocked by SSRF
protection".

`UrlPolicy.default` is the check of `NetworkSecurity.validateUrl` (in `org.llm4s.security`): only `http` and
`https`, and a host that resolves to a non-public address is refused: loopback (`localhost`, `127.0.0.0/8`, `::1`),
private networks (`10/8`, `172.16/12`, `192.168/16`, `fc00::/7`), link-local addresses including the cloud metadata
endpoint `169.254.169.254`, and the other special-purpose ranges. Its setters build other rules:

```scala
import org.llm4s.rag.loader.{ CrawlerConfig, UrlLoader, UrlPolicy, WebCrawlerLoader }

// Only these hosts and their subdomains, on every page and redirect
val docsOnly = UrlPolicy.default.withAllowedHosts(Set("docs.example.com"))

// An intranet crawl: private addresses allowed, a few hosts still refused
val intranet = UrlPolicy.default
  .withAllowPrivateNetworks(true)
  .withBlockedHosts(Set("vault.corp.example"))

val loader  = UrlLoader("https://docs.example.com/guide").withUrlPolicy(docsOnly)
val crawler = WebCrawlerLoader("http://wiki.corp.example/").withUrlPolicy(intranet)
```

| Setter | Effect |
|--------|--------|
| `withAllowedHosts(hosts)` | Only these hosts and their subdomains; empty (the default) allows any public host |
| `withBlockedHosts(hosts)` | Refuse these hosts and their subdomains as well |
| `withAllowPrivateNetworks(true)` | Allow loopback, private and other non-public addresses. The cloud metadata endpoint (`169.254.169.254`, `fd00:ec2::254`, `metadata.google.internal`, `metadata.internal`) stays refused. |
| `withAllowedSchemes(schemes)` | Narrow the schemes, for example to `https` only |
| `withResolver(f)` | Resolve host names with `f` instead of `InetAddress.getAllByName` |

Any other rule is a `UrlPolicy` of your own: one method, `resolve(url: URI): Either[NetworkError, Seq[InetAddress]]`,
returning the addresses the loader may connect to.

The loaders speak HTTP/1.1 over their own socket, so they use no proxy (`http.proxyHost` is ignored) and read each
body as UTF-8. Request headers are sent on every redirect hop, including one to another host the policy allows.

### robots.txt

With `respectRobotsTxt = true` (the default), `WebCrawlerLoader` fetches `/robots.txt` once per
scheme, host and port, caches the parsed rules for an hour, and uses the group for its `userAgent`
(the longest matching `User-agent`, else `*`). A disallowed page is `Skipped` with
"Blocked by robots.txt" and its links are not followed. The longest matching `Allow`/`Disallow`
rule wins, and `Allow` wins a tie; `*` and `$` patterns are supported. The robots.txt request and its redirects
are checked by the crawler's `UrlPolicy` like a page: a refused one is never requested. A robots.txt that is refused,
cannot be fetched, or answers anything other than 200, allows everything. A `Crawl-delay` longer than
`CrawlerConfig.delay` replaces it.

`UrlLoader` does not read robots.txt: it fetches exactly the URLs you give it.

---

## See also

- [Vector Store](vector-store) - the stores and search behind `RAG`
- [Embeddings](embeddings) - choosing the embedding provider `RAG` uses
- [Permission-Based RAG](permission-based-rag) - ingesting with access control
- [Web crawler design note](https://github.com/llm4s/llm4s/blob/main/docs/design/web-crawler-loader.md) - how the crawler was designed
