---
layout: page
title: 1.0 launch plan
description: "How LLM4S 1.0 is announced beyond the Scala community: Show HN, r/java and r/Kotlin, JVM newsletters, InfoQ and cross-posts, with dates, owners and drafts."
search_title: "LLM4S 1.0 launch plan for the wider JVM community"
nav_exclude: true
---

# 1.0 launch plan
{: .no_toc }

LLM4S 1.0 ([#1281](https://github.com/llm4s/llm4s/issues/1281)) is the release the wider JVM world is most likely to
notice, and it happens once. This page plans how it is announced to Java and Kotlin developers, who find libraries on
Hacker News, Reddit, JVM newsletters and InfoQ rather than in Scala channels
([#2323](https://github.com/llm4s/llm4s/issues/2323)). Scala-community listings and newsletters are planned in
[#2302](https://github.com/llm4s/llm4s/issues/2302); conference talks in
[#2324](https://github.com/llm4s/llm4s/issues/2324). Nothing is paid for.

1. TOC
{:toc}

---

## Rules for everyone who posts

- Post only from a maintainer's own account or the Foundation's accounts, under your own name, and say that you
  maintain LLM4S.
- Never ask anyone to upvote, star, comment or share a post, in public or in private, and never post from a second
  account. Hacker News and Reddit both treat this as vote manipulation.
- Read the channel's rules (linked in the [channel table](#channels)) again in the week you post: they change, and the
  summaries below are only as current as the date next to them.
- Post each channel once. Do not repost a removed or ignored post; ask the moderators or editors instead.
- Stay in the thread for the first day and answer every question, including critical ones, factually. Say plainly
  what LLM4S does not do.
- Every claim in a post must be checkable in the repository or on llm4s.org. The [press kit]({{ '/press/' | relative_url }})
  holds the checked descriptions and fact sheet.

## Dates

`T` is the day the `v1.0.0` tag is pushed and the release is on Maven Central. The 1.0 date is not set yet (#1281);
when it is, the maintainers write the calendar dates into this table. The posts are spread over two weeks so they read
as separate news items, not as a campaign. Weekday posts go out between 14:00 and 16:00 UTC (morning in the US,
afternoon in Europe).

| When | What | Who |
|------|------|-----|
| T − 6 weeks | This plan merged, owners confirmed per channel (the issue asks for at least four weeks) | Maintainers |
| T − 4 weeks | Press kit complete: SVG logos (light and dark), wordmark, screenshots, a 60-second demo clip | Kannupriya Kalra |
| T − 3 weeks | InfoQ news pitch sent (news editors plan ahead); foojay.io article drafted | Proposed owners below |
| T − 2 weeks | Every draft below reviewed against the release; links point at the 1.0 docs | Maintainers |
| T − 7 days at most | The [try-it-now path](#try-it-now) run on a clean machine; date and OS recorded in #2323 | One maintainer |
| T − 3 days | Modmail to r/java and r/Kotlin asking whether a release post is welcome | Reddit owner |
| T | Tag, Maven Central, GitHub release, release post on [News]({{ '/news/' | relative_url }}); the Scala channels of #2302 | Release manager |
| T + 1 or 2 (Tue–Thu) | Show HN | Show HN owner |
| T + 3 | r/java | Reddit owner |
| T + 3 | Newsletter submissions: Java Annotated Monthly, Baeldung Java Weekly, JVM Weekly, Kotlin Weekly | Newsletter owner |
| T + 5 | r/Kotlin | Reddit owner |
| T + 7 | Dev.to and Hashnode cross-posts of the release post, `canonical_url` on llm4s.org | Kannupriya Kalra |
| T + 7 to T + 10 | foojay.io article published (editors publish on a weekly schedule) | foojay owner |
| T + 14 | [Follow-up](#follow-up) recorded in #2323 | Kannupriya Kalra |

If 1.0 slips, every row moves with `T`. If Show HN falls on a Friday to Monday, move it to the next Tuesday.

## Before launch

Each item must be done before `T − 7 days`, or the post that needs it waits.

| Needed | Issue | Status on 2026-11-08 |
|--------|-------|-----------------------|
| A release post per release on llm4s.org, and an Atom feed | [#2319](https://github.com/llm4s/llm4s/issues/2319) | Done; `scripts/new-release-post.sh` drafts the 1.0 post from the CHANGELOG |
| Share cards and structured data, so links preview well | [#2316](https://github.com/llm4s/llm4s/issues/2316) | Done |
| A README that works as a landing page | [#2322](https://github.com/llm4s/llm4s/issues/2322) | Done |
| A no-key way to try LLM4S | [#2286](https://github.com/llm4s/llm4s/issues/2286) | The dev container and [Playground]({{ '/getting-started/playground' | relative_url }}) page are in; CI runs the page's commands in it |
| A trademark and name-use policy the press kit can point to | [#2310](https://github.com/llm4s/llm4s/issues/2310) | Open |
| The press kit | #2323 | [Page]({{ '/press/' | relative_url }}) is up with descriptions and fact sheet; SVG logos, wordmark, screenshots and clip still to make |
| `llm4s-kotlin-api` on Maven Central | — | First published with 0.5.2; check it resolves before the r/Kotlin post |

## Channels

Rules were read on 2026-11-08 where the page could be reached; "not read" means the owner must read the linked page
before posting. Owners are proposed here and confirmed by the maintainers when this plan is merged.

| Channel | Rules | Rules in short | Angle | Proposed owner |
|---------|-------|----------------|-------|----------------|
| Show HN | [Show HN](https://news.ycombinator.com/showhn.html), [guidelines](https://news.ycombinator.com/newsguidelines.html) | Read 2026-11-08: something people can try now, made by the poster, who stays to answer; no sign-up wall; title starts `Show HN:`; no asking friends to vote or comment | A runnable, no-key demo: the Codespace playground | Rory Graves (the project's creator) |
| r/java | [rules](https://www.reddit.com/r/java/about/rules) | Not read (Reddit blocked automated reading). Reported elsewhere: project promotion needs moderator permission; help and tutorials go to r/javahelp and r/learnjava | Java teams: one dependency, typed errors, a provider interface, JBang and Spring Boot | Kannupriya Kalra |
| r/Kotlin | [rules](https://www.reddit.com/r/Kotlin/about/rules) | Not read | The coroutine API and the tool and agent DSL | Kannupriya Kalra |
| r/scala | [rules](https://www.reddit.com/r/scala/about/rules) | Not read | Posted once, by #2302's owner, as the 1.0 release post; not posted again here | #2302's owner |
| Java Annotated Monthly (JetBrains) | [past issues](https://blog.jetbrains.com/idea/tag/java-annotated/) | Curated by JetBrains; no public submission form found | The 1.0 release post | Kannupriya Kalra |
| Baeldung Java Weekly | [past issues](https://www.baeldung.com/java-weekly) | Curated; no public submission form found | The 1.0 release post or the foojay article | Kannupriya Kalra |
| JVM Weekly | [site](https://www.jvm-weekly.com/) | Curated; not read | The 1.0 release post | Kannupriya Kalra |
| Kotlin Weekly | [site](https://kotlinweekly.net/) | Has a "Submit" section that takes links by email; sponsored links are separate (not used) | The Kotlin API, through the Java guide's Kotlin section | Kannupriya Kalra |
| foojay.io | [how to submit](https://foojay.io/today/how-to-submit-your-next-article-on-foojay-io/) | Join the Foojay Slack, ask in `#foojay-site-content` for an account, write in Markdown or AsciiDoc; editors review and publish weekly | An article: calling LLMs from Java with typed errors | Rory Graves |
| InfoQ Java news | [Java topic](https://www.infoq.com/java/) | News is written by InfoQ's Java queue editors; a news tip goes to them, articles go through InfoQ's submission form. The exact route is to be confirmed | News: a JVM LLM framework reaches 1.0, with a maintainer quote | Kannupriya Kalra |
| Dev.to | [editor guide](https://dev.to/p/editor_guide) | `canonical_url` in the front matter points at the original | The release post, unchanged, canonical on llm4s.org | Kannupriya Kalra |
| Hashnode | Its editor's "originally published at" setting | Sets the canonical URL | As Dev.to | Kannupriya Kalra |

r/programming and r/MachineLearning are left out: both are large and remove most project announcements, and one
Show HN reaches the same readers. DZone is left out too unless an editor asks for the foojay article.

## Drafts

Each draft is reviewed in the `T − 2 weeks` row. Text in `[brackets]` is filled in at release. Never invent a quote:
the maintainer named writes their own.

### Show HN

Title (80 characters at most):

> Show HN: LLM4S – a typed LLM and agent library for the JVM (Scala, Java, Kotlin)

Text:

> Hi HN, I'm one of the maintainers of LLM4S, an MIT-licensed library for building LLM applications on the JVM. 1.0
> came out [date].
>
> You can try it without installing anything or having an API key: open the repository in a GitHub Codespace and the
> samples run against a small local model (qwen2.5:0.5b in Ollama):
> https://llm4s.org/getting-started/playground
>
> What it does: one client for OpenAI, Anthropic, Gemini, Bedrock, Ollama and any OpenAI-compatible endpoint; an
> agent runtime with tools, guardrails, handoffs and threads that can be checkpointed to SQLite or Postgres and
> resumed; RAG; an MCP client and server; tracing to Langfuse or OpenTelemetry.
>
> Things we did differently: errors are values (`Either`), not exceptions; each provider is a separate module found
> through a documented interface, so the core has no vendor SDK; [binary compatibility is checked against the 1.0
> baseline from here on].
>
> Limits: it is written in Scala 3 and needs JDK 21. Java and Kotlin use it through facades, which are newer than the
> Scala API. Some providers are marked beta.
>
> Code: https://github.com/llm4s/llm4s. I'd like to hear where it gets in your way.

### r/java

Send the moderators a short modmail first (`T − 3 days`), with the link to the release post, and post only if they
agree. Title:

> LLM4S 1.0: an LLM and agent library for the JVM with a Java API, typed errors and a provider interface

Text:

> I maintain LLM4S, an MIT-licensed LLM library. It is written in Scala and has a Java API (`llm4s-java-api`, on
> Maven Central since 0.5.0), a Spring Boot starter and Reactor support; 1.0 came out [date].
>
> The quickest Java start is one JBang file against a local Ollama model, no API key needed:
> https://llm4s.org/getting-started/jbang. The Java guide is at https://llm4s.org/guide/java.
>
> Every call returns an `LlmResult` you check instead of an exception you catch; `CompletableFuture` variants run on virtual
> threads; each provider (OpenAI, Anthropic, Gemini, Bedrock, Ollama, OpenAI-compatible endpoints) is a module you add.
>
> It needs JDK 21. I'm here to answer questions, including whether a Scala-built library is a risk for a Java team.

### r/Kotlin

Title:

> LLM4S 1.0: an LLM and agent library for the JVM with a Kotlin coroutine API and DSL

Text:

> I maintain LLM4S, an MIT-licensed LLM library for the JVM; 1.0 came out [date]. `llm4s-kotlin-api` adds a Kotlin
> layer on the Java API: client blocks (`Llm4s.openAi { ... }`, `ollama`, `anthropic`, ...), a `tool { ... }` DSL with
> typed parameters and `suspend` handlers, an `agent(id, client) { ... }` block that returns a `Result`, and streaming as
> a `Flow`. Cancelling the coroutine cancels a running tool handler.
>
> Guide: https://llm4s.org/guide/java#the-kotlin-dsl. Built with Kotlin 2.0.21 for JVM 21. Feedback on the DSL is very
> welcome; it is the newest part of the library.

### Newsletters

One message per editor, sent by the owner, nothing else attached:

> LLM4S 1.0, an MIT-licensed LLM and agent library for the JVM (Scala, with Java and Kotlin APIs), came out on [date].
> Release post: https://llm4s.org/news/[post]. A no-key way to try it: https://llm4s.org/getting-started/playground.
> Press kit, with logo and descriptions: https://llm4s.org/press/. Happy to answer questions. [Name], LLM4S maintainer.

### InfoQ news pitch

> Subject: News tip: LLM4S 1.0, an open-source LLM framework for the JVM
>
> LLM4S, an MIT-licensed framework for building LLM applications on the JVM, released 1.0 on [date], after [N]
> releases since April 2025. The news angle for Java readers: [the 1.0 stability guarantee and what it covers], a
> Java API and Spring Boot starter, a provider interface that keeps vendor SDKs out of the core, and errors returned as
> values.
>
> Release post: [link]. Press kit and fact sheet: https://llm4s.org/press/. A maintainer is available for written
> questions; quote: [the maintainer's own words].

### foojay.io article (outline)

"Calling LLMs from Java without exceptions: LLM4S 1.0". One JBang file against Ollama; adding a tool; switching to a
hosted provider by changing configuration only; streaming with Reactor; what 1.0 promises about compatibility; where to
go next. Every code block taken from the docs pages, which are compiled in CI.

## Try it now

The link every post leads with is the [Playground]({{ '/getting-started/playground' | relative_url }}): a Codespace,
no install, no API key. The JBang and Scala CLI quick starts with Ollama are the fallback for readers who will not use
Codespaces.

Within a week of `T`, one maintainer runs, on a machine or account that has never built LLM4S:

1. Open the Playground link while signed in to a GitHub account with no LLM4S Codespace, and run the page's five
   commands. (CI's `devcontainer.yml` workflow runs the same commands; this run checks what a stranger sees, including
   the time to the first answer.)
2. On a clean machine with only JDK 21, JBang and Ollama installed, run the
   [JBang quick start]({{ '/getting-started/jbang' | relative_url }}) with the 1.0 version.

Record in #2323: the date, the OS and version, the time to the first answer, and anything that did not work.

## Follow-up

At `T + 14`, the owner adds to #2323:

| Channel | Link | Date | Outcome (published, removed, no reply) | Comments | Notes |
|---------|------|------|----------------------------------------|----------|-------|
| Show HN | | | | | |
| … | | | | | |

and a short summary: GitHub stars and Maven Central downloads before and after (for example from
[LFX Insights](https://insights.linuxfoundation.org/) and the Maven Central publisher statistics), traffic to
llm4s.org from each channel if the site has analytics by then, and every question asked more than once, each turned
into a docs issue. Every question in every thread is answered before the follow-up is written.
