---
layout: landing
title: News
nav_order: 8.6
permalink: /news/
description: "LLM4S news: a post for each release with what is new and how to upgrade, plus community news. Follow it with the Atom feed."
---

<section class="lp-hero lp-hero--page" aria-labelledby="news-hero">
  <div class="lp-wrap">
    <div class="lp-hero__copy">
      <p class="lp-eyebrow lp-eyebrow--light">News</p>
      <h1 id="news-hero">What is new in <em>LLM4S</em></h1>
      <p class="lp-lead">A short post for each release: what changed, why it matters and how to upgrade. The full list of changes is in the <a href="https://github.com/llm4s/llm4s/blob/main/CHANGELOG.md">CHANGELOG</a>.</p>
      <p class="lp-actions">
        <a class="lp-btn lp-btn--primary lp-btn--lg" href="{{ '/feed.xml' | relative_url }}">Subscribe (Atom)</a>
        <a class="lp-btn lp-btn--ghost-dark lp-btn--lg" href="https://github.com/llm4s/llm4s/releases">GitHub releases</a>
      </p>
      {%- assign news_tags = site.tags | sort %}
      {%- if news_tags.size > 0 %}
      <p class="lp-hero__meta">Feeds by tag:{% for tag in news_tags %} <a href="{{ '/feed/by_tag/' | append: tag[0] | append: '.xml' | relative_url }}">{{ tag[0] }}</a>{% unless forloop.last %} &middot;{% endunless %}{% endfor %}</p>
      {%- endif %}
    </div>
  </div>
</section>

<section class="lp-section" aria-labelledby="news-posts">
  <div class="lp-wrap">
    <h2 id="news-posts" class="lp-visually-hidden">All posts, newest first</h2>
    <ul class="lp-news">
      {%- for post in site.posts %}
      <li class="lp-card lp-card--link">
        <p class="lp-card__kicker"><time datetime="{{ post.date | date_to_xmlschema }}">{{ post.date | date: "%-d %B %Y" }}</time>{% for tag in post.tags %} &middot; {{ tag }}{% endfor %}</p>
        <h3><a href="{{ post.url | relative_url }}">{{ post.title }}</a></h3>
        <p>{{ post.description }}</p>
      </li>
      {%- endfor %}
    </ul>
  </div>
</section>
