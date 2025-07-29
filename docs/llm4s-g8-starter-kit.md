llm4s.g8: sbt template for [llm4s]
=================
[![CI](https://github.com/llm4s/llm4s/actions/workflows/llm4s-g8-template.yml/badge.svg)](https://github.com/llm4s/llm4s/actions/workflows/llm4s-g8-template.yml)

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
- ✅ Supports [Scala 3] and [Scala 2]

Pre-configured prerequisites
-----------
- JDK 21+
- SBT
- OpenAI API key
- [Scala 3][Scala 3] or [Scala 2][Scala 2]
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
--version=0.1.0-SNAPSHOT \
--llm4s_version=0.1.1 \
--scala_version=2.13.16 \
--munit_version=1.1.1 \
--directory=org.llm4s.template \
--force
```
in the folder where you want to clone the template.

Template usage (with local template): to create a project
--------------
To use this template locally (e.g., during development or when unpublished),
Using `sbt`, do:
```
sbt new file:///<absolute-path>/llm4s/llm4s.g8 \
--name=llm4s-template \
--package=org.llm4s.template \
--version=0.1.0-SNAPSHOT \
--llm4s_version=0.1.1 \
--scala_version=2.13.16 \
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
    sbt run "Explain what a Monad is in scala"
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
[Scala 2]: https://www.scala-lang.org/
[logback]: https://logback.qos.ch/
[scala-logging]: https://github.com/lightbend-labs/scala-logging
[MUnit]: https://scalameta.org/munit/
[Vitthal Mirji]: https://github.com/vim89