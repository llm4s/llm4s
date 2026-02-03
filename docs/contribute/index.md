---
layout: page
title: Contribute
nav_order: 9
---

# Contribute to LLM4S
{: .fs-9 }

Welcome to the LLM4S Contribute page.
{: .fs-6 .fw-300 }

Here you'll find beginner friendly and high impact issues you can work on to improve testing, documentation, performance, security, observability, and community experience in LLM4S.

---

## 🧪 Testing & Quality

<div class="contribute-grid" markdown="1">

<div class="contribute-card">
<h4>Increase Unit Test Coverage to 80%+ Across Core Modules</h4>
<p>Add missing unit tests across llmconnect, agent, toolapi, config, and trace modules to reach production-level coverage.</p>
<span class="label label-testing">Testing</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Increase%20Unit%20Test%20Coverage%20to%2080%25%2B%20Across%20Core%20Modules" class="btn btn-primary">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Create Integration Test Framework</h4>
<p>Introduce integration tests using test containers and mocked LLM providers to validate cross-module workflows.</p>
<span class="label label-testing">Testing</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Create%20Integration%20Test%20Framework" class="btn btn-primary">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Add End-to-End Reference Scenario Tests</h4>
<p>Test full user workflows like chatbot, RAG document Q&A, and multi-agent execution.</p>
<span class="label label-testing">Testing</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Add%20End-to-End%20Reference%20Scenario%20Tests" class="btn btn-primary">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Enable Coverage Enforcement in CI</h4>
<p>Fail CI builds when test coverage drops below a defined minimum threshold.</p>
<span class="label label-testing">Testing</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Enable%20Coverage%20Enforcement%20in%20CI" class="btn btn-primary">Open GitHub Issue →</a>
</div>

</div>

---

## 📦 API Stability

<div class="contribute-grid" markdown="1">

<div class="contribute-card">
<h4>Document Public API Surface</h4>
<p>Identify and document all public APIs and clearly mark internal-only interfaces.</p>
<span class="label label-api">API</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Document%20Public%20API%20Surface" class="btn btn-primary">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Define Versioning and SemVer Policy</h4>
<p>Create a clear versioning policy explaining binary compatibility and breaking change rules.</p>
<span class="label label-api">API</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Define%20Versioning%20and%20SemVer%20Policy" class="btn btn-primary">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Deprecation Cleanup for 0.x APIs</h4>
<p>Audit deprecated APIs, add annotations, and document migration paths.</p>
<span class="label label-api">API</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Deprecation%20Cleanup%20for%200.x%20APIs" class="btn btn-primary">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>API Freeze for 1.0 Release Candidates</h4>
<p>Freeze public APIs before RC1 and allow only bug fixes.</p>
<span class="label label-api">API</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=API%20Freeze%20for%201.0%20Release%20Candidates" class="btn btn-primary">Open GitHub Issue →</a>
</div>

</div>

---

## 🔍 RAG & Retrieval

<div class="contribute-grid" markdown="1">

<div class="contribute-card">
<h4>RAG Vector Database Integrations</h4>
<p>Integrate pgvector, Qdrant, and Weaviate for production-ready vector storage.</p>
<span class="label label-feature">Feature</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=RAG%20Vector%20Database%20Integrations" class="btn btn-primary">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Document Chunking Strategies</h4>
<p>Implement smart chunking strategies for PDFs, markdown, and large documents.</p>
<span class="label label-feature">Feature</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Document%20Chunking%20Strategies" class="btn btn-primary">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Semantic / Hybrid Search</h4>
<p>Combine keyword and vector search for improved retrieval accuracy.</p>
<span class="label label-feature">Feature</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Semantic%20%2F%20Hybrid%20Search" class="btn btn-primary">Open GitHub Issue →</a>
</div>

</div>

---

## 🤖 Agent Framework

<div class="contribute-grid" markdown="1">

<div class="contribute-card">
<h4>Agent State Persistence</h4>
<p>Enable saving and restoring agent state for long-running workflows.</p>
<span class="label label-feature">Feature</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Agent%20State%20Persistence" class="btn btn-primary">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Multi-Agent Communication</h4>
<p>Allow agents to communicate, delegate, and coordinate tasks.</p>
<span class="label label-feature">Feature</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Multi-Agent%20Communication" class="btn btn-primary">Open GitHub Issue →</a>
</div>

</div>

---

## ⚡ Performance

<div class="contribute-grid" markdown="1">

