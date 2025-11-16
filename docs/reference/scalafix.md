Scalafix: Ban Environment Variable Access

Overview
- Purpose: Prevent direct access to environment variables to encourage centralized configuration via `ConfigReader`.
- Enforced APIs: `sys.env` and `java.lang.System.getenv(...)`.
- Message: Violations emit the error "use ConfigReader".

What's Included
- Plugin: `sbt-scalafix` (see `project/plugins.sbt`).
- Rule: Uses `DisableSyntax` with regex to flag:
  - `sys.env`
  - `System.getenv`
  This approach is syntactic and does not require SemanticDB.

Running Scalafix
- Lint on compile: Enabled per-project.
  - Enabled: `root`, `samples`, `shared`
  - Disabled: `workspaceRunner`
  - Violations are warnings and do not fail the build.
- Manual run:
  - `sbt scalafixAll` to check all modules.
  - `sbt Test/scalafix` to check only test sources.

Violation Examples
- `val v = sys.env("API_KEY")` → warning: use ConfigReader
- `val v = System.getenv("API_KEY")` → warning: use ConfigReader

Suppressing a Finding (discouraged)
- Next line only: add `// scalafix:ok NoSysEnv` or `// scalafix:ok NoSystemGetenv`.
- Block scope: wrap with `// scalafix:off` and `// scalafix:on`.
Use sparingly and document why the exception is necessary.

Migration Guidance
- Replace env access with a typed configuration layer, for example:
  - Define a `ConfigReader` that validates and loads values (e.g., from Typesafe Config, system properties, or injected config), and use it where needed.
  - Avoid hard dependencies on the process environment in library code; prefer dependency injection.

Files Changed
- `.scalafix.conf`: Adds `DisableSyntax` regex rules with custom messages.
- `project/plugins.sbt`: Adds the `sbt-scalafix` plugin.
- `build.sbt`: Enables `scalafixOnCompile` only on `root` and `samples`.

Notes
- The rule is syntactic and works for both Scala 2.13 and Scala 3 without SemanticDB.
- If you see false negatives for unusual code paths, please open an issue with a minimal repro so we can extend the rule.
