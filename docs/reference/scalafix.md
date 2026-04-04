Scalafix: Configuration Boundary Enforcement

Overview
- Purpose: Keep configuration loading at the application edge and prevent direct reads in core main code.
- Enforcement scope: `modules/core/src/main/scala/**` (excluding `org.llm4s.config`).
- Blocked patterns in scoped files:
  - `org.llm4s.config.Llm4sConfig`
  - `ConfigFactory`
  - `ConfigSource.default`
  - `sys.env`
  - `System.getenv`

What's Included
- Plugin: `sbt-scalafix` (see `project/plugins.sbt`).
- Rule: `DisableSyntax` with scoped regex checks in `.scalafix.conf`.
- Rule type: Syntactic checks (no SemanticDB required).

Running Scalafix
- Compile-time enforcement: Enabled in CI (`scalafixOnCompile := sys.env.getOrElse("CI", "false").toBoolean`).
- Manual run:
  - `sbt scalafixAll` to run all configured checks.
  - `sbt core/scalafix` to run only core compile checks.

Violation Examples (in core main)
- `val x = sys.env.get("OPENAI_API_KEY")`
- `val cfg = ConfigSource.default`
- `import org.llm4s.config.Llm4sConfig`

Migration Guidance
- Load configuration at the app/test edge.
- Inject typed settings into core builders/services.
- Keep `Llm4sConfig` usage in edge code (samples, CLIs, tests, config package).

Notes
- CI and pre-commit run Scalafix checks.
- If you hit a false positive, open an issue with a minimal repro.
