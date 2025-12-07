# Repository Guidelines

## Project Structure & Modules
- Core framework lives in `modules/core` (agents, LLM connectors, guardrails, tracing). Workspace support sits in `modules/workspace` (runner/client/shared). Cross-version checks for Scala 2.13/3.3 live in `modules/crossTest`, and runnable demos are in `modules/samples`. `docs/` stores documentation; `hooks/` provides the pre-commit installer. Sources use `src/main/scala` plus `src/main/scala-2.13` for version-specific code. See also [szork](https://github.com/llm4s/szork) - a demo game showcasing LLM4S agents.

## Build, Test, and Development Commands
- Compile: `sbt compile` or `sbt +compile` (cross-build). Run examples via `sbt "samples/runMain org.llm4s.samples.basic.BasicLLMCallingExample"`.
- Tests: `sbt test` for the active Scala, `sbt +test` for both, or module-specific runs (e.g., `sbt core/test`).
- Pipeline: `sbt buildAll` cross-compiles and tests; slower but mirrors CI.
- Formatting/linting: `sbt scalafmtAll` is required; `sbt scalafixAll` is available for extra hygiene.
- Dev hook: `./hooks/install.sh` installs a pre-commit that runs scalafmt, compile, and tests.

## Coding Style & Naming Conventions
- `.scalafmt.conf` enforces two-space indents, aligned imports/params, and trailing commas; run scalafmt instead of hand-formatting.
- Favor immutability, pure functions, and explicit types at module boundaries. Keep logging consistent with existing `org.slf4j` usage.
- Naming: types `PascalCase`, members `camelCase`, constants `SCREAMING_SNAKE_CASE`. Tests end with `Spec` or `Test` to match neighboring suites.

## Testing Guidelines
- Scalatest is primary; Scalamock is available. Integration suites often mix in `ScalaFutures` or `BeforeAndAfterAll`.
- Place tests under `src/test/scala`, mirroring the package of the code under test.
- Add cross-version specs in `modules/crossTest/scala2` and `modules/crossTest/scala3` when compiler behavior differs.
- Typical flow: `sbt test` for quick checks; `sbt +test` or `sbt buildAll` before PR. Coverage (when needed): `sbt coverage test coverageReport`.

## Commit & Pull Request Guidelines
- Use short, imperative commit subjects (e.g., “Add centralized model metadata system”) and include issue/PR refs when relevant.
- Before a PR: run scalafmtAll and tests, update docs for user-facing changes, and add sample commands/output for new workflows.
- PR text should state motivation, key changes, and validation steps. Screenshots only when altering docs/assets; otherwise keep notes concise.

## Security & Configuration Tips
- Never commit secrets. Keep API keys (e.g., `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `LLM_MODEL`, optional `OPENAI_BASE_URL`) in env vars or an untracked `.env`.
- For workspace demos, ensure Docker is running before `sbt docker:publishLocal` or workspace samples, and scrub sensitive data from logs.
