---
layout: landing
title: Why LLM4S
nav_order: 1.5
permalink: /why-llm4s/
description: "Why build LLM applications in Scala and on the JVM, what LLM4S gives a team, how it compares with Python-first stacks and other JVM libraries, and what adopting it involves."
---

<section class="lp-hero lp-hero--page" aria-labelledby="why-hero">
  <div class="lp-wrap">
    <div class="lp-hero__copy">
      <p class="lp-eyebrow lp-eyebrow--light">Why LLM4S</p>
      <h1 id="why-hero">AI features belong in the systems you already <em>trust</em></h1>
      <p class="lp-lead">Your services, data pipelines and operations run on the JVM. LLM4S lets you add agents, tools and retrieval there, in typed Scala, instead of standing up and securing a second stack beside them.</p>
      <p class="lp-actions">
        <a class="lp-btn lp-btn--primary lp-btn--lg" href="{{ '/getting-started/' | relative_url }}">Get started</a>
        <a class="lp-btn lp-btn--ghost-dark lp-btn--lg" href="{{ '/guide/comparison' | relative_url }}">Read the comparison</a>
      </p>
    </div>
  </div>
</section>

<section class="lp-section" aria-labelledby="why-jvm">
  <div class="lp-wrap">
    <header class="lp-section__head">
      <p class="lp-eyebrow">The JVM</p>
      <h2 id="why-jvm">Why build LLM applications on the JVM</h2>
      <p>Most LLM tooling is Python-first, while many production systems in finance, telecom, data platforms and enterprise software are built on Scala and the JVM.</p>
    </header>
    <ul class="lp-grid lp-grid--3">
      <li class="lp-card lp-card--flat"><h3>No Python sidecar</h3><p>The LLM code runs in the same process, build and deployment as the rest of your service: one language to review, one artifact to ship, one runtime to patch.</p></li>
      <li class="lp-card lp-card--flat"><h3>Operations you already have</h3><p>Your logging, metrics, tracing, secrets handling, CI and security review apply unchanged. LLM4S reports through OpenTelemetry, Prometheus and Micrometer.</p></li>
      <li class="lp-card lp-card--flat"><h3>Concurrency that scales</h3><p>LLM calls are slow I/O. On JDK 21, LLM4S runs tools on virtual threads and integrates with cats-effect, ZIO, Pekko Streams and Reactor for streaming and cancellation.</p></li>
    </ul>
  </div>
</section>

<section class="lp-section lp-section--tint" aria-labelledby="why-scala">
  <div class="lp-wrap lp-split lp-split--top">
    <div class="lp-split__text">
      <p class="lp-eyebrow">Scala</p>
      <h2 id="why-scala">Why Scala suits LLM work</h2>
      <p>Model output is untrusted, provider calls fail in many ways, and agents are state machines. Scala's type system makes each of those explicit.</p>
    </div>
    <ul class="lp-checks lp-checks--big">
      <li><strong>Failures as values.</strong> A <code>Result[A]</code> with typed error classes makes rate limits, timeouts, refused requests and unreadable answers part of the signature, not a surprise in production.</li>
      <li><strong>Types as schemas.</strong> Case classes describe what you expect back from a model and what a tool accepts; <code>derives SchemaOf</code> turns them into the JSON schema the provider enforces (Experimental).</li>
      <li><strong>Immutable conversations and agent state.</strong> A run is data you can serialise, checkpoint, replay and test.</li>
      <li><strong>Effect systems if you want them.</strong> <code>AgentIO</code> for cats-effect and <code>AgentZ</code> for ZIO, or plain <code>Result</code> if you do not.</li>
    </ul>
  </div>
</section>

