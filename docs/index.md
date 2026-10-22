---
layout: landing
title: Home
nav_order: 1
description: "LLM4S - Large Language Models for Scala. Type-safe agents, tools, RAG and multi-provider LLM calls for Scala and the JVM, from the community-led LLM4S Foundation."
permalink: /
---

<section class="lp-hero" aria-labelledby="hero-heading">
  <div class="lp-wrap lp-hero__grid">
    <div class="lp-hero__copy">
      <p class="lp-pill"><a href="{{ '/reference/roadmap' | relative_url }}"><span>v{{ site.data.project.latest_release }}</span> Latest release, on the road to 1.0</a></p>
      <h1 id="hero-heading">Build reliable LLM applications in <em>Scala</em>, on the JVM you already run</h1>
      <p class="lp-lead">LLM4S is an open-source framework for agents, tool calling, RAG and multi-provider LLM calls, with errors as values, typed schemas and production observability. Community-built and stewarded by the LLM4S Foundation.</p>
      <p class="lp-actions">
        <a class="lp-btn lp-btn--primary lp-btn--lg" href="{{ '/getting-started/' | relative_url }}">Get started</a>
        <a class="lp-btn lp-btn--ghost-dark lp-btn--lg" href="{{ '/foundation/' | relative_url }}"><svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path fill="currentColor" d="M12 21s-7.5-4.6-9.6-9.2C.9 8.4 3 4.5 6.7 4.5c2.1 0 3.6 1.1 4.3 2.5h2c.7-1.4 2.2-2.5 4.3-2.5 3.7 0 5.8 3.9 4.3 7.3C19.5 16.4 12 21 12 21z"/></svg> Sponsor the foundation</a>
      </p>
      <p class="lp-hero__meta">MIT licensed · Scala 3 · JDK 21 · <a href="{{ '/why-llm4s/' | relative_url }}">Why LLM4S?</a></p>
    </div>
    <div class="lp-hero__card" aria-label="Add LLM4S to an sbt build">
      <div class="lp-term">
        <div class="lp-term__bar"><span></span><span></span><span></span><b>build.sbt</b></div>
<pre><code><span class="c">// the core, plus one module per provider you call</span>
libraryDependencies ++= Seq(
  <span class="s">"org.llm4s"</span> %% <span class="s">"llm4s-core"</span>   % <span class="s">"{{ site.data.project.latest_release }}"</span>,
  <span class="s">"org.llm4s"</span> %% <span class="s">"llm4s-openai"</span> % <span class="s">"{{ site.data.project.latest_release }}"</span>,
  <span class="s">"org.llm4s"</span> %% <span class="s">"llm4s-agent"</span>  % <span class="s">"{{ site.data.project.latest_release }}"</span>
)</code></pre>
      </div>
      <p class="lp-hero__alt">Also on <a href="{{ '/getting-started/maven' | relative_url }}">Maven</a>, <a href="{{ '/getting-started/gradle' | relative_url }}">Gradle</a> and <a href="{{ '/getting-started/scala-cli' | relative_url }}">Scala CLI</a>, or start from the template: <code class="lp-nowrap">sbt new llm4s/llm4s.g8</code></p>
    </div>
  </div>
</section>

{% include stats-band.html %}

