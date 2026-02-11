---
layout: default
title: Student Resources
parent: Google Summer of Code
nav_order: 2
permalink: /gsoc/resources/
---

# Student Resources

Interested in contributing to LLM4S or applying for GSoC 2026? Here is everything you need to get started.

## GSoC 2026 Aspirants - Start Here!

### Quick Links
- **[GSoC 2026 Ideas & Application Guide](/gsoc/2026/)** - Full guide with project ideas and step-by-step instructions
- **[Complete Project Ideas List](https://github.com/llm4s/llm4s/blob/main/Google%20Summer%20of%20Code/Project%20Ideas/2026.md)** - 75+ project ideas with full descriptions
- **[Join Discord Community](https://discord.gg/YXSmPjDp)** - Connect with mentors and other aspirants
- **[Dev Hours Calendar](https://luma.com/calendar/cal-Zd9BLb5jbZewxLA)** - Weekly Sunday 9am London time

### First Steps
- **[Join Discord](https://lnkd.in/eb4ZFdtG)** and introduce yourself in `#introduce-yourself`
2. **[Review GSoC 2026 page](/gsoc/2026/)** for detailed guidance
3. **[Setup your dev environment](#dev-environment-setup)**
4. **Start contributing** - Create issues and PRs
5. **Attend Dev Hours** - Engage with mentors and community

---

## How to Apply

We participate through the **Scala Center**. When applications open:

1. **Read the [GSoC 2026 Guide](/gsoc/2026/)** - Full overview and requirements
2. **Check the [Ideas Page](https://github.com/llm4s/llm4s/blob/main/Google%20Summer%20of%20Code/Project%20Ideas/2026.md)** - Browse all 75+ project ideas
3. **Contact mentors** - Email mentors listed in project descriptions to discuss ideas
4. **Join our Discord** - Discuss ideas with mentors in `#gsoc-2026` channel
5. **Write your proposal** - Follow [GSoC proposal writing guide](https://google.github.io/gsocguides/student/writing-a-proposal)
6. **Submit your proposal** - Through the official GSoC website when applications open

---

## Dev Environment Setup

### Basic Requirements
- **JDK 21+** - Java Development Kit (required for Scala compilation)
- **SBT** - Scala Build Tool (Scala version manager and build system)
- **Git** - For cloning and submitting PRs

### Installation Steps

**On macOS (with Homebrew):**
```bash
brew install openjdk@21
brew install sbt
brew install git
```

**On Ubuntu/Debian:**
```bash
sudo apt-get install openjdk-21-jdk
echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | sudo tee /etc/apt/sources.list.d/sbt.list
curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84ECB3F3E3E46C57E2D6E4627" | sudo apt-key add
sudo apt-get update
sudo apt-get install sbt
apt-get install git-all
```

**On Windows:**
- Download [JDK 21](https://www.oracle.com/java/technologies/downloads/)
- Download [SBT](https://www.scala-sbt.org/download.html)
- Download [Git for Windows](https://git-scm.com/download/win)

### Verify Installation
```bash
java -version          # Should show JDK 21+
sbt sbtVersion        # Should show SBT version
git --version         # Should show Git version
```

---

## Getting Your Hands Dirty

### Clone and Build
```bash
git clone https://github.com/llm4s/llm4s.git
cd llm4s
sbt compile            # Compile core modules
sbt test              # Run test suite
```

### Find Issues to Work On
- **Good First Issues**: [GitHub Issues - label:good-first-issue](https://github.com/llm4s/llm4s/issues?q=is%3Aopen+label%3A%22good+first+issue%22)
- **Help Wanted**: [GitHub Issues - label:help-wanted](https://github.com/llm4s/llm4s/issues?q=is%3Aopen+label%3A%22help+wanted%22)
- **Browse Roadmap**: Check [Production Roadmap](../reference/roadmap.md) and [Hardware Design Roadmap](../roadmap/llm4s-hardware-design-roadmap.md)

### Create Your First PR
1. Fork the repository
2. Create a feature branch: `git checkout -b feature/your-feature`
3. Make your changes
4. Run tests: `sbt test`
5. Format code: `sbt scalafmtAll`
6. Commit and push
7. Create a Pull Request
8. Notify maintainers in Discord `#pr-review` channel

---

## Onboarding for Contributors

If you have been selected or want to start contributing now:

### Join the Community
- **[LLM4S Discord](https://lnkd.in/eb4ZFdtG)** - Primary hub for discussion
  - Channels: `#introduce-yourself`, `#gsoc-2026`, `#design-discussions`
- **[GitHub Issues](https://github.com/llm4s/llm4s/issues)** - Find "good first issues"
- **[Dev Hours](https://lnkd.in/e722aSVt)** - Weekly mentorship meetings (Sundays 9am London time)

### Dev Environment Setup
- Install JDK 21+ and SBT (see [Dev Environment Setup](#dev-environment-setup) above)
- Clone: `git clone https://github.com/llm4s/llm4s.git`
- Build: `cd llm4s && sbt compile`
- Run tests: `sbt test`
- Check the [Contributing Guide](/contributing/)

### Understanding the Codebase
- **Core Framework**: [modules/core](https://github.com/llm4s/llm4s/tree/main/modules/core)
- **Workspace Support**: [modules/workspace](https://github.com/llm4s/llm4s/tree/main/modules/workspace)
- **Documentation**: [docs/](https://github.com/llm4s/llm4s/tree/main/docs)
- **Samples**: [modules/samples](https://github.com/llm4s/llm4s/tree/main/modules/samples)

---

## Learning Resources

### Understanding LLM4S
- **[Official Documentation](https://llm4s.org/)** - Complete API and usage guide
- **[GitHub Repository](https://github.com/llm4s/llm4s)** - Source code and examples
- **GSoC Project Pages**:
  - **[2025 Projects](/gsoc/2025/)** - Learn from completed projects
  - **[2026 Ideas](/gsoc/2026/)** - Project descriptions and requirements
- **Development Guides**:
  - **[API Design](../reference/)** - Architecture and design principles
  - **[Testing](../getting-started/testing-guide.md)** - How to write tests

### Learning Scala
- **[Scala Book](https://docs.scala-lang.org/tutorials/)** - Official Scala tutorials
- **[Scala 3 Docs](https://docs.scala-lang.org/scala3/)** - Scala 3 documentation
- **[Functional Programming in Scala](https://www.manning.com/books/functional-programming-in-scala)** - Authoritative book

### Understanding LLMs & AI
- **[OpenAI Documentation](https://platform.openai.com/docs/)** - LLM APIs and models
- **[Retrieval-Augmented Generation (RAG)](https://arxiv.org/abs/2005.11401)** - Research paper
- **[Agents & Tool Use](https://arxiv.org/abs/2402.01539)** - Agent research and concepts

---

## Communication Channels

### Discord Channels by Purpose

| Channel | Purpose |
|---------|---------|
| `#introduce-yourself` | New member introductions |
| `#gsoc-2026` | GSoC 2026 applications and discussions |
| `#design-discussions` | Architecture and design decisions |

### Contacting Maintainers

- **GSoC Org Admin**: Kannupriya Kalra
  - Email: [kannupriyakalra@gmail.com](mailto:kannupriyakalra@gmail.com)
  - LinkedIn: [linkedin.com/in/kannupriyakalra](https://www.linkedin.com/in/kannupriyakalra/)
  - Discord: `kannupriyakalra_46520`

- **Mentors**: Email addresses provided in individual [project ideas](https://github.com/llm4s/llm4s/blob/main/Google%20Summer%20of%20Code/Project%20Ideas/2026.md)

---

## Useful Links

### Official Resources
- **[Official GSoC Website](https://summerofcode.withgoogle.com/)** - Program rules and timeline
- **[Scala Center GSoC Page](https://scala-lang.org/gsoc/)** - Organization information
- **[GSoC Guides for Students](https://google.github.io/gsocguides/student/)** - Tips and best practices

### Project Links
- **[LLM4S GitHub Repository](https://github.com/llm4s/llm4s)** - Source code
- **[Project Ideas - 2026](https://github.com/llm4s/llm4s/blob/main/Google%20Summer%20of%20Code/Project%20Ideas/2026.md)** - All 75+ project ideas
- **[Project Ideas - 2025](https://github.com/llm4s/llm4s/blob/main/Google%20Summer%20of%20Code/Project%20Ideas/2025.md)** - Previous year's ideas for reference

### Community
- **[LLM4S Discord](https://lnkd.in/eb4ZFdtG)** - Community hub
- **[Dev Hours](https://lnkd.in/e722aSVt)** - Weekly mentorship meetings (Sundays 9am London time)

---

## FAQ

**Q: Do I need prior Scala experience?**  
A: Not required, but helpful. Basic programming knowledge in any functional language is beneficial. We have resources to help you learn.

**Q: Can I apply from outside India/specific countries?**  
A: Yes! GSoC is open to students worldwide. Check the [official GSoC eligibility page](https://summerofcode.withgoogle.com/rules) for requirements.

**Q: How many hours per week are expected?**  
A: Typically 30-40 hours/week for 350-hour projects, or 15-20 hours/week for 175-hour projects. Schedule flexibility exists with mentor agreement.

**Q: Can I modify a project idea?**  
A: Absolutely! Contact the mentor to discuss variations. Custom ideas are welcome if they align with the project roadmap.

**Q: What if I get stuck during coding?**  
A: Discord is your best friend. Ask in `#gsoc-2026-aspirants` or directly message your mentor. We encourage collaboration!

---

*Questions? Ask in Discord `#gsoc-2026` channel. We're here to help!*
