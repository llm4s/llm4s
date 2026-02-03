---
layout: page
title: Contribute
nav_order: 9
---

# Contribute to LLM4S
{: .fs-9 }

Welcome to the LLM4S Contribute page.
{: .fs-6 .fw-300 }

Here you'll find open issues from the [project roadmap](../reference/roadmap) that you can help with. Each item links directly to GitHub where you can create an issue and start contributing.

<style>
.contribute-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 1.5rem;
  margin: 1.5rem 0;
}
.contribute-card {
  background: var(--body-background-color);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  padding: 1.5rem;
  display: flex;
  flex-direction: column;
  transition: all 0.3s ease;
  box-shadow: 0 2px 8px rgba(0,0,0,0.08);
}
.contribute-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 8px 24px rgba(0,0,0,0.12);
  border-color: var(--link-color);
}
.contribute-card h4 {
  margin: 0 0 0.75rem 0;
  color: var(--body-heading-color);
  font-size: 1.1rem;
  font-weight: 600;
}
.contribute-card p {
  color: var(--body-text-color);
  font-size: 0.95rem;
  line-height: 1.5;
  flex-grow: 1;
  margin-bottom: 1rem;
}
.card-meta {
  display: flex;
  gap: 0.5rem;
  margin-bottom: 1rem;
  flex-wrap: wrap;
}
.label {
  display: inline-block;
  padding: 0.2rem 0.5rem;
  border-radius: 4px;
  font-size: 0.65rem;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.03em;
}
/* Priority labels */
.label-p0 { 
  background: #fef2f2; 
  color: #dc2626;
  border: 1px solid #fecaca;
}
.label-p1 { 
  background: #fffbeb; 
  color: #b45309;
  border: 1px solid #fde68a;
}
.label-p2 { 
  background: #eff6ff; 
  color: #2563eb;
  border: 1px solid #bfdbfe;
}
.label-in-progress { 
  background: #f5f3ff; 
  color: #7c3aed;
  border: 1px solid #ddd6fe;
}
/* Difficulty labels */
.label-easy { 
  background: #f0fdf4; 
  color: #16a34a;
  border: 1px solid #bbf7d0;
}
.label-medium { 
  background: #fefce8; 
  color: #a16207;
  border: 1px solid #fef08a;
}
.label-hard { 
  background: #fef2f2; 
  color: #dc2626;
  border: 1px solid #fecaca;
}
/* Type labels */
.label-docs { 
  background: #ecfeff; 
  color: #0891b2;
  border: 1px solid #a5f3fc;
}
.label-testing { 
  background: #fdf2f8; 
  color: #db2777;
  border: 1px solid #fbcfe8;
}
.label-feature { 
  background: #f5f3ff; 
  color: #7c3aed;
  border: 1px solid #ddd6fe;
}
.label-infra { 
  background: #f8fafc; 
  color: #475569;
  border: 1px solid #e2e8f0;
}
.label-good-first-issue { 
  background: #ecfdf5; 
  color: #059669;
  border: 1px solid #a7f3d0;
}
.contribute-card .btn {
  display: inline-block;
  background-color: #7253ed;
  background-image: linear-gradient(180deg, rgba(255,255,255,0.15) 0%, rgba(255,255,255,0) 100%);
  color: #fff !important;
  padding: 0.75rem 1.25rem;
  border-radius: 4px;
  text-decoration: none !important;
  font-weight: 500;
  font-size: 0.875rem;
  text-align: center;
  transition: all 0.2s ease;
  box-shadow: 0 1px 3px rgba(0,0,0,0.12), 0 1px 2px rgba(0,0,0,0.24);
  border: none;
  cursor: pointer;
  margin-top: auto;
}
.contribute-card .btn:hover {
  background-color: #5e41d0;
  box-shadow: 0 4px 6px rgba(0,0,0,0.15), 0 2px 4px rgba(0,0,0,0.12);
  transform: translateY(-1px);
}
/* Completed section styles */
.completed-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 1rem;
  margin: 1.5rem 0;
}
.completed-card {
  display: flex;
  align-items: flex-start;
  gap: 0.75rem;
  padding: 1rem;
  background: var(--body-background-color);
  border: 1px solid #22c55e;
  border-radius: 8px;
  transition: all 0.2s ease;
}
.completed-card:hover {
  box-shadow: 0 2px 8px rgba(34, 197, 94, 0.15);
  border-color: #16a34a;
}
.completed-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  background: #22c55e;
  color: white;
  border-radius: 50%;
  font-weight: 700;
  font-size: 0.875rem;
  flex-shrink: 0;
}
.completed-content h5 {
  margin: 0 0 0.25rem 0;
  font-size: 0.95rem;
  font-weight: 600;
  color: var(--body-heading-color);
}
.completed-content p {
  margin: 0;
  font-size: 0.8rem;
  color: var(--default-body-color);
  opacity: 0.8;
}
</style>

