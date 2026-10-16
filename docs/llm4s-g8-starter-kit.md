llm4s.g8: sbt template for [llm4s]
=================

> **Note:** The LLM4S template has moved to its own repository at [github.com/llm4s/llm4s.g8](https://github.com/llm4s/llm4s.g8)

[![Template CI](https://github.com/llm4s/llm4s.g8/actions/workflows/template-ci.yml/badge.svg)](https://github.com/llm4s/llm4s.g8/actions/workflows/template-ci.yml)

A [Giter8][g8] template for [llm4s] project.

Quickstart
----------
This template bootstraps a production-ready Scala project pre-configured to use the [LLM4S SDK](https://github.com/llm4s/llm4s).

Features
--------
- ✅ Preconfigured with `llm4s` for building AI-powered applications
- ✅ Produces production-ready directory layout, CI hooks, formatting, and more
- ✅ Uses [giter8][g8] project creation using `sbt new`,
- ✅ Includes `Main.scala` + `PromptExecutor` for quick onboarding & getting started with [llm4s]
- ✅ Supports the current LLM4S Scala 3 setup

Pre-configured prerequisites
-----------
- JDK 21+
- SBT
- OpenAI API key
- [Scala 3][Scala 3]
- [MUnit] for unit testing
- [LLM4S SDK][llm4s]
- Logging library [logback][logback], [scala-logging][scala-logging]

# Usage

Template usage: to create a project
--------------
Using `sbt`, do:
```
sbt new llm4s/llm4s.g8 \
--name=llm4s-template \
--package=org.llm4s.template \
--version=0.1.0 \
--llm4s_version=0.5.1 \
--scala_version=3.7.1 \
--munit_version=1.1.1 \
--directory=org.llm4s.template \
--force
```
in the folder where you want to clone the template.

> **Version note:** since 0.5.0 `llm4s-core` ships no provider client, so the generated `build.sbt` needs `llm4s-core`
> and at least one provider module at the same version. The template is being moved to 0.5.x
> ([llm4s.g8#6](https://github.com/llm4s/llm4s.g8/pull/6)): that version adds `llm4s-ollama` and `llm4s-openai`
> itself and makes a local Ollama section the default. A template from before it defaults to llm4s 0.4.0 and adds
> `llm4s-core` only, so pass `--llm4s_version=0.5.1` and add the provider you use (for example `llm4s-openai` at
> 0.5.1); without it, loading the `openai-main` section fails because no module registers the `openai` provider.

Template usage (with local template): to create a project
--------------
To use this template locally (e.g., during development or when unpublished),
Using `sbt`, do:
```
sbt new file:///<absolute-path>/llm4s/llm4s.g8 \
--name=llm4s-template \
--package=org.llm4s.template \
--version=0.1.0 \
--llm4s_version=0.5.1 \
--scala_version=3.7.1 \
--munit_version=1.1.1 \
--directory=org.llm4s.template \
--force
```
Note: Do not use `~` as  ~/.. is not resolved correctly when passed to sbt new or other CLI tools — it must be expanded manually

Run the app
-----------
1. Export your OpenAI API key:
   ```bash
   export OPENAI_API_KEY=sk-xxxxxx
   ```
   `llm4s-openai` binds it to OpenAI's shared key, `llm4s.credentials.openai.apiKey`, which the
   generated project's `openai-main` named provider section in
   `src/main/resources/application.conf` uses. Make it the default with the template's own `LLM4S_PROVIDER`
   binding (`export LLM4S_PROVIDER=openai-main`) if it is not already (the 0.5.x template defaults to a local
   Ollama section, which needs no key). To use another provider,
   uncomment its section there. See the
   [configuration guide](getting-started/configuration.md#named-provider-sections).

2. Switch to the project directory:
   ```bash
   cd llm4s-template
   ls -lta
   ```
   Structure should look like:
   ```text
   ├── .github  
   │   └── workflows  
   │       └── ci.yml  
   ├── .gitignore  
   ├── .pre-commit-config.yaml  
   ├── .pre-commit-hooks.yaml  
   ├── .scalafmt.conf  
   ├── build.sbt  
   ├── project  
   │   ├── build.properties  
   │   └── plugins.sbt  
   ├── README.md  
   ├── src  
   │   ├── main  
   │   │   ├── resources  
   │   │   │   └── logback.xml  
   │   │   └── scala  
   │   │       └── org  
   │   │           └── llm4s  
   │   │               └── template  
   │   │                   ├── Main.scala  
   │   │                   └── PromptExecutor.scala  
   │   └── test  
   │       └── scala  
   │           └── org  
   │               └── llm4s  
   │                   └── template  
   │                       └── MainSpec.scala
   ```

3. Format, compile, test:
   The template integrates [MUnit] by default.
   A sample test is provided in `src/test/scala/MainSpec.scala`.
   ```bash
   sbt scalafmtAll
   sbt compile
   sbt test
   ```
4. Run with default or custom prompt:
   ```bash
    sbt run
    sbt "run Explain what a Monad is in scala"
   ```
   
5. Development:
   - Add your own prompts in `Main.scala`
   - Implement additional functionality in `PromptExecutor.scala`
   - Write more tests in `MainSpec.scala`

6. CI
   - The template includes a GitHub Actions workflow for CI.
   - It runs tests and checks formatting on every push and pull request.

---------------
Written in July 2025 by [Vitthal Mirji]

[g8]: http://www.foundweekends.org/giter8/
[llm4s]: https://github.com/llm4s/llm4s
[Scala 3]: https://www.scala-lang.org/
[logback]: https://logback.qos.ch/
[scala-logging]: https://github.com/lightbend-labs/scala-logging
[MUnit]: https://scalameta.org/munit/
[Vitthal Mirji]: https://github.com/vim89