<section class="lp-section" aria-labelledby="why-llm4s-heading">
  <div class="lp-wrap">
    <header class="lp-section__head">
      <p class="lp-eyebrow">What you get</p>
      <h2 id="why-llm4s-heading">What LLM4S gives a team</h2>
    </header>
    <ul class="lp-grid lp-grid--3">
      <li class="lp-card"><p class="lp-card__glyph">providers</p><h3>Multi-provider through one SPI</h3><p>Sixteen chat providers on <code>main</code>: OpenAI, Azure OpenAI, Anthropic, Gemini, Vertex AI, Ollama, Mistral, Cohere, DeepSeek, Z.ai, xAI, OpenRouter, Requesty, Amazon Bedrock, IBM watsonx.ai and any OpenAI-compatible endpoint, plus five embedding providers. Each is a module found on the classpath.</p><a href="{{ '/guide/providers' | relative_url }}">Providers</a></li>
      <li class="lp-card"><p class="lp-card__glyph">agents</p><h3>Agents with guardrails</h3><p>Input and output guardrails, including LLM-as-judge, handoffs, delegation, human review, streaming events and a typed graph runtime with durable checkpoints in SQLite or PostgreSQL.</p><a href="{{ '/guide/agents/' | relative_url }}">Agents</a></li>
      <li class="lp-card"><p class="lp-card__glyph">rag</p><h3>RAG and memory</h3><p>Loaders, chunking, pgvector, Qdrant, SQLite and Elasticsearch stores, hybrid search, reranking, permission-aware retrieval, GraphRAG and RAGAS-style evaluation.</p><a href="{{ '/guide/vector-store' | relative_url }}">Vector stores</a></li>
      <li class="lp-card"><p class="lp-card__glyph">mcp</p><h3>Tools and MCP</h3><p>A tool API with typed tools, built-in tools, and Model Context Protocol client and server with stdio, SSE and Streamable HTTP transports, bearer-token auth and public-bind protection.</p><a href="{{ '/guide/custom-tools' | relative_url }}">Tools</a></li>
      <li class="lp-card"><p class="lp-card__glyph">observability</p><h3>Observability and cost</h3><p>Tracing through OpenTelemetry or Langfuse, metrics through Prometheus or Micrometer, a trace collector, cost tracking and provider exchange logging.</p><a href="{{ '/guide/observability/' | relative_url }}">Observability</a></li>
      <li class="lp-card"><p class="lp-card__glyph">interop</p><h3>Java, Kotlin and frameworks</h3><p>A Java facade, a Kotlin coroutine API (Experimental), a Spring Boot starter, a Quarkus guide and sample, and cats-effect, ZIO, Pekko and Reactor modules.</p><a href="{{ '/guide/java' | relative_url }}">Java guide</a></li>
    </ul>
  </div>
</section>

<section class="lp-section lp-section--tint" aria-labelledby="unique-heading">
  <div class="lp-wrap">
    <header class="lp-section__head">
      <p class="lp-eyebrow">How it differs</p>
      <h2 id="unique-heading">Compared with Python-first stacks and other JVM libraries</h2>
      <p>Facts and trade-offs, not rankings. The full, sourced table is on <a href="{{ '/guide/comparison' | relative_url }}">Comparing LLM4S</a>, last verified 2026-10-11 against LangChain4j, Spring AI, Koog and Embabel.</p>
    </header>
    <div class="lp-grid lp-grid--2">
      <div class="lp-card lp-card--flat">
        <h3>Versus a Python-first stack</h3>
        <ul class="lp-list">
          <li>Runs inside your JVM services: no second runtime, no service boundary to secure between your code and the LLM logic.</li>
          <li>Type-checked request and answer shapes instead of dictionaries.</li>
          <li>The same build, dependency scanning (SBOMs per module) and deployment pipeline as the rest of your code.</li>
        </ul>
      </div>
      <div class="lp-card lp-card--flat">
        <h3>Versus other JVM libraries</h3>
        <ul class="lp-list">
          <li>Written for Scala 3, with errors as <code>Result[A]</code> values; the others are Java or Kotlin libraries.</li>
          <li>A provider is a small published interface, <code>ProviderDescriptor</code>: adding one is adding a dependency.</li>
          <li>Guardrails and handoffs in the same library as the client, usable on an agent run.</li>
          <li><code>AgentIO</code> and <code>AgentZ</code> for cats-effect and ZIO users.</li>
        </ul>
      </div>
    </div>
    <div class="lp-callout">
      <p><strong>Where it is not the right fit yet.</strong> LLM4S is pre-1.0 and Scala 3 only, and needs JDK 21. Its Java facade is incomplete (defining tools, token streaming and structured output from Java are open issues), its Spring Boot starter wires three providers, and LangChain4j and Spring AI each list far more vector stores. If you need those today, the <a href="{{ '/guide/comparison#when-not-to-choose-llm4s' | relative_url }}">comparison</a> says so plainly.</p>
    </div>
  </div>