---

## 🚧 Testing & Quality
{: .text-purple-300 }

Status: **In Progress** — Target: 80%+ statement coverage
{: .text-grey-dk-000 .fs-3 }

<div class="contribute-grid">

<div class="contribute-card">
<h4>80%+ Statement Coverage</h4>
<p>Increase test coverage from ~21% to 80%+ across core modules.</p>
<div class="card-meta">
<span class="label label-p1">P1</span>
<span class="label label-medium">Medium</span>
<span class="label label-testing">Testing</span>
<span class="label label-good-first-issue">Good First Issue</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Increase%20Statement%20Coverage%20to%2080%25%2B&body=%23%23%20Goal%0AIncrease%20test%20coverage%20from%20~21%25%20to%2080%25%2B%20across%20core%20modules.%0A%0A%23%23%20Difficulty%0AMedium%0A%0A%23%23%20Labels%0Atesting%2C%20good%20first%20issue%0A%0A%23%23%20Source%0A%5BRoadmap%20-%20Success%20Metrics%5D(https%3A%2F%2Fllm4s.org%2Freference%2Froadmap%23success-metrics-v10-targets)" class="btn">Open GitHub Issue →</a>
</div>

</div>

---

## 🚧 API Stability
{: .text-purple-300 }

Status: **In Progress** — Target: MiMa checks, SemVer policy
{: .text-grey-dk-000 .fs-3 }

<div class="contribute-grid">

<div class="contribute-card">
<h4>MiMa Binary Compatibility Checks</h4>
<p>Implement MiMa checks to ensure safe upgrades with clear binary compatibility.</p>
<div class="card-meta">
<span class="label label-p1">P1</span>
<span class="label label-medium">Medium</span>
<span class="label label-infra">Infra</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Implement%20MiMa%20Binary%20Compatibility%20Checks&body=%23%23%20Goal%0AImplement%20MiMa%20checks%20for%20safe%20upgrades%20with%20clear%20binary%20compatibility.%0A%0A%23%23%20Difficulty%0AMedium%0A%0A%23%23%20Labels%0Ainfra%0A%0A%23%23%20Source%0A%5BRoadmap%20-%20API%20Stability%5D(https%3A%2F%2Fllm4s.org%2Freference%2Froadmap%23pillar-status)" class="btn">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>SemVer Policy</h4>
<p>Define and document semantic versioning policy for the project.</p>
<div class="card-meta">
<span class="label label-p1">P1</span>
<span class="label label-easy">Easy</span>
<span class="label label-docs">Docs</span>
<span class="label label-good-first-issue">Good First Issue</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Define%20SemVer%20Policy&body=%23%23%20Goal%0ADefine%20and%20document%20semantic%20versioning%20policy.%0A%0A%23%23%20Difficulty%0AEasy%0A%0A%23%23%20Labels%0Adocs%2C%20good%20first%20issue%0A%0A%23%23%20Source%0A%5BRoadmap%20-%20API%20Stability%5D(https%3A%2F%2Fllm4s.org%2Freference%2Froadmap%23pillar-status)" class="btn">Open GitHub Issue →</a>
</div>

</div>

---

## 📋 Performance
{: .text-purple-300 }

