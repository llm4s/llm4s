---
layout: landing
title: Learn
nav_order: 9.5
permalink: /learn/
description: "A free path to becoming an AI engineer with LLM4S: from your first LLM call to agents, RAG, tools and MCP, then real open-source contributions with Dev Hour, Discord and mentorship programs."
---

<section class="lp-hero lp-hero--page" aria-labelledby="learn-hero">
  <div class="lp-wrap">
    <div class="lp-hero__copy">
      <p class="lp-eyebrow lp-eyebrow--light">For students and early-career engineers</p>
      <h1 id="learn-hero">Become an AI engineer in the open, <em>for free</em></h1>
      <p class="lp-lead">You do not need an expensive bootcamp to learn LLM engineering. Learn it on a real, production-minded open-source framework: build agents and retrieval pipelines, then contribute code that the maintainers review in public.</p>
      <p class="lp-actions">
        <a class="lp-btn lp-btn--primary lp-btn--lg" href="#the-learning-path">Start the path</a>
        <a class="lp-btn lp-btn--ghost-dark lp-btn--lg" href="{{ site.discord_invite }}">Join the Discord</a>
      </p>
    </div>
  </div>
</section>

<section class="lp-section" aria-labelledby="why-learn">
  <div class="lp-wrap">
    <header class="lp-section__head">
      <p class="lp-eyebrow">Why learn here</p>
      <h2 id="why-learn">Real engineering, not toy exercises</h2>
    </header>
    <ul class="lp-grid lp-grid--4">
      <li class="lp-card lp-card--flat"><h3>It costs nothing</h3><p>The code, the documentation, the weekly Dev Hour and the Discord are free and open to everyone.</p></li>
      <li class="lp-card lp-card--flat"><h3>Real code review</h3><p>Your pull requests are reviewed in public by the project's maintainers, against the same CI, tests and coverage floors as everyone else's.</p></li>
      <li class="lp-card lp-card--flat"><h3>A public track record</h3><p>Every merged pull request and every discussion is public, so you can point to the work you did.</p></li>
      <li class="lp-card lp-card--flat"><h3>Skills that transfer</h3><p>LLM APIs, agents, RAG, evaluation, typed functional programming, testing, CI and open-source collaboration.</p></li>
    </ul>
  </div>
</section>

<section class="lp-section lp-section--tint" aria-labelledby="the-learning-path">
  <div class="lp-wrap">
    <header class="lp-section__head">
      <p class="lp-eyebrow">The learning path</p>
      <h2 id="the-learning-path">Six steps from first call to first contribution</h2>
      <p>Each step links to guides and runnable examples in this documentation. Ollama lets you work through most of it on a local model, without an API key.</p>
    </header>
    <ol class="lp-steps">
      <li>
        <b>1</b>
        <div>
          <h3>Get set up and make your first call</h3>
          <p>Install LLM4S, configure a provider and send your first conversation. New to Scala? Scala CLI is the quickest way in.</p>
          <p class="lp-steps__links"><a href="{{ '/getting-started/installation' | relative_url }}">Installation</a> <a href="{{ '/getting-started/ollama-quickstart' | relative_url }}">Ollama quickstart</a> <a href="{{ '/getting-started/first-example' | relative_url }}">First example</a> <a href="{{ '/getting-started/scala-cli' | relative_url }}">Scala CLI</a></p>
        </div>
      </li>
      <li>
        <b>2</b>
        <div>
          <h3>Understand the basics</h3>
          <p>Conversations, streaming, errors as values and structured output: the ideas every later step builds on.</p>
          <p class="lp-steps__links"><a href="{{ '/guide/basic-usage' | relative_url }}">Basic usage</a> <a href="{{ '/guide/error-handling' | relative_url }}">Error handling</a> <a href="{{ '/guide/structured-output' | relative_url }}">Structured output</a></p>
        </div>
      </li>
      <li>
        <b>3</b>
        <div>
          <h3>Build your first agent</h3>
          <p>Give a model tools, keep multi-turn state, add guardrails and hand off to a specialist agent.</p>
          <p class="lp-steps__links"><a href="{{ '/guide/agents/' | relative_url }}">Agents</a> <a href="{{ '/guide/custom-tools' | relative_url }}">Custom tools</a> <a href="{{ '/guide/agents/guardrails' | relative_url }}">Guardrails</a> <a href="{{ '/guide/agents/handoffs' | relative_url }}">Handoffs</a></p>
        </div>
      </li>
      <li>
        <b>4</b>
        <div>
          <h3>Add retrieval (RAG)</h3>
          <p>Embed documents, store them in a vector store, search with hybrid ranking and measure answer quality.</p>
          <p class="lp-steps__links"><a href="{{ '/guide/embeddings' | relative_url }}">Embeddings</a> <a href="{{ '/guide/vector-store' | relative_url }}">Vector stores</a> <a href="{{ '/guide/rag-evaluation' | relative_url }}">RAG evaluation</a></p>
        </div>
      </li>
      <li>
        <b>5</b>
        <div>
          <h3>Connect tools, MCP and observability</h3>
          <p>Derive typed tools from case classes, connect an agent to Model Context Protocol servers and trace what it does.</p>
          <p class="lp-steps__links"><a href="{{ '/guide/derived-schemas' | relative_url }}">Typed tools</a> <a href="{{ '/examples/#mcp-examples' | relative_url }}">MCP examples</a> <a href="{{ '/guide/observability/' | relative_url }}">Observability</a></p>
        </div>
      </li>
      <li>
        <b>6</b>
        <div>
          <h3>Contribute</h3>
          <p>Read the contributing guide, pick a good first issue, ask questions on Discord and bring your work to Dev Hour.</p>
          <p class="lp-steps__links"><a href="{{ '/reference/contributing' | relative_url }}">Contributing guide</a> <a href="https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22">Good first issues</a> <a href="{{ '/contribute/' | relative_url }}">Ways to contribute</a></p>
        </div>
      </li>
    </ol>
  </div>
