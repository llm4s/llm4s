---
layout: page
title: Operations
description: "Run LLM4S in production: deployment, a reference architecture behind an AI gateway, data protection, secrets and API keys, monitoring and real-world application patterns."
parent: User Guide
nav_order: 700
has_children: true
---

# Operations

Running an LLM4S application in production.
{: .fs-6 .fw-300 }

- **[Production Deployment](production-deployment)** - Configuration, secrets, reliability, observability, scaling and container images
- **[Reference Architecture](reference-architecture)** - Deploy behind an AI gateway: secret injection, egress policy, failover, quotas and spend, with tested Kubernetes manifests
- **[Data Protection](data-protection)** - What personal data LLM4S sends where, what it keeps and logs, data residency and deletion
- **[Secrets and API Keys](secrets)** - Choose a key source (config, environment, key file, custom provider, Vault, AWS Secrets Manager), with caching, rotation and failure behaviour
- **[Monitoring](observability/)** - Tracing (Langfuse, OpenTelemetry), logging, metrics, health checks and cost monitoring
- **[Real-World Application Patterns](patterns/)** - Multi-agent orchestration, enterprise RAG, monitoring, scaling, error recovery and security

Related, under [Core](core): [Reliable LLM Calling](reliability) and [Cost Controls](cost-controls).