Status: **Planned** — Target: JMH benchmarks, baselines
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
<a href="https://github.com/llm4s/llm4s/issues/new?title=Performance%20Benchmarks&body=%23%23%20Goal%0AJMH%20framework%2C%20baseline%20metrics%20for%20v1.0%20release.%0A%0A%23%23%20Difficulty%0AHard%0A%0A%23%23%20Labels%0Ainfra%0A%0A%23%23%20Source%0A%5BRoadmap%20-%20Near%20Term%5D(https%3A%2F%2Fllm4s.org%2Freference%2Froadmap%23near-term-q1-q2-2025)" class="btn">Open GitHub Issue →</a>
</div>

</div>

---

## 📋 Security
{: .text-purple-300 }

Status: **Planned** — Target: Threat model, dependency scanning
{: .text-grey-dk-000 .fs-3 }

<div class="contribute-grid">

<div class="contribute-card">
<h4>Security Audit</h4>
<p>Threat model, vulnerability scanning for production readiness.</p>
<div class="card-meta">
<span class="label label-p1">P1</span>
<span class="label label-hard">Hard</span>
<span class="label label-infra">Infra</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Security%20Audit&body=%23%23%20Goal%0AThreat%20model%2C%20vulnerability%20scanning%20for%20production%20readiness.%0A%0A%23%23%20Difficulty%0AHard%0A%0A%23%23%20Labels%0Ainfra%2C%20security%0A%0A%23%23%20Source%0A%5BRoadmap%20-%20Near%20Term%5D(https%3A%2F%2Fllm4s.org%2Freference%2Froadmap%23near-term-q1-q2-2025)" class="btn">Open GitHub Issue →</a>
</div>

</div>

---

## 🚧 Documentation
{: .text-purple-300 }

Status: **In Progress** — Target: Complete guides, 100% public API docs
{: .text-grey-dk-000 .fs-3 }

<div class="contribute-grid">

<div class="contribute-card">
<h4>100% ScalaDoc Coverage</h4>
<p>Complete ScalaDoc coverage for all public APIs.</p>
<div class="card-meta">
<span class="label label-p1">P1</span>
<span class="label label-easy">Easy</span>
<span class="label label-docs">Docs</span>
<span class="label label-good-first-issue">Good First Issue</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues/new?title=100%25%20ScalaDoc%20Coverage&body=%23%23%20Goal%0AComplete%20ScalaDoc%20coverage%20for%20all%20public%20APIs.%0A%0A%23%23%20Difficulty%0AEasy%0A%0A%23%23%20Labels%0Adocs%2C%20good%20first%20issue%0A%0A%23%23%20Source%0A%5BRoadmap%20-%20Success%20Metrics%5D(https%3A%2F%2Fllm4s.org%2Freference%2Froadmap%23success-metrics-v10-targets)" class="btn">Open GitHub Issue →</a>
</div>

</div>

---

## 🚧 Observability
{: .text-purple-300 }

Status: **Enhanced Observability** — Planning phase
{: .text-grey-dk-000 .fs-3 }

<div class="contribute-grid">

<div class="contribute-card">
<h4>Enhanced Observability</h4>
<p>Expand observability features beyond current Langfuse integration.</p>
<div class="card-meta">
<span class="label label-in-progress">In Progress</span>
<span class="label label-medium">Medium</span>
<span class="label label-feature">Feature</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Enhanced%20Observability&body=%23%23%20Goal%0AExpand%20observability%20features%20beyond%20current%20Langfuse%20integration.%0A%0A%23%23%20Difficulty%0AMedium%0A%0A%23%23%20Labels%0Afeature%0A%0A%23%23%20Source%0A%5BRoadmap%20-%20What%27s%20In%20Progress%5D(https%3A%2F%2Fllm4s.org%2Freference%2Froadmap%23whats-in-progress)" class="btn">Open GitHub Issue →</a>
</div>

</div>

---

## 🚧 Community
{: .text-purple-300 }

Status: **In Progress** — Target: 15+ contributors
{: .text-grey-dk-000 .fs-3 }

<div class="contribute-grid">