</section>

<section class="lp-section" aria-labelledby="community-learn">
  <div class="lp-wrap">
    <header class="lp-section__head">
      <p class="lp-eyebrow">Learn with others</p>
      <h2 id="community-learn">You will not be learning alone</h2>
    </header>
    <ul class="lp-grid lp-grid--3">
      <li class="lp-card"><p class="lp-card__kicker">Every Sunday, 09:00 London</p><h3>LLM4S Dev Hour</h3><p>A weekly mob-programming session on Discord, hosted by Kannupriya Kalra and Rory Graves. Code, debug and learn together, whether it is your first contribution or your hundredth.</p><a href="{{ '/events/' | relative_url }}">Next session and time zones</a></li>
      <li class="lp-card"><p class="lp-card__kicker">Any time</p><h3>Discord</h3><p>Ask questions, find an issue to work on, get unstuck and share what you build.</p><a href="{{ site.discord_invite }}">Join the Discord</a></li>
      <li class="lp-card"><p class="lp-card__kicker">Starter issues</p><h3>Good first issues</h3><p>Issues labelled for newcomers, scoped so you can finish one while you learn the codebase.</p><a href="https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22">Browse good first issues</a></li>
    </ul>
  </div>
</section>

<section class="lp-section lp-learn" aria-labelledby="mentorship">
  <div class="lp-wrap">
    <header class="lp-section__head">
      <p class="lp-eyebrow lp-eyebrow--light">Mentorship programs</p>
      <h2 id="mentorship">Mentored, and sometimes funded, open-source work</h2>
      <p class="lp-on-dark">Mentorship programs pair contributors with LLM4S mentors on a scoped project.</p>
    </header>
    <ul class="lp-grid lp-grid--2 lp-grid--dark">
      <li class="lp-card lp-card--dark">
        <p class="lp-card__kicker">2025</p>
        <h3>Google Summer of Code 2025</h3>
        <p>LLM4S took part in GSoC 2025 under the Scala Center umbrella. Four interns from the United States, Switzerland and India were funded to work on the project during summer 2025.</p>
        <a href="{{ '/gsoc/2025/' | relative_url }}">Meet the 2025 cohort</a>
      </li>
      <li class="lp-card lp-card--dark">
        <p class="lp-card__kicker">2026</p>
        <h3>Mentorship tracks for 2026</h3>
        <p>The LLM4S roadmap lists three community mentorship tracks for 2026, each with a public list of project ideas linked to roadmap phases:</p>
        <ul class="lp-list">
          <li><a href="https://github.com/llm4s/llm4s/blob/main/LFX%20Mentorship/Project%20Ideas/2026.md">LFX Mentorship 2026 ideas</a></li>
          <li><a href="https://github.com/llm4s/llm4s/blob/main/European%20Summer%20of%20Code/Project%20Ideas/2026.md">European Summer of Code 2026 ideas</a></li>
          <li><a href="https://github.com/llm4s/llm4s/blob/main/Google%20Summer%20of%20Code/Project%20Ideas/2026.md">Google Summer of Code 2026 ideas</a></li>
        </ul>
        <a href="{{ '/reference/roadmap#community-mentorship-tracks-2026' | relative_url }}">How the tracks map to the roadmap</a>
      </li>
    </ul>
    <p class="lp-on-dark lp-small">Each program runs its own selection; check its own site for dates and eligibility. Universities and companies that want to host or fund interns can <a href="{{ '/foundation/#partner-on-internships' | relative_url }}">partner with the foundation</a>.</p>
  </div>
</section>

<section class="lp-section" aria-labelledby="first-pr">
  <div class="lp-wrap lp-split lp-split--top">
    <div class="lp-split__text">
      <p class="lp-eyebrow">Your first pull request</p>
      <h2 id="first-pr">How to make your first contribution</h2>
      <p>The <a href="{{ '/reference/contributing' | relative_url }}">contributing guide</a> has the details. In short:</p>
    </div>
    <ol class="lp-numbered">
      <li>Read the contributing guide and the code of conduct.</li>
      <li>Pick a good first issue and comment that you would like to work on it.</li>
      <li>Fork the repository, build it with <code>sbt compile</code> and run the tests with <code>sbt test</code>.</li>
      <li>Keep the change small, add tests, and format with <code>sbt scalafmtAll</code>.</li>
      <li>Sign off every commit (<code>git commit -s</code>) and open the pull request.</li>
      <li>Answer review comments; ask on Discord or at Dev Hour whenever you are stuck.</li>
    </ol>
  </div>
</section>