<section class="lp-section" aria-labelledby="why-heading">
  <div class="lp-wrap">
    <header class="lp-section__head">
      <p class="lp-eyebrow">Why LLM4S</p>
      <h2 id="why-heading">Production AI engineering, the way JVM teams already build software</h2>
      <p>Most LLM tooling is Python-first. LLM4S brings agents, tools and retrieval to Scala and the JVM, so they run in the services, data platforms and pipelines you already operate, with no Python sidecar.</p>
    </header>
    <ul class="lp-grid lp-grid--3">
      <li class="lp-card">
        <p class="lp-card__glyph">Result[A]</p>
        <h3>Errors are values</h3>
        <p>Every call returns a <code>Result</code> with typed error classes instead of throwing, so failures from rate limits to bad tool arguments are handled where the compiler can see them.</p>
        <a href="{{ '/guide/error-handling' | relative_url }}">Error handling</a>
      </li>
      <li class="lp-card">
        <p class="lp-card__glyph">derives</p>
        <h3>Typed tools and structured output</h3>
        <p>Describe a tool's arguments or a model's answer as a case class and derive its JSON schema with <code>derives SchemaOf</code>, so the schema, the parser and your code cannot drift apart (Experimental).</p>
        <a href="{{ '/guide/derived-schemas' | relative_url }}">Derived schemas</a>
      </li>
      <li class="lp-card">
        <p class="lp-card__glyph">SPI</p>
        <h3>One API, many providers</h3>
        <p>Sixteen chat providers, from OpenAI, Anthropic, Gemini and Bedrock to local Ollama, behind one small provider SPI. Adding a provider is adding a dependency; switching is a config change.</p>
        <a href="{{ '/guide/providers' | relative_url }}">Providers</a>
      </li>
      <li class="lp-card">
        <p class="lp-card__glyph">Agent</p>
        <h3>Agents built for production</h3>
        <p>Guardrails, including LLM-as-judge, handoffs between specialist agents, streaming events, human review and durable checkpoints in SQLite or PostgreSQL so a run survives a restart.</p>
        <a href="{{ '/guide/agents/' | relative_url }}">Agents</a>
      </li>
      <li class="lp-card">
        <p class="lp-card__glyph">RAG</p>
        <h3>Retrieval and memory</h3>
        <p>Chunking, embeddings, pgvector, Qdrant, SQLite and Elasticsearch stores, hybrid keyword and vector search, reranking, RAG evaluation and agent memory.</p>
        <a href="{{ '/guide/vector-store' | relative_url }}">RAG and vector stores</a>
      </li>
      <li class="lp-card">
        <p class="lp-card__glyph">OTel</p>
        <h3>Observable and governed</h3>
        <p>OpenTelemetry, Langfuse, Prometheus and Micrometer integrations, cost tracking, API-key redaction, a sandboxed workspace for tools, MCP with auth, and a published threat model.</p>
        <a href="{{ '/guide/observability/' | relative_url }}">Observability</a>
      </li>
    </ul>
  </div>
</section>

<section class="lp-section lp-section--tint" aria-labelledby="code-heading">
  <div class="lp-wrap lp-split">
    <div class="lp-split__text">
      <p class="lp-eyebrow">Your types are the contract</p>
      <h2 id="code-heading">From a case class to a checked model answer</h2>
      <p>Write the type once. LLM4S derives the JSON schema the provider enforces, sends it, and reads the answer back into your type, all inside one <code>Result</code>.</p>
      <ul class="lp-checks">
        <li>Provider and model come from <code>application.conf</code>, keys from the environment</li>
        <li>No exceptions to catch: a missing key, a refused request or an unreadable answer is a <code>Left</code></li>
        <li>The same type works as an agent's typed answer or a tool's arguments</li>
      </ul>
      <p class="lp-actions"><a class="lp-btn lp-btn--primary" href="{{ '/getting-started/first-example' | relative_url }}">Write your first program</a> <a class="lp-btn lp-btn--ghost" href="{{ '/examples/' | relative_url }}">Browse examples</a></p>
    </div>
    <div class="lp-split__code" markdown="1">
<div class="lp-codebar" aria-hidden="true"><span></span><span></span><span></span><b>Ticket.scala</b></div>

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.schema.*
import org.llm4s.types.Result
import upickle.default.ReadWriter

case class Ticket(
  @description("One-line summary of the problem") title: String,
  @description("low, medium or high") priority: String
) derives SchemaOf, ReadWriter