<div class="contribute-card">
<h4>Grow Contributors to 15+</h4>
<p>Help grow the contributor community through documentation, mentorship, and outreach.</p>
<div class="card-meta">
<span class="label label-p1">P1</span>
<span class="label label-easy">Easy</span>
<span class="label label-docs">Docs</span>
<span class="label label-good-first-issue">Good First Issue</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Community%20Growth%3A%2015%2B%20Contributors&body=%23%23%20Goal%0AGrow%20contributor%20community%20through%20documentation%2C%20mentorship%2C%20and%20outreach.%0A%0A%23%23%20Difficulty%0AEasy%0A%0A%23%23%20Labels%0Adocs%2C%20good%20first%20issue%0A%0A%23%23%20Source%0A%5BRoadmap%20-%20Success%20Metrics%5D(https%3A%2F%2Fllm4s.org%2Freference%2Froadmap%23success-metrics-v10-targets)" class="btn">Open GitHub Issue →</a>
</div>

</div>

---

## 🔨 In Progress Features
{: .text-purple-300 }

Active development items from the roadmap.
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
<a href="https://github.com/llm4s/llm4s/issues/new?title=MCP%20Integration&body=%23%23%20Goal%0AComplete%20Model%20Context%20Protocol%20integration%20(currently%20~50%25).%0A%0A%23%23%20Difficulty%0AHard%0A%0A%23%23%20Labels%0Afeature%0A%0A%23%23%20Source%0A%5BRoadmap%20-%20What%27s%20In%20Progress%5D(https%3A%2F%2Fllm4s.org%2Freference%2Froadmap%23whats-in-progress)" class="btn">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Advanced Embeddings</h4>
<p>Advanced embedding capabilities - approximately 60% complete.</p>
<div class="card-meta">
<span class="label label-in-progress">~60%</span>
<span class="label label-medium">Medium</span>
<span class="label label-feature">Feature</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Advanced%20Embeddings&body=%23%23%20Goal%0AComplete%20advanced%20embedding%20capabilities%20(currently%20~60%25).%0A%0A%23%23%20Difficulty%0AMedium%0A%0A%23%23%20Labels%0Afeature%0A%0A%23%23%20Source%0A%5BRoadmap%20-%20What%27s%20In%20Progress%5D(https%3A%2F%2Fllm4s.org%2Freference%2Froadmap%23whats-in-progress)" class="btn">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Reliable Calling</h4>
<p>High-priority reliability improvements for LLM calls.</p>
<div class="card-meta">
<span class="label label-p0">P0</span>
<span class="label label-hard">Hard</span>
<span class="label label-feature">Feature</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Reliable%20Calling&body=%23%23%20Goal%0AHigh-priority%20reliability%20improvements%20for%20LLM%20calls.%0A%0A%23%23%20Difficulty%0AHard%0A%0A%23%23%20Labels%0Afeature%0A%0A%23%23%20Source%0A%5BRoadmap%20-%20What%27s%20In%20Progress%5D(https%3A%2F%2Fllm4s.org%2Freference%2Froadmap%23whats-in-progress)" class="btn">Open GitHub Issue →</a>
</div>

</div>

---

## 📅 Medium Term Roadmap
{: .text-purple-300 }

Planned features for medium-term development.
{: .text-grey-dk-000 .fs-3 }

<div class="contribute-grid">

