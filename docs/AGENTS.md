# Repository Guidelines

## Project Structure & Module Organization
- Core library lives in `src/main/scala` with version splits in `src/main/scala-2.13` and `src/main/scala-3`; tests in `src/test/scala`.
- `shared/`: common utilities used by multiple modules.
- `workspaceRunner/`: runnable service (main: `org.llm4s.runner.RunnerMain`); tests in `workspaceRunner/src/test/scala`.
- `samples/`: executable examples; add small, focused demos here.
- `crosstest/scala2` and `crosstest/scala3`: verify published artifacts across Scala versions.

## Build, Test, and Development Commands
- `sbt compile` / `sbt +compile` — build for current/all Scala versions.
- `sbt test` / `sbt testAll` — run tests (module/all modules incl. cross tasks).
- `sbt scalafmtAll` / `sbt scalafmtCheckAll` — format/check code.
- `sbt cov` / `sbt covReport` — run coverage and generate HTML report.
- `sbt docker:publishLocal` — build `workspaceRunner` Docker image locally.
- Run a sample: `sbt "samples/runMain org.llm4s.samples.basic.BasicLLMCallingExample"`.
- Install pre-commit checks: `./hooks/install.sh`.

## Coding Style & Naming Conventions
- Scala only; use `scalafmt` (2-space indent, max 120 cols, trailing commas preserved). Run `sbt scalafmtAll` before commit.
- `scalafix` is enabled; do not use `sys.env`/`System.getenv` (use `ConfigReader` instead). Prefer curly `for` and avoid infix operators.
- Package under `org.llm4s.<area>`; match package to directory.
- Test filenames end with `Spec.scala` or `Test.scala` and live under the corresponding module’s `src/test/scala`.

## Testing Guidelines
- Frameworks: ScalaTest (+ Scalamock where mocking is required).
- Cross-version: `sbt +test` for quick checks; `sbt testCross` or `sbt fullCrossTest` to validate published artifacts.
- Coverage: target ≥80% statement coverage (`sbt covReport`); focus on core logic and branch cases.
- Keep tests deterministic and fast; avoid network I/O—stub or mock external calls.

## Commit & Pull Request Guidelines
- Commits: concise, imperative mood; include a scoped prefix when helpful (e.g., `[shared]`, `[workspaceRunner]`, `[samples]`).
- PRs: provide a clear description, link issues, include tests, and update docs/samples when behavior changes.
- Pre-submit checklist: `sbt scalafmtCheckAll && sbt testAll` (or run the installed pre-commit hook).

## Security & Configuration Tips
- Load configuration via `ConfigReader.LLMConfig()`; never read env vars directly. Example keys: `LLM_MODEL`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `OPENAI_BASE_URL`.
- Do not log secrets; prefer `.env` locally and documented env vars for CI.

