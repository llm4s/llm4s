---
layout: page
title: Installation
parent: Getting Started
nav_order: 1
---

# Installation
{: .no_toc }

Get LLM4S up and running in minutes.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Prerequisites

Before installing LLM4S, ensure you have:

- **Java Development Kit (JDK) 11 or higher** (JDK 21 recommended)
- **Scala 2.13.16** or **Scala 3.7.1** (or both for cross-compilation)
- **SBT 1.10.6** or higher
- An API key from at least one LLM provider (OpenAI, Anthropic, Azure OpenAI, or Ollama)

### Verify Prerequisites

```bash
# Check Java version
java -version  # Should show 11 or higher

# Check Scala version
scala -version  # 2.13.16 or 3.7.1

# Check SBT version
sbt version  # 1.10.6 or higher
```

---

## Add LLM4S to Your Project

### SBT

Add LLM4S to your `build.sbt`:

```scala
// For Scala 2.13 or 3.x
libraryDependencies += "org.llm4s" %% "core" % "0.1.16"

// Cross-compile for both versions
ThisBuild / scalaVersion := "2.13.16"  // or "3.7.1"
```

### Maven

```xml
<!-- For Scala 3 -->
<dependency>
    <groupId>org.llm4s</groupId>
    <artifactId>core_3</artifactId>
    <version>0.1.16</version>
</dependency>

<!-- For Scala 2.13 -->
<dependency>
    <groupId>org.llm4s</groupId>
    <artifactId>core_2.13</artifactId>
    <version>0.1.16</version>
</dependency>
```

### Multi-Module Project

If you have a multi-module project:

```scala
lazy val myProject = (project in file("."))
  .settings(
    name := "my-llm-project",
    scalaVersion := "2.13.16",
    libraryDependencies ++= Seq(
      "org.llm4s" %% "core" % "0.1.16"
    )
  )
```

### Snapshot Versions

To use the latest development snapshot:

```scala
resolvers += Resolver.sonatypeRepo("snapshots")
libraryDependencies += "org.llm4s" %% "core" % "0.1.0-SNAPSHOT"
```

---

## Quick Start with the Starter Kit

The fastest way to get started is using the **llm4s.g8** template:

```bash
# Install the template
sbt new llm4s/llm4s.g8

# Follow the prompts
# name [My LLM Project]: my-awesome-agent
# organization [com.example]: com.mycompany
# scala_version [2.13.16]:
# llm4s_version [0.1.16]:

cd my-awesome-agent
sbt run
```

The starter kit includes:

- ✅ Pre-configured SBT build
- ✅ Example agent with tool calling
- ✅ Configuration templates
- ✅ Multi-provider setup
- ✅ Docker configuration for workspace

