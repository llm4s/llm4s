---
layout: default
title: Student Resources
parent: Google Summer of Code
nav_order: 2
permalink: /gsoc/resources/
---

# Student Resources

Interested in contributing to LLM4S or applying for GSoC 2026? Here is everything you need to get started.

## GSoC 2026 Aspirants - Start Here

### Canonical 2026 Docs
- **[GSoC 2026 Summary](https://github.com/llm4s/llm4s/blob/main/Google%20Summer%20of%20Code/GSOC-2026-SUMMARY.md)** - Official overview of projects, mentors, and statistics.
- **[Complete Project Ideas List](https://github.com/llm4s/llm4s/blob/main/Google%20Summer%20of%20Code/Project%20Ideas/2026.md)** - Official 75+ project ideas and mentor contacts.

### Quick Links
- **[Join Discord Community](https://discord.gg/YXSmPjDp)** - Connect with mentors and aspirants.
- **[Dev Hours Calendar](https://luma.com/calendar/cal-Zd9BLb5jbZewxLA)** - Weekly Sunday 9am London time sessions.
- **[GSoC Proposal Guide](https://google.github.io/gsocguides/student/writing-a-proposal)** - Official student proposal instructions.

### First Steps
1. [Join Discord](https://discord.gg/YXSmPjDp) and introduce yourself in `#introduce-yourself`.
2. Review the canonical 2026 summary and ideas pages above.
3. Set up your development environment.
4. Start with issues/PRs and discuss progress in community channels.
5. Attend Dev Hours consistently.

---

## How to Apply

We participate through the **Scala Center**. When applications open:

1. Pick 1-2 ideas from the canonical ideas page.
2. Contact mentors listed in those idea descriptions.
3. Join Discord discussions in `#gsoc-2026` and `#design-discussions`.
4. Write your proposal using the official GSoC guide.
5. Submit through [summerofcode.withgoogle.com](https://summerofcode.withgoogle.com/).

---

## Dev Environment Setup

### Basic Requirements
- **JDK 21+**
- **SBT**
- **Git**

### Verify Installation
```bash
java -version
sbt sbtVersion
git --version
```

### Clone and Build
```bash
git clone https://github.com/llm4s/llm4s.git
cd llm4s
sbt compile
sbt test
```

### Create Your First PR
1. Fork the repository.
2. Create a branch: `git checkout -b feature/your-feature`.
3. Make your changes.
4. Run `sbt test`.
5. Run `sbt scalafmtAll`.
6. Push and open a PR.
7. Notify maintainers in Discord `#pr-review`.

---

## Contributor Onboarding

- **Discord**: [LLM4S community](https://discord.gg/YXSmPjDp)
- **Issues**: [GitHub Issues](https://github.com/llm4s/llm4s/issues)
- **Roadmaps**: [Production Roadmap](../reference/roadmap.md), [Hardware Design Roadmap](../roadmap/llm4s-hardware-design-roadmap.md)
- **Contributing Guide**: [/contributing/](/contributing/)

### Relevant Discord Channels
- `#introduce-yourself`
- `#gsoc-2026`
- `#creating-github-issues`
- `#pr-review`
- `#design-discussions`

---

## FAQ

**Q: Do I need prior Scala experience?**  
A: Not mandatory, but helpful. Strong fundamentals and willingness to learn are expected.

**Q: Can I propose changes to an idea?**  
A: Yes. Discuss the scope first with mentors before finalizing the proposal.

**Q: What improves selection chances?**  
A: Consistent contributions, active communication, and a concrete proposal with realistic milestones.

---

Questions can be asked in Discord `#gsoc-2026`.
