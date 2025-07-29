$name$
=================

Quickstart
----------
This is a production-ready scala project pre-configured to use the [llm4s SDK](https://github.com/llm4s/llm4s).

Features
--------
- ✅ Preconfigured with `llm4s` for building AI-powered applications
- ✅ Production-ready directory layout, CI hooks, formatting, and more
- ✅ Supports `sbt test` & `sbt run` for quick CLI interaction
- ✅ Supports & suites directly from the comfort of your IDE, whether it's IntelliJ, VS Code, or any other
- ✅ Includes `Main.scala` + `PromptExecutor` for quick onboarding & getting started with [llm4s]

Pre-configured prerequisites
-----------
- JDK 21+
- SBT
- OpenAI API key
- [Scala 3][Scala 3] or [Scala 2][Scala 2]
- [MUnit] for unit testing
- [LLM4S SDK][llm4s]
- Logging library [logback][logback], [scala-logging][scala-logging]

Run the app
-----------
1. Export your OpenAI API key:
   ```bash
   export OPENAI_API_KEY=sk-xxxxxx
   ```
   You can also set this in your IDE's run configuration or use a `.env.$name$` file (Optional to use library like `dotenv-scala`.)
   ```bash
   export \$(cat ".env.$name$" | xargs)
   ```

2. Run with default or custom prompt:
   ```bash
   sbt run
   sbt run "Explain what a Monad is in Scala"
   ```

3. Format & compile (after making any changes):
   ```bash
   sbt scalafmtAll
   sbt compile
   ```
4. Running Tests: This template comes with [MUnit](https://scalameta.org/munit/) preconfigured for testing.

- Included in this setup:
  - `munit` version `$munit_version$` is added as a test dependency in `build.sbt`. 
  - Sample test is located in: `src/test/scala/MainSpec.scala` that tests the `PromptExecutor` functionality.

- To run tests:
  - Use SBT:
  - ```bash 
    sbt test
    ```
1. Development:
    - Add your own prompts in `Main.scala`
    - Implement additional functionality in `PromptExecutor.scala`
    - Write more tests in `MainSpec.scala`

2. CI
    - The template includes a GitHub Actions workflow for CI.
    - It runs tests and checks formatting on every push and pull request.

----------------
Written in July 2025 by [Vitthal Mirji]

[g8]: http://www.foundweekends.org/giter8/
[llm4s]: https://github.com/llm4s/llm4s
[Scala 3]: https://www.scala-lang.org/
[Scala 2]: https://www.scala-lang.org/
[logback]: https://logback.qos.ch/
[scala-logging]: https://github.com/lightbend-labs/scala-logging
[MUnit]: https://scalameta.org/munit/
[Vitthal Mirji]: https://github.com/vim89