<div class="contribute-card">
<h4>Introduce JMH Benchmarking Framework</h4>
<p>Measure latency, throughput, tool overhead, and RAG performance.</p>
<span class="label label-performance">Performance</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Introduce%20JMH%20Benchmarking%20Framework" class="btn btn-primary">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Establish Performance Baselines</h4>
<p>Document baseline metrics for the 1.0 release.</p>
<span class="label label-performance">Performance</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Establish%20Performance%20Baselines" class="btn btn-primary">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Memory Profiling and Optimization</h4>
<p>Identify memory leaks and ensure proper resource cleanup.</p>
<span class="label label-performance">Performance</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Memory%20Profiling%20and%20Optimization" class="btn btn-primary">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Scalability and Load Testing</h4>
<p>Validate concurrent request handling and horizontal scaling behavior.</p>
<span class="label label-performance">Performance</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Scalability%20and%20Load%20Testing" class="btn btn-primary">Open GitHub Issue →</a>
</div>

</div>

---

## 🔐 Security

<div class="contribute-grid" markdown="1">

<div class="contribute-card">
<h4>Conduct Formal Security Audit</h4>
<p>Review code for vulnerabilities, secret handling, and container isolation.</p>
<span class="label label-security">Security</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Conduct%20Formal%20Security%20Audit" class="btn btn-primary">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Integrate Dependency Vulnerability Scanning</h4>
<p>Enable Dependabot or Snyk for automated vulnerability alerts.</p>
<span class="label label-security">Security</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Integrate%20Dependency%20Vulnerability%20Scanning" class="btn btn-primary">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Secrets Management Guidelines</h4>
<p>Document best practices for API keys, env vars, and vault usage.</p>
<span class="label label-security">Security</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Secrets%20Management%20Guidelines" class="btn btn-primary">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Create SECURITY.md and Disclosure Process</h4>
<p>Define responsible vulnerability reporting procedures.</p>
<span class="label label-security">Security</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Create%20SECURITY.md%20and%20Disclosure%20Process" class="btn btn-primary">Open GitHub Issue →</a>
</div>

</div>

---

## 📚 Documentation

<div class="contribute-grid" markdown="1">

<div class="contribute-card">
<h4>Documentation Audit and Gap Analysis</h4>
<p>Review all existing docs and identify missing or outdated sections.</p>
<span class="label label-docs">Docs</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Documentation%20Audit%20and%20Gap%20Analysis" class="btn btn-primary">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Create Missing Core Documentation Guides</h4>
<p>Add Getting Started, Architecture, Deployment, Testing, and Troubleshooting guides.</p>
<span class="label label-docs">Docs</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Create%20Missing%20Core%20Documentation%20Guides" class="btn btn-primary">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Achieve 100% ScalaDoc Coverage</h4>
<p>Ensure all public APIs have complete ScalaDoc comments.</p>
<span class="label label-docs">Docs</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Achieve%20100%25%20ScalaDoc%20Coverage" class="btn btn-primary">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Publish API Documentation Site</h4>
<p>Generate and publish API docs using GitHub Pages.</p>
<span class="label label-docs">Docs</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Publish%20API%20Documentation%20Site" class="btn btn-primary">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Improve Contributor Onboarding</h4>
<p>Enhance CONTRIBUTING.md and starter templates for newcomers.</p>
<span class="label label-docs">Docs</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Improve%20Contributor%20Onboarding" class="btn btn-primary">Open GitHub Issue →</a>
</div>

</div>

---

## 📊 Observability

<div class="contribute-grid" markdown="1">

<div class="contribute-card">
<h4>Integrate OpenTelemetry Tracing</h4>
<p>Add distributed tracing across LLM calls, agents, and tools.</p>
<span class="label label-observability">Observability</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Integrate%20OpenTelemetry%20Tracing" class="btn btn-primary">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Expose Prometheus Metrics</h4>
<p>Export metrics for latency, token usage, error rates, and throughput.</p>
<span class="label label-observability">Observability</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Expose%20Prometheus%20Metrics" class="btn btn-primary">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Create Production Monitoring Guide</h4>
<p>Document dashboards, alerts, and operational best practices.</p>
<span class="label label-observability">Observability</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Create%20Production%20Monitoring%20Guide" class="btn btn-primary">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Add Health and Readiness Checks</h4>
<p>Expose health endpoints for production deployments.</p>
<span class="label label-observability">Observability</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Add%20Health%20and%20Readiness%20Checks" class="btn btn-primary">Open GitHub Issue →</a>
</div>