[View the starter kit →](https://github.com/llm4s/llm4s.g8)

---

## Optional Dependencies

{: .note }
> Additional modules are coming soon. The core library includes most functionality.
> Check [Maven Central](https://central.sonatype.com/namespace/org.llm4s) for available artifacts.

### For Workspace (Containerized Execution)

```scala
libraryDependencies += "org.llm4s" %% "workspaceClient" % "0.1.16"
```

And install Docker:

```bash
# macOS
brew install docker

# Ubuntu/Debian
sudo apt-get install docker.io

# Verify
docker --version
```

---

## API Keys Setup

LLM4S requires API keys for your chosen provider(s). You can configure these via:

1. **Environment variables** (recommended)
2. **Configuration files** (`application.conf`)
3. **System properties** (`-D` flags)

### Environment Variables

Create a `.env` file in your project root (add to `.gitignore`!):

```bash
# Choose your provider
LLM_MODEL=openai/gpt-4o

# OpenAI
OPENAI_API_KEY=sk-proj-...
OPENAI_BASE_URL=https://api.openai.com/v1  # Optional

# Anthropic
ANTHROPIC_API_KEY=sk-ant-...
ANTHROPIC_BASE_URL=https://api.anthropic.com  # Optional

# Azure OpenAI
AZURE_API_KEY=your-azure-key
AZURE_API_BASE=https://your-resource.openai.azure.com
AZURE_DEPLOYMENT_NAME=gpt-4o

# Ollama (local)
OLLAMA_BASE_URL=http://localhost:11434

# Cohere
COHERE_API_KEY=your-cohere-api-key
COHERE_BASE_URL=https://api.cohere.com  # Optional
```

Load the `.env` file before running:

```bash
source .env
sbt run
```

Or use `sbt-dotenv` plugin:

```scala
// project/plugins.sbt
addSbtPlugin("au.com.onegeek" %% "sbt-dotenv" % "2.1.233")
```

### Get API Keys

#### OpenAI

1. Go to [platform.openai.com](https://platform.openai.com)
2. Sign up or log in
3. Navigate to **API Keys**
4. Click **Create new secret key**
5. Copy the key (starts with `sk-`)

#### Anthropic

1. Go to [console.anthropic.com](https://console.anthropic.com)
2. Sign up or log in
3. Navigate to **API Keys**
4. Click **Create Key**
5. Copy the key (starts with `sk-ant-`)

#### Azure OpenAI

1. Create an Azure account
2. Navigate to **Azure OpenAI Service**
3. Create a resource
4. Deploy a model (e.g., gpt-4o)
5. Copy the **API Key** and **Endpoint**

#### Ollama (Local)

1. Install Ollama: [ollama.com](https://ollama.com)
2. Pull a model: `ollama pull llama2`
3. Start server: `ollama serve`
4. No API key needed!

---

## Verify Installation

Create a simple test file `VerifyInstall.scala`:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.UserMessage

object VerifyInstall extends App {
  println("Testing LLM4S installation...")

  val result = for {
    providerConfig <- Llm4sConfig.provider()
    client <- LLMConnect.getClient(providerConfig)
    response <- client.complete(
      messages = List(UserMessage("Say 'LLM4S is working!'")),
      model = None
    )
  } yield response

  result match {
    case Right(completion) =>
      println("✅ Success!")
      println(s"Response: ${completion.content}")
    case Left(error) =>
      println("❌ Error:")
      println(error)
  }
}
```

Run it:

```bash
sbt run
```

Expected output:

```
Testing LLM4S installation...
✅ Success!
Response: LLM4S is working!
```

---

## Troubleshooting

### "API key not found"

**Problem**: LLM4S can't find your API key.

**Solution**:
1. Verify `.env` file exists and is in project root
2. Check you've sourced it: `source .env`
3. Verify variable name matches your provider (e.g., `OPENAI_API_KEY`)
4. Check for typos in the key

### "Provider not supported"

**Problem**: Invalid `LLM_MODEL` format.

**Solution**: Use the correct format:
- OpenAI: `openai/gpt-4o`
- Anthropic: `anthropic/claude-sonnet-4-5-latest`
- Azure: `azure/gpt-4o`
- Ollama: `ollama/llama2`

### Compilation Errors

**Problem**: Scala version mismatch.

**Solution**:
```bash
# Clean and recompile
sbt clean
sbt compile
```

### Dependency Resolution Issues

**Problem**: Can't resolve LLM4S dependency.

**Solution**:
- For release versions, no additional resolver needed (uses Maven Central)
- For snapshots, add the resolver:
```scala
resolvers += Resolver.sonatypeRepo("snapshots")
```

---

## Next Steps

Now that LLM4S is installed:

1. **[Write your first program →](first-example)** - Create a simple LLM application
2. **[Configure providers →](configuration)** - Set up multiple LLM providers
3. **[Explore examples →](/examples/)** - Browse 69 working examples

---

## Additional Resources

- **GitHub Repository**: [llm4s/llm4s](https://github.com/llm4s/llm4s)
- **Starter Kit**: [llm4s.g8](https://github.com/llm4s/llm4s.g8)
- **Discord Community**: [Join us](https://discord.gg/4uvTPn6qww)
- **API Reference**: [Core API](/api/)

---

**Installation complete!** Ready to [write your first program →](first-example)
