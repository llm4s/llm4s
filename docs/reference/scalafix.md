Scalafix: Configuration Boundary Enforcement

Overview
- Purpose: Keep configuration loading at the application edge and discourage direct exception handling in favour of `Result[A]`.
- Enforcement is global; legitimate exemptions are opt-in via `// scalafix:off` markers in source files.

Rules enforced (`.scalafix.conf`, `DisableSyntax.regex`)
- `NoConfigFactory` - bans `ConfigFactory` references.
- `NoSysEnv` - bans `sys.env` references.
- `NoSystemGetenv` - bans `System.getenv` references.
- `NoPureConfigDefault` - bans `ConfigSource.default` references.
- `NoKeywordTry` / `NoKeywordCatch` / `NoKeywordFinally` - prefer `Try(...).toResult` and `Result[A]` combinators.

Configuration shape
- All rules live under the canonical top-level path `DisableSyntax.regex`. scalafix only reads its regex list from this exact path; custom-named subblocks like `DisableSyntax.MyRules { regex = [...] }` are silently ignored. `ScalafixConfigurationBoundarySpec` enforces this structurally.
- scalafix's `DisableSyntax` rule has no `excludePackages` or per-rule `fileFilter`. To exempt a file, use a `// scalafix:off` marker (optionally with a rule id) in the source.

Plugin and rule type
- Plugin: `sbt-scalafix` (see `project/plugins.sbt`).
- Rule type: syntactic regex (no SemanticDB required, works on Scala 2.13 and 3).

Running Scalafix
- Compile-time enforcement: enabled in CI only (`scalafixOnCompile := sys.env.getOrElse("CI", "false").toBoolean`).
- Manual run:
  - `sbt scalafixAll` to lint every module.
  - `sbt core/scalafix` to lint just core main sources.

Suppressing a finding
- File-wide: put `// scalafix:off` (silences all rules) or `// scalafix:off DisableSyntax.NoSysEnv` (silences one) at the top of the file, before the `package` declaration.
- Block: wrap the region with `// scalafix:off RuleId` / `// scalafix:on RuleId`.
- Use sparingly; prefer fixing the violation. App-edge code (samples, workspace, the `org.llm4s.config` package) is the most common legitimate exemption.

Migration guidance
- Load configuration at the app/test edge via `Llm4sConfig`.
- Inject typed settings into core services rather than reading env vars or `ConfigSource.default` from library code.
- Replace `try/catch` with `Try(...).toResult` and combinators on `Result[A]`.

Notes
- CI and pre-commit run scalafix checks.
- If you hit a false positive (e.g. a doc comment containing a banned token), prefer a `// scalafix:off DisableSyntax.<RuleId>` over loosening the regex.