</section>

<section class="lp-section" aria-labelledby="cases-heading">
  <div class="lp-wrap">
    <header class="lp-section__head">
      <p class="lp-eyebrow">Use cases</p>
      <h2 id="cases-heading">Where it fits</h2>
    </header>
    <ul class="lp-grid lp-grid--3">
      <li class="lp-card lp-card--flat"><h3>Customer and internal assistants</h3><p>Assistants inside existing Spring Boot, Pekko or plain JVM services, with guardrails on input and output and traces in your observability stack.</p></li>
      <li class="lp-card lp-card--flat"><h3>Knowledge search and Q&amp;A</h3><p>RAG over documents, tickets and wikis with hybrid search, reranking and permission-aware retrieval, evaluated in CI.</p></li>
      <li class="lp-card lp-card--flat"><h3>Document and data extraction</h3><p>Turning emails, invoices and reports into typed records inside Spark or streaming pipelines.</p></li>
      <li class="lp-card lp-card--flat"><h3>Long-running agent workflows</h3><p>Multi-step agents with approvals and checkpoints that resume after a restart.</p></li>
      <li class="lp-card lp-card--flat"><h3>Developer and platform tooling</h3><p>Issue triage, code agents and MCP servers that expose internal systems to AI clients.</p></li>
      <li class="lp-card lp-card--flat"><h3>Multimodal products</h3><p>Speech, image generation and vision behind the same typed client.</p></li>
    </ul>
  </div>
</section>

<section class="lp-section lp-section--tint" aria-labelledby="adopt-heading">
  <div class="lp-wrap">
    <header class="lp-section__head">
      <p class="lp-eyebrow">For companies</p>
      <h2 id="adopt-heading">Adopting LLM4S in your organisation</h2>
      <p>What a team evaluating LLM4S for production can check today.</p>
    </header>
    <ul class="lp-grid lp-grid--3">
      <li class="lp-card"><h3>Modular artifacts</h3><p>Depend on <code>llm4s-core</code> and add only the providers and integrations you use. <code>llm4s-bom</code> pins every artifact to one version. The core holds no vendor SDK.</p><a href="{{ '/reference/v1-scope' | relative_url }}">1.0 scope</a></li>
      <li class="lp-card"><h3>A compatibility promise</h3><p>Every package has a tier: Frozen at 1.0, Beta or Experimental. Frozen modules will be binary compatible across 1.x, enforced by MiMa from a pre-1.0 baseline, with deprecation before removal.</p><a href="{{ '/reference/compatibility-policy' | relative_url }}">Compatibility policy</a></li>
      <li class="lp-card"><h3>Security work in the open</h3><p>A published threat model, API-key redaction, a sandboxed workspace for tool execution, MCP auth hardening, a config-policy CI gate, Dependabot and a CycloneDX SBOM for every module.</p><a href="{{ '/reference/security' | relative_url }}">Security reference</a></li>
      <li class="lp-card"><h3>MIT licence</h3><p>Permissive licensing with no field-of-use restrictions, and a DCO sign-off on every contribution.</p><a href="https://github.com/llm4s/llm4s/blob/main/LICENSE">Licence</a></li>
      <li class="lp-card"><h3>Open governance</h3><p>Governance, maintainers and release process are documented in the repository, and the roadmap is public.</p><a href="https://github.com/llm4s/llm4s/blob/main/GOVERNANCE.md">Governance</a></li>
      <li class="lp-card"><h3>Sustain what you depend on</h3><p>If LLM4S matters to your business, sponsoring the foundation funds maintenance, security hardening and release engineering. Tell others you use it in ADOPTERS.md.</p><a href="{{ '/foundation/' | relative_url }}">Support the foundation</a></li>
    </ul>
    <p class="lp-actions"><a class="lp-btn lp-btn--primary" href="{{ '/getting-started/' | relative_url }}">Start a pilot</a> <a class="lp-btn lp-btn--ghost" href="https://github.com/llm4s/llm4s/blob/main/ADOPTERS.md">List your organisation</a> <a class="lp-btn lp-btn--ghost" href="mailto:kannupriya@llm4s.org">Talk to the maintainers</a></p>
  </div>
</section>

{% include sponsor-band.html %}
