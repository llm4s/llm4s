---
layout: landing
title: Foundation
nav_order: 13
description: "Support the LLM4S Foundation: what funding pays for, ways for individuals, companies, grant makers and universities to give or partner, and how the money is handled transparently through Open Collective."
permalink: /foundation/
---

<section class="lp-hero lp-hero--page" aria-labelledby="foundation-hero">
  <div class="lp-wrap lp-hero__grid">
    <div class="lp-hero__copy">
      <p class="lp-eyebrow lp-eyebrow--light">LLM4S Foundation</p>
      <h1 id="foundation-hero">Help make type-safe AI on the JVM <em>sustainable</em></h1>
      <p class="lp-lead">LLM4S is community-led and maintained by a small core team, largely in volunteer time. Your support turns it into open infrastructure that Scala and JVM teams can rely on for years.</p>
      <p class="lp-actions">
        <a class="lp-btn lp-btn--primary lp-btn--lg" href="https://opencollective.com/llm4s">Support on Open Collective</a>
        <a class="lp-btn lp-btn--ghost-dark lp-btn--lg" href="mailto:kannupriya@llm4s.org">Contact us</a>
      </p>
    </div>
    <div class="lp-goal" aria-labelledby="goal-heading">
      <p class="lp-eyebrow lp-eyebrow--light">Current funding plan</p>
      <h2 id="goal-heading">Maintainer sustainability</h2>
      <p class="lp-goal__amount">US$25,000 <span>one-time</span></p>
      <p>Maintainer time, contributor onboarding, documentation, examples, release engineering, CI, testing, security hardening, community Dev Hours and production readiness, and the move toward stronger governance and legal registration.</p>
      <p class="lp-goal__src">Published in the project's <a href="https://github.com/llm4s/llm4s/blob/main/funding.json">funding.json</a>.</p>
    </div>
  </div>
</section>

<section class="lp-section" aria-labelledby="funds-heading">
  <div class="lp-wrap">
    <header class="lp-section__head">
      <p class="lp-eyebrow">What your support funds</p>
      <h2 id="funds-heading">Where the money goes</h2>
      <p>Funding goes to the work that keeps a library dependable, the work that is hardest to do on volunteer time alone.</p>
    </header>
    <ul class="lp-grid lp-grid--3">
      <li class="lp-card lp-card--flat"><h3>Maintainer time</h3><p>Reviewing pull requests, triaging issues, answering users and keeping the modules healthy as providers change their APIs.</p></li>
      <li class="lp-card lp-card--flat"><h3>Release engineering and CI</h3><p>Reliable releases, the 1.0 compatibility baseline, tests across modules and the CI that runs them.</p></li>
      <li class="lp-card lp-card--flat"><h3>Security hardening</h3><p>Tool and MCP security, dependency hygiene and the threat model that production users depend on.</p></li>
      <li class="lp-card lp-card--flat"><h3>Documentation and examples</h3><p>Guides, runnable examples and reference applications that make adoption straightforward.</p></li>
      <li class="lp-card lp-card--flat"><h3>Onboarding and Dev Hour</h3><p>The weekly Dev Hour and the time it takes to bring new contributors, many of them students, to their first merged pull request.</p></li>
      <li class="lp-card lp-card--flat"><h3>Internships</h3><p>Mentored, paid project work for students and early-career engineers, alongside programs such as Google Summer of Code.</p></li>
    </ul>
  </div>
</section>

<section class="lp-section lp-section--tint" aria-labelledby="ways-heading">
  <div class="lp-wrap">
    <header class="lp-section__head">
      <p class="lp-eyebrow">Ways to give</p>
      <h2 id="ways-heading">How you can support LLM4S</h2>
    </header>
    <ul class="lp-grid lp-grid--2">
      <li class="lp-card">
        <p class="lp-card__kicker">Individuals</p>
        <h3>Give through Open Collective</h3>
        <p>A one-time or recurring contribution of any size, visible on the project's public ledger.</p>
        <a href="https://opencollective.com/llm4s">Contribute on Open Collective</a>
      </li>
      <li class="lp-card">
        <p class="lp-card__kicker">Companies</p>
        <h3>Sponsor the project you depend on</h3>
        <p>If your organisation relies on LLM4S for production AI workloads, sponsorship funds the maintenance, security updates and stability your systems need. We would love to talk.</p>
        <a href="mailto:kannupriya@llm4s.org">Talk to us about sponsorship</a>
      </li>
      <li class="lp-card">
        <p class="lp-card__kicker">Grant makers and institutions</p>
        <h3>Grants and institutional support</h3>
        <p>We welcome grants and institutional support for open-source infrastructure. Bank-transfer details can be shared privately when grant paperwork requires them.</p>
        <a href="mailto:kannupriya@llm4s.org">Get in touch</a>
      </li>
      <li class="lp-card" id="partner-on-internships">
        <p class="lp-card__kicker">Universities and companies</p>
        <h3>Partner on internships</h3>
        <p>Host, fund or co-mentor student internships on LLM4S. Students work on real roadmap items, reviewed in public, and leave with merged code and experience of AI engineering on the JVM.</p>
        <a href="mailto:kannupriya@llm4s.org?subject=Internship%20partnership">Propose a partnership</a>
      </li>
    </ul>
    <p class="lp-small">Not in a position to give money? Contributions of code, documentation, reviews, talks and answers on Discord sustain the project too: see <a href="{{ '/learn/' | relative_url }}">Learn</a> and the <a href="{{ '/reference/contributing' | relative_url }}">contributing guide</a>.</p>
  </div>
