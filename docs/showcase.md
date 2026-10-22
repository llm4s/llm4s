---
layout: landing
title: Showcase
nav_order: 8.7
permalink: /showcase/
description: "Open-source projects built with LLM4S: from the LLM4S organisation and from the community."
---

<section class="lp-hero lp-hero--page" aria-labelledby="showcase-hero">
  <div class="lp-wrap">
    <div class="lp-hero__copy">
      <p class="lp-eyebrow lp-eyebrow--light">Showcase</p>
      <h1 id="showcase-hero">Open-source projects <em>built with</em> LLM4S</h1>
      <p class="lp-lead">Games, RAG services, agent frameworks, developer tools and starter projects. All of them are public repositories, so you can read the code and see how LLM4S is used.</p>
      <p class="lp-actions">
        <a class="lp-btn lp-btn--primary lp-btn--lg" href="#add-your-project">Add your project</a>
        <a class="lp-btn lp-btn--ghost-dark lp-btn--lg" href="{{ '/getting-started/' | relative_url }}">Build your own</a>
      </p>
    </div>
  </div>
</section>

<section class="lp-section" aria-labelledby="org-projects">
  <div class="lp-wrap">
    <header class="lp-section__head">
      <p class="lp-eyebrow">From the LLM4S organisation</p>
      <h2 id="org-projects">Demos, services and tooling</h2>
    </header>
    {% include showcase-grid.html group="org" %}
  </div>
</section>

<section class="lp-section lp-section--tint" aria-labelledby="community-projects">
  <div class="lp-wrap">
    <header class="lp-section__head">
      <p class="lp-eyebrow">From the community</p>
      <h2 id="community-projects">Built by LLM4S users</h2>
      <p>Public repositories that depend on <code>org.llm4s</code>, found by searching GitHub. They are listed to help you learn from real code; a listing is not an endorsement, and it does not say anything about the authors' employers.</p>
    </header>
    {% include showcase-grid.html group="community" %}
  </div>
</section>

<section class="lp-section" aria-labelledby="add-your-project">
  <div class="lp-wrap lp-split lp-split--top">
    <div class="lp-split__text">
      <p class="lp-eyebrow">Add yours</p>
      <h2 id="add-your-project">Built something with LLM4S?</h2>
      <p>Tell us about it. Open-source projects can be added to this page, and organisations that use LLM4S can list themselves in ADOPTERS.md. Both are voluntary and by pull request: the maintainers do not add anyone on their own.</p>
    </div>
    <ul class="lp-grid">
      <li class="lp-card"><h3>An open-source project</h3><p>Open a pull request that adds it to <code>docs/_data/showcase.yml</code>, with a one-line description from your README.</p><a href="https://github.com/llm4s/llm4s/blob/main/docs/_data/showcase.yml">Edit showcase.yml</a></li>
      <li class="lp-card"><h3>Your organisation</h3><p>Add a row to ADOPTERS.md with your name, a public link and a one-line use case.</p><a href="https://github.com/llm4s/llm4s/blob/main/ADOPTERS.md">Add to ADOPTERS.md</a></li>
      <li class="lp-card"><h3>Not sure yet?</h3><p>Show it on Discord first; a maintainer can help you write the entry.</p><a href="{{ site.discord_invite }}">Share on Discord</a></li>
    </ul>
  </div>
</section>