val ticket: Result[Ticket] = for {
  providerConfig <- Llm4sConfig.defaultProvider()
  registry       <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registry
  client <- LLMConnect.getClient(providerConfig)
  ticket <- client.completeStructuredOf[Ticket](
    Conversation(Seq(UserMessage("Checkout fails with a 500")))
  )
} yield ticket
```

</div>
  </div>
</section>

<section class="lp-section" aria-labelledby="integrations-heading">
  <div class="lp-wrap">
    <header class="lp-section__head">
      <p class="lp-eyebrow">Fits your stack</p>
      <h2 id="integrations-heading">Works with the JVM ecosystem you already use</h2>
      <p>Modular artifacts: depend on the core and add only the providers and integrations you need, pinned together by one bill of materials.</p>
    </header>
    <div class="lp-chips-block">
      <h3>Model providers</h3>
      <ul class="lp-chips">
        <li>OpenAI</li><li>Anthropic</li><li>Azure OpenAI</li><li>Google Gemini</li><li>Vertex AI</li><li>Amazon Bedrock</li><li>IBM watsonx.ai</li><li>Mistral</li><li>Cohere</li><li>DeepSeek</li><li>xAI</li><li>Z.ai</li><li>OpenRouter</li><li>Requesty</li><li>Ollama</li><li>Any OpenAI-compatible endpoint</li>
      </ul>
      <h3>Languages, frameworks and runtimes</h3>
      <ul class="lp-chips lp-chips--alt">
        <li><a href="{{ '/guide/java' | relative_url }}">Java</a></li><li><a href="https://github.com/llm4s/llm4s/tree/main/modules/kotlin-api">Kotlin</a></li><li><a href="{{ '/guide/spring-boot' | relative_url }}">Spring Boot</a></li><li><a href="{{ '/guide/quarkus' | relative_url }}">Quarkus</a></li><li><a href="{{ '/guide/cats-effect' | relative_url }}">cats-effect</a></li><li><a href="{{ '/guide/zio' | relative_url }}">ZIO</a></li><li><a href="{{ '/guide/pekko' | relative_url }}">Apache Pekko</a></li><li><a href="{{ '/guide/java-reactor' | relative_url }}">Reactor</a></li><li><a href="{{ '/guide/spark' | relative_url }}">Apache Spark</a></li><li><a href="{{ '/getting-started/gradle' | relative_url }}">Gradle</a></li><li><a href="{{ '/getting-started/maven' | relative_url }}">Maven</a></li>
      </ul>
      <h3>Protocols, stores and operations</h3>
      <ul class="lp-chips">
        <li>Model Context Protocol</li><li>OpenTelemetry</li><li>Langfuse</li><li>Prometheus</li><li>Micrometer</li><li>PostgreSQL + pgvector</li><li>Qdrant</li><li>SQLite</li><li>Elasticsearch</li><li>Neo4j</li><li>Docker sandbox</li>
      </ul>
    </div>
  </div>
</section>

<section class="lp-section lp-section--tint" aria-labelledby="usecases-heading">
  <div class="lp-wrap">
    <header class="lp-section__head">
      <p class="lp-eyebrow">Use cases</p>
      <h2 id="usecases-heading">What teams build with it</h2>
    </header>
    <ul class="lp-grid lp-grid--3">
      <li class="lp-card lp-card--flat"><h3>Assistants inside JVM services</h3><p>Support, operations and internal assistants that run in your Spring Boot, Pekko or plain JVM service, with guardrails on what goes in and comes out.</p></li>
      <li class="lp-card lp-card--flat"><h3>Question answering over your documents</h3><p>RAG over manuals, tickets and wikis with hybrid search, reranking, permission-aware retrieval and evaluation you can run in CI.</p></li>
      <li class="lp-card lp-card--flat"><h3>Extraction into typed records</h3><p>Invoices, emails and forms turned into case classes, checked against a schema, inside your existing data pipelines.</p></li>
      <li class="lp-card lp-card--flat"><h3>Agentic workflows</h3><p>Multi-step agents with tools, handoffs and human review, checkpointed so long runs resume after a crash or a deploy.</p></li>
      <li class="lp-card lp-card--flat"><h3>Developer tooling</h3><p>Issue triage, coding agents and MCP servers that expose your systems to any MCP-capable client.</p></li>
      <li class="lp-card lp-card--flat"><h3>Voice and multimodal apps</h3><p>Speech to text, text to speech, image generation and vision, behind the same typed API.</p></li>
    </ul>
  </div>
</section>

<section class="lp-section" aria-labelledby="showcase-heading">
  <div class="lp-wrap">
    <header class="lp-section__head lp-section__head--row">
      <div>
        <p class="lp-eyebrow">Showcase</p>
        <h2 id="showcase-heading">Open-source projects built with LLM4S</h2>
        <p>Public projects from the LLM4S organisation and the community. Listing is not an endorsement.</p>
      </div>
      <p class="lp-actions"><a class="lp-btn lp-btn--ghost" href="{{ '/showcase/' | relative_url }}">See all projects</a> <a class="lp-btn lp-btn--primary" href="https://github.com/llm4s/llm4s/blob/main/ADOPTERS.md">Add your project</a></p>
    </header>
    {% include showcase-grid.html home=true %}
  </div>
</section>

<section class="lp-section lp-learn" aria-labelledby="learn-heading">
  <div class="lp-wrap lp-split lp-split--top">
    <div class="lp-split__text">
      <p class="lp-eyebrow lp-eyebrow--light">Students and early-career engineers</p>
      <h2 id="learn-heading">Become an AI engineer in the open, for free</h2>
      <p>Skip the expensive bootcamp. Learn LLM engineering on a real, production-minded codebase: build agents and RAG pipelines, then contribute code that maintainers review in public. Every merged pull request is part of your public record.</p>
      <p class="lp-actions">
        <a class="lp-btn lp-btn--light" href="{{ '/learn/' | relative_url }}">Follow the learning path</a>
        <a class="lp-btn lp-btn--ghost-dark" href="https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22">Good first issues</a>
      </p>
    </div>
    <ol class="lp-path">
      <li><b>1</b><div><strong>Get started</strong><span>Install LLM4S and make your first call</span></div></li>
      <li><b>2</b><div><strong>Build an agent</strong><span>Tools, guardrails and multi-turn conversations</span></div></li>
      <li><b>3</b><div><strong>Add retrieval</strong><span>Embeddings, vector stores and RAG evaluation</span></div></li>
      <li><b>4</b><div><strong>Connect tools and MCP</strong><span>Typed tools and Model Context Protocol servers</span></div></li>
      <li><b>5</b><div><strong>Contribute</strong><span>Good first issues, weekly Dev Hour, mentorship</span></div></li>
    </ol>
  </div>
</section>

<section class="lp-section" aria-labelledby="programs-heading">
  <div class="lp-wrap">
    <header class="lp-section__head">
      <p class="lp-eyebrow">Programs and mentorship</p>
      <h2 id="programs-heading">Mentored paths into open source</h2>
    </header>
    <ul class="lp-grid lp-grid--4">
      <li class="lp-card">
        <p class="lp-card__kicker">2025</p>
        <h3>Google Summer of Code</h3>
        <p>LLM4S took part in GSoC 2025 under the Scala Center umbrella: four funded interns from the United States, Switzerland and India worked on the project.</p>
        <a href="{{ '/gsoc/2025/' | relative_url }}">Meet the 2025 cohort</a>
      </li>
      <li class="lp-card">
        <p class="lp-card__kicker">2026 tracks</p>
        <h3>LFX, ESoC and GSoC</h3>
        <p>The roadmap lists LFX Mentorship 2026, European Summer of Code 2026 and GSoC 2026 as mentorship tracks, each with a public list of project ideas.</p>
        <a href="{{ '/reference/roadmap#community-mentorship-tracks-2026' | relative_url }}">See the project ideas</a>
      </li>
      <li class="lp-card">
        <p class="lp-card__kicker">Every Sunday</p>
        <h3>LLM4S Dev Hour</h3>
        <p>A weekly mob-programming session on Discord at 09:00 London time, open to everyone, from first contribution to hundredth.</p>
        <a href="{{ '/events/' | relative_url }}">Join the next session</a>
      </li>
      <li class="lp-card">
        <p class="lp-card__kicker">Partners</p>
        <h3>Host an intern</h3>
        <p>Universities and companies can partner with the foundation to fund or co-mentor internships on LLM4S.</p>
        <a href="{{ '/foundation/#partner-on-internships' | relative_url }}">Partner with us</a>
      </li>
    </ul>
  </div>
</section>

<section class="lp-section lp-section--tint" aria-labelledby="events-heading">
  <div class="lp-wrap">
    <header class="lp-section__head lp-section__head--row">
      <div>
        <p class="lp-eyebrow">Events</p>
        <h2 id="events-heading">Upcoming events</h2>
        <p>Meet the LLM4S community at a talk near you, or join the weekly Dev Hour online.</p>
      </div>
      <p class="lp-actions"><a class="lp-btn lp-btn--primary" href="{{ '/events/' | relative_url }}">Explore events</a> <a class="lp-btn lp-btn--ghost" href="https://luma.com/calendar/cal-Zd9BLb5jbZewxLA">Follow the Luma calendar</a></p>
    </header>
    {% include upcoming-events.html %}
  </div>
</section>

<section class="lp-section" aria-labelledby="world-heading">
  <div class="lp-wrap">
    <header class="lp-section__head lp-section__head--row">
      <div>
        <p class="lp-eyebrow">Talks</p>
        <h2 id="world-heading">LLM4S Around the World</h2>
        <p>Snapshots from LLM4S talks at Scala meetups, conferences, developer events and big-tech hackathons. Select a picture to open it full size.</p>
      </div>
      <p class="lp-actions"><a class="lp-btn lp-btn--ghost" href="{{ '/events/' | relative_url }}">See all talks and events</a></p>
    </header>
    {% include talk-snapshots.html strip=true %}
  </div>
</section>

<section class="lp-section lp-section--tint" aria-labelledby="roadmap-heading">
  <div class="lp-wrap">
    <header class="lp-section__head">
      <p class="lp-eyebrow">Roadmap</p>
      <h2 id="roadmap-heading">The road to 1.0</h2>
      <p>1.0 freezes a stable spine with a binary-compatibility promise. The date is intentionally not fixed: the frozen surface ships once it has survived two releases.</p>
    </header>
    <ol class="lp-timeline">
      <li class="is-done"><strong>0.4.x</strong><span>Artifacts renamed to <code>llm4s-*</code></span></li>
      <li class="is-done"><strong>0.5.0</strong><span>Modular artifacts, provider SPI, Java, Spring Boot, cats-effect and ZIO modules</span></li>
      <li class="is-done"><strong>0.5.1</strong><span>Multimodal messages, agent harness SPIs, Agent Skills</span></li>
      <li><strong>Pre-1.0</strong><span>Designs final, MiMa compatibility baseline set</span></li>
      <li><strong>1.0</strong><span>Frozen, binary-compatible surface across 1.x</span></li>
    </ol>
    <p class="lp-actions"><a class="lp-btn lp-btn--ghost" href="{{ '/reference/roadmap' | relative_url }}">Read the roadmap</a> <a class="lp-btn lp-btn--ghost" href="{{ '/reference/v1-scope' | relative_url }}">What 1.0 freezes</a></p>
  </div>
</section>

{% include sponsor-band.html %}

<section class="lp-section" aria-labelledby="community-heading">
  <div class="lp-wrap">
    <header class="lp-section__head">
      <p class="lp-eyebrow">Community</p>
      <h2 id="community-heading">Join the community</h2>
    </header>
    <ul class="lp-grid lp-grid--4">
      <li class="lp-card lp-card--link"><h3><a href="{{ site.discord_invite }}">Discord</a></h3><p>Ask questions, share what you build and meet the maintainers.</p></li>
      <li class="lp-card lp-card--link"><h3><a href="https://github.com/llm4s/llm4s">GitHub</a></h3><p>Star the repository, report issues and open pull requests.</p></li>
      <li class="lp-card lp-card--link"><h3><a href="{{ '/community/' | relative_url }}">Community hub</a></h3><p>Dev Hour times in your time zone, resources and ways to help.</p></li>
      <li class="lp-card lp-card--link"><h3><a href="{{ '/people/' | relative_url }}">People</a></h3><p>The maintainers, contributors and mentors behind LLM4S.</p></li>
    </ul>
    <div class="lp-follow">
      <p>Follow LLM4S</p>
      {% include follow-links.html style="buttons" %}
    </div>
  </div>
</section>

{% include events-script.html %}