</section>

<section class="lp-section" aria-labelledby="matters-heading">
  <div class="lp-wrap lp-split lp-split--top">
    <div class="lp-split__text">
      <p class="lp-eyebrow">Why it matters</p>
      <h2 id="matters-heading">Closing the gap between AI tooling and the JVM</h2>
      <p>Most LLM tooling today is Python-first, while many production systems in finance, telecom, data platforms, enterprise software and distributed systems are built on Scala and the JVM. LLM4S gives those teams open infrastructure for building AI applications without abandoning their production stack.</p>
      <p>Funding helps it become sustainable Scala and JVM AI infrastructure rather than a purely volunteer-led effort, on its way to a 1.0 release with a binary-compatibility promise.</p>
    </div>
    <ul class="lp-checks lp-checks--big">
      <li><strong>Built in the open.</strong> {{ site.data.stats.github[2].value }} contributors and {{ site.data.stats.github[3].value }} merged pull requests (as of {{ site.data.stats.as_of }}).</li>
      <li><strong>Growing the next generation.</strong> Four funded Google Summer of Code 2025 interns, under the Scala Center umbrella, and mentorship tracks planned for 2026.</li>
      <li><strong>Present in the community.</strong> Talks at Scala meetups, conferences and developer events around the world; see <a href="{{ '/events/' | relative_url }}">Events</a>.</li>
      <li><strong>Tracked publicly.</strong> Project health and activity are visible on <a href="{{ site.data.stats.lfx_insights }}">LFX Insights</a>.</li>
    </ul>
  </div>
</section>

<section class="lp-section lp-section--tint" aria-labelledby="transparency-heading">
  <div class="lp-wrap">
    <header class="lp-section__head">
      <p class="lp-eyebrow">Transparency</p>
      <h2 id="transparency-heading">How funds are handled</h2>
    </header>
    <div class="lp-grid lp-grid--3">
      <div class="lp-card lp-card--flat"><h3>Open Collective</h3><p>Donations and grants are handled through <a href="https://opencollective.com/llm4s">Open Collective</a>, where income and expenses are public.</p></div>
      <div class="lp-card lp-card--flat"><h3>Legal status</h3><p>The LLM4S Foundation is a community-led open-source group, not yet a registered legal entity. Part of the funding goes toward completing legal registration and a stronger long-term governance structure.</p></div>
      <div class="lp-card lp-card--flat"><h3>Machine-readable</h3><p>Funding channels and plans are published in <a href="https://github.com/llm4s/llm4s/blob/main/funding.json">funding.json</a>, following the fundingjson.org schema, and governance in <a href="https://github.com/llm4s/llm4s/blob/main/GOVERNANCE.md">GOVERNANCE.md</a>.</p></div>
    </div>
  </div>
</section>

<section class="lp-section" aria-labelledby="mission-heading">
  <div class="lp-wrap">
    <header class="lp-section__head">
      <p class="lp-eyebrow">Our mission</p>
      <h2 id="mission-heading">Advancing type-safe AI, with an inclusive community</h2>
      <p>We created the LLM4S Foundation to strengthen the long-term sustainability of the LLM4S ecosystem, advance type-safe artificial intelligence and grow an inclusive community of curious and passionate developers.</p>
    </header>
    <ol class="lp-grid lp-grid--3 lp-mission">
      <li class="lp-card lp-card--flat"><h3>Sustainability</h3><p>Keep the core libraries maintained, up to date and secure.</p></li>
      <li class="lp-card lp-card--flat"><h3>Innovation</h3><p>Move faster on multi-agent workflows, RAG and multimodal support, and reach a stable 1.0.</p></li>
      <li class="lp-card lp-card--flat"><h3>Community</h3><p>Fund mentorship, documentation and developer events to grow the next generation of Scala AI engineers.</p></li>
    </ol>
    <div class="lp-callout lp-callout--cta">
      <p><strong>Thank you for building the future of AI with us.</strong> Questions about supporting LLM4S? Email <a href="mailto:kannupriya@llm4s.org">kannupriya@llm4s.org</a>.</p>
      <p class="lp-actions"><a class="lp-btn lp-btn--primary" href="https://opencollective.com/llm4s">Support on Open Collective</a></p>
    </div>
  </div>
</section>