<div class="contribute-card">
<h4>Prompt Tuning & Management</h4>
<p>Built-in prompt tuning capabilities and management system.</p>
<div class="card-meta">
<span class="label label-p1">P1</span>
<span class="label label-medium">Medium</span>
<span class="label label-feature">Feature</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Prompt%20Tuning%20%26%20Management&body=%23%23%20Goal%0ABuilt-in%20prompt%20tuning%20capabilities%20and%20management%20system.%0A%0A%23%23%20Difficulty%0AMedium%0A%0A%23%23%20Labels%0Afeature%0A%0A%23%23%20Source%0A%5BRoadmap%20-%20Medium%20Term%5D(https%3A%2F%2Fllm4s.org%2Freference%2Froadmap%23medium-term-q3-q4-2025)" class="btn">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>RAG Cost Tracking</h4>
<p>Cost tracking and optimization for RAG pipelines.</p>
<div class="card-meta">
<span class="label label-p1">P1</span>
<span class="label label-medium">Medium</span>
<span class="label label-feature">Feature</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues/new?title=RAG%20Cost%20Tracking&body=%23%23%20Goal%0ACost%20tracking%20and%20optimization%20for%20RAG%20pipelines.%0A%0A%23%23%20Difficulty%0AMedium%0A%0A%23%23%20Labels%0Afeature%0A%0A%23%23%20Source%0A%5BRoadmap%20-%20Medium%20Term%5D(https%3A%2F%2Fllm4s.org%2Freference%2Froadmap%23medium-term-q3-q4-2025)" class="btn">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Caching Layer</h4>
<p>Implement caching layer for LLM responses and embeddings.</p>
<div class="card-meta">
<span class="label label-p2">P2</span>
<span class="label label-medium">Medium</span>
<span class="label label-feature">Feature</span>
<span class="label label-good-first-issue">Good First Issue</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Caching%20Layer&body=%23%23%20Goal%0AImplement%20caching%20layer%20for%20LLM%20responses%20and%20embeddings.%0A%0A%23%23%20Difficulty%0AMedium%0A%0A%23%23%20Labels%0Afeature%2C%20good%20first%20issue%0A%0A%23%23%20Source%0A%5BRoadmap%20-%20Medium%20Term%5D(https%3A%2F%2Fllm4s.org%2Freference%2Froadmap%23medium-term-q3-q4-2025)" class="btn">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Cost Tracking</h4>
<p>Comprehensive cost tracking for LLM API usage.</p>
<div class="card-meta">
<span class="label label-p2">P2</span>
<span class="label label-easy">Easy</span>
<span class="label label-feature">Feature</span>
<span class="label label-good-first-issue">Good First Issue</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Cost%20Tracking&body=%23%23%20Goal%0AComprehensive%20cost%20tracking%20for%20LLM%20API%20usage.%0A%0A%23%23%20Difficulty%0AEasy%0A%0A%23%23%20Labels%0Afeature%2C%20good%20first%20issue%0A%0A%23%23%20Source%0A%5BRoadmap%20-%20Medium%20Term%5D(https%3A%2F%2Fllm4s.org%2Freference%2Froadmap%23medium-term-q3-q4-2025)" class="btn">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Provider Expansion</h4>
<p>Expand LLM provider support beyond current integrations.</p>
<div class="card-meta">
<span class="label label-p2">P2</span>
<span class="label label-medium">Medium</span>
<span class="label label-feature">Feature</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Provider%20Expansion&body=%23%23%20Goal%0AExpand%20LLM%20provider%20support%20beyond%20current%20integrations.%0A%0A%23%23%20Difficulty%0AMedium%0A%0A%23%23%20Labels%0Afeature%0A%0A%23%23%20Source%0A%5BRoadmap%20-%20Medium%20Term%5D(https%3A%2F%2Fllm4s.org%2Freference%2Froadmap%23medium-term-q3-q4-2025)" class="btn">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>ONNX Embeddings</h4>
<p>Local ONNX-based embeddings for offline/edge deployment.</p>
<div class="card-meta">
<span class="label label-p2">P2</span>
<span class="label label-hard">Hard</span>
<span class="label label-feature">Feature</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues/new?title=ONNX%20Embeddings&body=%23%23%20Goal%0ALocal%20ONNX-based%20embeddings%20for%20offline%2Fedge%20deployment.%0A%0A%23%23%20Difficulty%0AHard%0A%0A%23%23%20Labels%0Afeature%0A%0A%23%23%20Source%0A%5BRoadmap%20-%20Medium%20Term%5D(https%3A%2F%2Fllm4s.org%2Freference%2Froadmap%23medium-term-q3-q4-2025)" class="btn">Open GitHub Issue →</a>
</div>

</div>

---

## 🔌 Phase 4: Extended Integrations
{: .text-purple-300 }

Extended vector database and embedding provider integrations.
{: .text-grey-dk-000 .fs-3 }

<div class="contribute-grid">

