---
layout: page
title: Contribute
nav_order: 9
---

# Contribute to LLM4S
{: .fs-9 }

Find open issues organized by category and start contributing.
{: .fs-6 .fw-300 }

This page links directly to [GitHub Issues](https://github.com/llm4s/llm4s/issues) filtered by label. Browse existing issues below, or check the [project roadmap](../reference/roadmap) for context on priorities.

---

## Quick Links by Label
{: .text-purple-300 }

Jump directly to issues filtered by label.
{: .text-grey-dk-000 .fs-3 }

<div class="issue-labels-grid">

<a href="https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22" class="issue-label-card">
<span class="label-icon">🌱</span>
<div class="label-info">
<h5>Good First Issues</h5>
<p>Great for newcomers</p>
</div>
</a>

<a href="https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+label%3Adocumentation" class="issue-label-card">
<span class="label-icon">📚</span>
<div class="label-info">
<h5>Documentation</h5>
<p>Docs improvements</p>
</div>
</a>

<a href="https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+label%3Atesting" class="issue-label-card">
<span class="label-icon">🧪</span>
<div class="label-info">
<h5>Testing</h5>
<p>Test coverage & quality</p>
</div>
</a>

<a href="https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+label%3Aenhancement" class="issue-label-card">
<span class="label-icon">✨</span>
<div class="label-info">
<h5>Enhancements</h5>
<p>New features & improvements</p>
</div>
</a>

<a href="https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+label%3Abug" class="issue-label-card">
<span class="label-icon">🐛</span>
<div class="label-info">
<h5>Bugs</h5>
<p>Bug fixes needed</p>
</div>
</a>

<a href="https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+label%3A%22help+wanted%22" class="issue-label-card">
<span class="label-icon">🙋</span>
<div class="label-info">
<h5>Help Wanted</h5>
<p>Community help appreciated</p>
</div>
</a>

</div>

---

## 🚧 Testing & Quality
{: .text-purple-300 }

Status: **In Progress** — Target: 80%+ statement coverage
{: .text-grey-dk-000 .fs-3 }

<div class="contribute-grid">

<div class="contribute-card">
<h4>Test Coverage Improvements</h4>
<p>Help increase test coverage from ~21% to 80%+ across core modules.</p>
<div class="card-meta">
<span class="label label-p1">P1</span>
<span class="label label-medium">Medium</span>
<span class="label label-testing">Testing</span>
<span class="label label-good-first-issue">Good First Issue</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+label%3Atesting" class="btn">View Testing Issues →</a>
</div>

</div>

---

## 🚧 API Stability
{: .text-purple-300 }

Status: **In Progress** — Target: MiMa checks, SemVer policy
{: .text-grey-dk-000 .fs-3 }

<div class="contribute-grid">

<div class="contribute-card">
<h4>Binary Compatibility & Versioning</h4>
<p>MiMa checks for safe upgrades and semantic versioning policy.</p>
<div class="card-meta">
<span class="label label-p1">P1</span>
<span class="label label-medium">Medium</span>
<span class="label label-infra">Infra</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+label%3Ainfra" class="btn">View Infra Issues →</a>
</div>

</div>

---

## 📋 Performance & Security
{: .text-purple-300 }

Status: **Planned** — Target: JMH benchmarks, threat model
{: .text-grey-dk-000 .fs-3 }

<div class="contribute-grid">

<div class="contribute-card">
<h4>Performance Benchmarks</h4>
<p>JMH framework, baseline metrics for v1.0 release.</p>
<div class="card-meta">
<span class="label label-p1">P1</span>
<span class="label label-hard">Hard</span>
<span class="label label-infra">Infra</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+performance+OR+benchmark" class="btn">View Performance Issues →</a>
</div>

<div class="contribute-card">
<h4>Security Improvements</h4>
<p>Threat model, vulnerability scanning for production readiness.</p>
<div class="card-meta">
<span class="label label-p1">P1</span>
<span class="label label-hard">Hard</span>
<span class="label label-infra">Infra</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+security" class="btn">View Security Issues →</a>
</div>

</div>

---

## 🚧 Documentation
{: .text-purple-300 }

Status: **In Progress** — Target: Complete guides, 100% public API docs
{: .text-grey-dk-000 .fs-3 }

<div class="contribute-grid">

<div class="contribute-card">
<h4>Documentation Improvements</h4>
<p>ScalaDoc coverage, guides, and examples for all public APIs.</p>
<div class="card-meta">
<span class="label label-p1">P1</span>
<span class="label label-easy">Easy</span>
<span class="label label-docs">Docs</span>
<span class="label label-good-first-issue">Good First Issue</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+label%3Adocumentation" class="btn">View Docs Issues →</a>
</div>

</div>

---

## 🚧 Observability
{: .text-purple-300 }

Status: **Expanding** — Core Langfuse integration complete, extended features planned
{: .text-grey-dk-000 .fs-3 }

<div class="contribute-grid">

<div class="contribute-card">
<h4>Extended Observability</h4>
<p>Expand observability features beyond current Langfuse integration.</p>
<div class="card-meta">
<span class="label label-in-progress">In Progress</span>
<span class="label label-medium">Medium</span>
<span class="label label-feature">Feature</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+observability+OR+tracing+OR+langfuse" class="btn">View Observability Issues →</a>
</div>

</div>

---

## 🔨 Active Development
{: .text-purple-300 }

Major features currently in progress.
{: .text-grey-dk-000 .fs-3 }

<div class="contribute-grid">

<div class="contribute-card">
<h4>MCP (Model Context Protocol)</h4>
<p>Model Context Protocol integration - approximately 50% complete.</p>
<div class="card-meta">
<span class="label label-in-progress">~50%</span>
<span class="label label-hard">Hard</span>
<span class="label label-feature">Feature</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+MCP" class="btn">View MCP Issues →</a>
</div>

<div class="contribute-card">
<h4>Advanced Embeddings</h4>
<p>Advanced embedding capabilities - approximately 60% complete.</p>
<div class="card-meta">
<span class="label label-in-progress">~60%</span>
<span class="label label-medium">Medium</span>
<span class="label label-feature">Feature</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+embedding" class="btn">View Embedding Issues →</a>
</div>

<div class="contribute-card">
<h4>Reliable Calling</h4>
<p>High-priority reliability improvements for LLM calls.</p>
<div class="card-meta">
<span class="label label-p0">P0</span>
<span class="label label-hard">Hard</span>
<span class="label label-feature">Feature</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+reliability+OR+retry" class="btn">View Reliability Issues →</a>
</div>

</div>

---

## 📅 Medium Term Roadmap
{: .text-purple-300 }

Planned features for Q3-Q4 2025.
{: .text-grey-dk-000 .fs-3 }

<div class="contribute-grid">

<div class="contribute-card">
<h4>Prompt Management & Caching</h4>
<p>Prompt tuning, management system, and caching layer.</p>
<div class="card-meta">
<span class="label label-p1">P1</span>
<span class="label label-medium">Medium</span>
<span class="label label-feature">Feature</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+prompt+OR+cache" class="btn">View Related Issues →</a>
</div>

<div class="contribute-card">
<h4>Cost Tracking</h4>
<p>Comprehensive cost tracking for LLM API and RAG pipelines.</p>
<div class="card-meta">
<span class="label label-p2">P2</span>
<span class="label label-easy">Easy</span>
<span class="label label-feature">Feature</span>
<span class="label label-good-first-issue">Good First Issue</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+cost" class="btn">View Cost Issues →</a>
</div>

<div class="contribute-card">
<h4>Provider Expansion</h4>
<p>Expand LLM provider support beyond current integrations.</p>
<div class="card-meta">
<span class="label label-p2">P2</span>
<span class="label label-medium">Medium</span>
<span class="label label-feature">Feature</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+provider" class="btn">View Provider Issues →</a>
</div>

</div>

---

## 🔌 Extended Integrations
{: .text-purple-300 }

Vector database and embedding provider integrations.
{: .text-grey-dk-000 .fs-3 }

<div class="contribute-grid">

<div class="contribute-card">
<h4>Vector Database Integrations</h4>
<p>Milvus, Pinecone, and other vector database backends.</p>
<div class="card-meta">
<span class="label label-p2">P2</span>
<span class="label label-medium">Medium</span>
<span class="label label-feature">Feature</span>
<span class="label label-good-first-issue">Good First Issue</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+vector+OR+milvus+OR+pinecone" class="btn">View Vector DB Issues →</a>
</div>

<div class="contribute-card">
<h4>Embedding Providers</h4>
<p>Cohere embeddings, ONNX local embeddings, embedding cache.</p>
<div class="card-meta">
<span class="label label-p2">P2</span>
<span class="label label-easy">Easy</span>
<span class="label label-feature">Feature</span>
<span class="label label-good-first-issue">Good First Issue</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+embedding+OR+cohere+OR+onnx" class="btn">View Embedding Issues →</a>
</div>

</div>

---

## 🔮 Long Term (Post v1.0)
{: .text-purple-300 }

Future roadmap items.
{: .text-grey-dk-000 .fs-3 }

<div class="contribute-grid">

<div class="contribute-card">
<h4>Advanced Features</h4>
<p>Fine-tuning APIs, workflow engines, plugin architecture, multi-agent orchestration.</p>
<div class="card-meta">
<span class="label label-p2">Future</span>
<span class="label label-hard">Hard</span>
<span class="label label-feature">Feature</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+label%3Aenhancement" class="btn">View All Enhancements →</a>
</div>

</div>

---

## ✅ Already Completed
{: .text-purple-300 }

These features are implemented. Check the [roadmap](../reference/roadmap) for details.
{: .text-grey-dk-000 .fs-3 }

<div class="completed-grid">

<div class="completed-card">
<span class="completed-icon">✓</span>
<div class="completed-content">
<h5>SQLite Backend</h5>
<p>File-based and in-memory vector storage</p>
</div>
</div>

<div class="completed-card">
<span class="completed-icon">✓</span>
<div class="completed-content">
<h5>pgvector Backend</h5>
<p>PostgreSQL + pgvector extension</p>
</div>
</div>

<div class="completed-card">
<span class="completed-icon">✓</span>
<div class="completed-content">
<h5>Qdrant Backend</h5>
<p>REST API, local + cloud support</p>
</div>
</div>

<div class="completed-card">
<span class="completed-icon">✓</span>
<div class="completed-content">
<h5>RAG Core Engine</h5>
<p>Complete retrieval pipeline</p>
</div>
</div>

<div class="completed-card">
<span class="completed-icon">✓</span>
<div class="completed-content">
<h5>RAGAS Evaluation</h5>
<p>Faithfulness, relevancy, precision metrics</p>
</div>
</div>

<div class="completed-card">
<span class="completed-icon">✓</span>
<div class="completed-content">
<h5>Guardrails System</h5>
<p>PII detection, prompt injection, grounding</p>
</div>
</div>

<div class="completed-card">
<span class="completed-icon">✓</span>
<div class="completed-content">
<h5>Langfuse Observability</h5>
<p>Tracing and structured logging</p>
</div>
</div>

<div class="completed-card">
<span class="completed-icon">✓</span>
<div class="completed-content">
<h5>Hybrid Search</h5>
<p>BM25 + vector fusion, reranking</p>
</div>
</div>

<div class="completed-card">
<span class="completed-icon">✓</span>
<div class="completed-content">
<h5>Agent Framework</h5>
<p>Core agent, handoffs, memory, streaming</p>
</div>
</div>

<div class="completed-card">
<span class="completed-icon">✓</span>
<div class="completed-content">
<h5>Tool Calling API</h5>
<p>Built-in tools, MCP server support</p>
</div>
</div>

</div>

---

<div class="contribute-footer">
<p>
Don't see what you're looking for? Browse <a href="https://github.com/llm4s/llm4s/issues">all open issues</a> or check the <a href="../reference/roadmap">full roadmap</a>.
</p>
<a href="https://github.com/llm4s/llm4s/issues" class="btn btn-primary">Browse All Issues →</a>
</div>