</div>

---

## 🌐 Community

<div class="contribute-grid" markdown="1">

<div class="contribute-card">
<h4>Label and Curate Good First Issues</h4>
<p>Identify beginner-friendly tasks and label them clearly.</p>
<span class="label label-community">Community</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Label%20and%20Curate%20Good%20First%20Issues" class="btn btn-primary">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Framework Integration Guides</h4>
<p>Document integrations with Akka/Pekko, ZIO, Cats Effect, and Play.</p>
<span class="label label-community">Community</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Framework%20Integration%20Guides" class="btn btn-primary">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Define Plugin Architecture</h4>
<p>Design a stable plugin SPI for community extensions.</p>
<span class="label label-community">Community</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Define%20Plugin%20Architecture" class="btn btn-primary">Open GitHub Issue →</a>
</div>

<div class="contribute-card">
<h4>Create Plugin Registry</h4>
<p>Publish a registry of verified plugins after API stabilization.</p>
<span class="label label-community">Community</span>
<a href="https://github.com/llm4s/llm4s/issues/new?title=Create%20Plugin%20Registry" class="btn btn-primary">Open GitHub Issue →</a>
</div>

</div>

---

## Get Started

Ready to contribute? Here's how:

1. **Browse the issues above** and find one that interests you
2. **Click "Open GitHub Issue →"** to create the issue in the repository
3. **Comment on the issue** to let others know you're working on it
4. **Fork the repository** and start coding
5. **Submit a pull request** when you're ready for review

Need help? Join our [Discord community](https://discord.gg/4uvTPn6qww) and ask in the `#contributing` channel.

---

<style>
.contribute-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 1.5rem;
  margin: 1.5rem 0;
}

.contribute-card {
  background: var(--body-background-color, #fff);
  border: 1px solid var(--border-color, #e1e4e8);
  border-radius: 8px;
  padding: 1.25rem;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  transition: box-shadow 0.2s ease, border-color 0.2s ease;
}

.contribute-card:hover {
  border-color: var(--link-color, #0969da);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

.contribute-card h4 {
  margin: 0;
  font-size: 1rem;
  line-height: 1.4;
  color: var(--heading-color, #24292f);
}

.contribute-card p {
  margin: 0;
  font-size: 0.875rem;
  color: var(--text-color, #57606a);
  flex-grow: 1;
}

.contribute-card .label {
  display: inline-block;
  font-size: 0.75rem;
  font-weight: 500;
  padding: 0.25rem 0.5rem;
  border-radius: 4px;
  width: fit-content;
}

.label-testing {
  background-color: #ddf4ff;
  color: #0969da;
}

.label-api {
  background-color: #fff8c5;
  color: #9a6700;
}

.label-feature {
  background-color: #dafbe1;
  color: #1a7f37;
}

.label-performance {
  background-color: #ffeff7;
  color: #bf3989;
}

.label-security {
  background-color: #ffeef0;
  color: #cf222e;
}

.label-docs {
  background-color: #f0f6fc;
  color: #0550ae;
}

.label-observability {
  background-color: #fbefff;
  color: #8250df;
}

.label-community {
  background-color: #fff1e5;
  color: #bc4c00;
}

.contribute-card .btn {
  margin-top: auto;
  text-align: center;
  font-size: 0.875rem;
}

/* Dark mode support */
@media (prefers-color-scheme: dark) {
  .contribute-card {
    background: var(--body-background-color, #0d1117);
    border-color: var(--border-color, #30363d);
  }
  
  .contribute-card h4 {
    color: var(--heading-color, #c9d1d9);
  }
  
  .contribute-card p {
    color: var(--text-color, #8b949e);
  }
  
  .label-testing {
    background-color: #0c2d6b;
    color: #79c0ff;
  }
  
  .label-api {
    background-color: #3d2e00;
    color: #d29922;
  }
  
  .label-feature {
    background-color: #0f3d14;
    color: #56d364;
  }
  
  .label-performance {
    background-color: #3d1f30;
    color: #ff7b72;
  }
  
  .label-security {
    background-color: #3d1418;
    color: #f85149;
  }
  
  .label-docs {
    background-color: #0c2d6b;
    color: #79c0ff;
  }
  
  .label-observability {
    background-color: #2d1a4f;
    color: #a371f7;
  }
  
  .label-community {
    background-color: #3d2200;
    color: #db6d28;
  }
}
</style>