<div class="contribute-card">
<h4>Milvus Integration</h4>
<p>Add Milvus as a vector database backend option.</p>
<div class="card-meta">
<span class="label label-p2">P2</span>
<span class="label label-medium">Medium</span>
<span class="label label-feature">Feature</span>
<span class="label label-good-first-issue">Good First Issue</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Milvus%20Integration&body=%23%23%20Goal%0AAdd%20Milvus%20as%20a%20vector%20database%20backend%20option.%0A%0A%23%23%20Difficulty%0AMedium%0A%0A%23%23%20Labels%0Afeature%2C%20good%20first%20issue%0A%0A%23%23%20Source%0A%5BRoadmap%20-%20Phase%204%5D(https%3A%2F%2Fllm4s.org%2Freference%2Froadmap%23phase-4-extended-integrations)" class="btn">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Pinecone Integration</h4>
<p>Add Pinecone as a vector database backend option.</p>
<div class="card-meta">
<span class="label label-p2">P2</span>
<span class="label label-medium">Medium</span>
<span class="label label-feature">Feature</span>
<span class="label label-good-first-issue">Good First Issue</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Pinecone%20Integration&body=%23%23%20Goal%0AAdd%20Pinecone%20as%20a%20vector%20database%20backend%20option.%0A%0A%23%23%20Difficulty%0AMedium%0A%0A%23%23%20Labels%0Afeature%2C%20good%20first%20issue%0A%0A%23%23%20Source%0A%5BRoadmap%20-%20Phase%204%5D(https%3A%2F%2Fllm4s.org%2Freference%2Froadmap%23phase-4-extended-integrations)" class="btn">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Cohere Embeddings</h4>
<p>Add Cohere as an embedding provider option.</p>
<div class="card-meta">
<span class="label label-p2">P2</span>
<span class="label label-easy">Easy</span>
<span class="label label-feature">Feature</span>
<span class="label label-good-first-issue">Good First Issue</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Cohere%20Embeddings&body=%23%23%20Goal%0AAdd%20Cohere%20as%20an%20embedding%20provider%20option.%0A%0A%23%23%20Difficulty%0AEasy%0A%0A%23%23%20Labels%0Afeature%2C%20good%20first%20issue%0A%0A%23%23%20Source%0A%5BRoadmap%20-%20Phase%204%5D(https%3A%2F%2Fllm4s.org%2Freference%2Froadmap%23phase-4-extended-integrations)" class="btn">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Embedding Cache</h4>
<p>Caching layer for embeddings to reduce API costs.</p>
<div class="card-meta">
<span class="label label-p2">P2</span>
<span class="label label-easy">Easy</span>
<span class="label label-feature">Feature</span>
<span class="label label-good-first-issue">Good First Issue</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Embedding%20Cache&body=%23%23%20Goal%0ACaching%20layer%20for%20embeddings%20to%20reduce%20API%20costs.%0A%0A%23%23%20Difficulty%0AEasy%0A%0A%23%23%20Labels%0Afeature%2C%20good%20first%20issue%0A%0A%23%23%20Source%0A%5BRoadmap%20-%20Phase%204%5D(https%3A%2F%2Fllm4s.org%2Freference%2Froadmap%23phase-4-extended-integrations)" class="btn">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Metadata Extraction</h4>
<p>Automatic metadata extraction from documents.</p>
<div class="card-meta">
<span class="label label-p2">P2</span>
<span class="label label-medium">Medium</span>
<span class="label label-feature">Feature</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Metadata%20Extraction&body=%23%23%20Goal%0AAutomatic%20metadata%20extraction%20from%20documents.%0A%0A%23%23%20Difficulty%0AMedium%0A%0A%23%23%20Labels%0Afeature%0A%0A%23%23%20Source%0A%5BRoadmap%20-%20Phase%204%5D(https%3A%2F%2Fllm4s.org%2Freference%2Froadmap%23phase-4-extended-integrations)" class="btn">Open GitHub Issue →</a>
</div>

</div>

---

## 🔮 Long Term (Post v1.0)
{: .text-purple-300 }

Future roadmap items planned after v1.0 release.
{: .text-grey-dk-000 .fs-3 }

<div class="contribute-grid">

<div class="contribute-card">
<h4>Fine-tuning APIs</h4>
<p>APIs for fine-tuning models on custom datasets.</p>
<div class="card-meta">
<span class="label label-p2">Future</span>
<span class="label label-hard">Hard</span>
<span class="label label-feature">Feature</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Fine-tuning%20APIs&body=%23%23%20Goal%0AAPIs%20for%20fine-tuning%20models%20on%20custom%20datasets.%0A%0A%23%23%20Difficulty%0AHard%0A%0A%23%23%20Labels%0Afeature%0A%0A%23%23%20Source%0A%5BRoadmap%20-%20Long%20Term%5D(https%3A%2F%2Fllm4s.org%2Freference%2Froadmap%23long-term-post-v10)" class="btn">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Workflow Engines</h4>
<p>Integration with workflow engines for complex AI pipelines.</p>
<div class="card-meta">
<span class="label label-p2">Future</span>
<span class="label label-hard">Hard</span>
<span class="label label-feature">Feature</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Workflow%20Engines&body=%23%23%20Goal%0AIntegration%20with%20workflow%20engines%20for%20complex%20AI%20pipelines.%0A%0A%23%23%20Difficulty%0AHard%0A%0A%23%23%20Labels%0Afeature%0A%0A%23%23%20Source%0A%5BRoadmap%20-%20Long%20Term%5D(https%3A%2F%2Fllm4s.org%2Freference%2Froadmap%23long-term-post-v10)" class="btn">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Plugin Architecture</h4>
<p>Extensible plugin system for custom integrations.</p>
<div class="card-meta">
<span class="label label-p2">Future</span>
<span class="label label-hard">Hard</span>
<span class="label label-feature">Feature</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Plugin%20Architecture&body=%23%23%20Goal%0AExtensible%20plugin%20system%20for%20custom%20integrations.%0A%0A%23%23%20Difficulty%0AHard%0A%0A%23%23%20Labels%0Afeature%0A%0A%23%23%20Source%0A%5BRoadmap%20-%20Long%20Term%5D(https%3A%2F%2Fllm4s.org%2Freference%2Froadmap%23long-term-post-v10)" class="btn">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Advanced Multi-Agent</h4>
<p>Advanced multi-agent orchestration capabilities.</p>
<div class="card-meta">
<span class="label label-p2">Future</span>
<span class="label label-hard">Hard</span>
<span class="label label-feature">Feature</span>
</div>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Advanced%20Multi-Agent&body=%23%23%20Goal%0AAdvanced%20multi-agent%20orchestration%20capabilities.%0A%0A%23%23%20Difficulty%0AHard%0A%0A%23%23%20Labels%0Afeature%0A%0A%23%23%20Source%0A%5BRoadmap%20-%20Long%20Term%5D(https%3A%2F%2Fllm4s.org%2Freference%2Froadmap%23long-term-post-v10)" class="btn">Open GitHub Issue →</a>
</div>

</div>

---

## ✅ Already Completed
{: .text-purple-300 }

The following features are already implemented and do not need contributions.
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
<h5>RAG Benchmarking</h5>
<p>Chunking, fusion, embedding comparison</p>
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
<h5>BM25 Keyword Index</h5>
<p>SQLite FTS5 + PostgreSQL full-text</p>
</div>
</div>

<div class="completed-card">
<span class="completed-icon">✓</span>
<div class="completed-content">
<h5>Hybrid Search Fusion</h5>
<p>RRF + weighted score strategies</p>
</div>
</div>

<div class="completed-card">
<span class="completed-icon">✓</span>
<div class="completed-content">
<h5>Reranking Pipeline</h5>
<p>Cohere + LLM-based reranking</p>
</div>
</div>

<div class="completed-card">
<span class="completed-icon">✓</span>
<div class="completed-content">
<h5>Document Chunking</h5>
<p>Simple, sentence, markdown, semantic</p>
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

<div class="completed-card">
<span class="completed-icon">✓</span>
<div class="completed-content">
<h5>RAG in a Box</h5>
<p>Production-ready RAG server (194 tests)</p>
</div>
</div>

<div class="completed-card">
<span class="completed-icon">✓</span>
<div class="completed-content">
<h5>Ollama Embeddings</h5>
<p>Local embedding support</p>
</div>
</div>

</div>

---

<div style="text-align: center; padding: 2rem; margin-top: 2rem; border-top: 1px solid var(--border-color);">
<p style="color: var(--body-text-color); margin-bottom: 1rem;">
Can't find what you're looking for? Check the <a href="../reference/roadmap">full roadmap</a> or 
</p>
<a href="https://github.com/llm4s/llm4s/issues/new" class="btn btn-primary" style="display: inline-block;">Create a Custom Issue →</a>
</div>
