---
layout: page
title: Scalafix Rules
description: "The Scalafix rules LLM4S enforces to keep configuration loading at the application edge and prefer Result[A] over exceptions, and how to suppress a finding."
parent: Reference
nav_order: 4
---

# Scalafix: Configuration Boundary Enforcement

## Overview
- Purpose: Keep configuration loading at the application edge and discourage direct exception handling in favour of `Result[A]`.
- Enforcement is global; legitimate exemptions are opt-in via `// scalafix:off` markers in source files.

## Rules enforced (`.scalafix.conf`, `DisableSyntax.regex`)
- `NoConfigFactory` - bans `ConfigFactory` references.
- `NoSysEnv` - bans `sys.env` references.
- `NoSystemGetenv` - bans `System.getenv` references.
- `NoPureConfigDefault` - bans `ConfigSource.default` references.
- `NoKeywordTry` / `NoKeywordCatch` / `NoKeywordFinally` - prefer `Try(...).toResult` and `Result[A]` combinators.

## Configuration shape
- All rules live under the canonical top-level path `DisableSyntax.regex`. scalafix only reads its regex list from this exact path; custom-named subblocks like `DisableSyntax.MyRules { regex = [...] }` are silently ignored. `ScalafixConfigurationBoundarySpec` enforces this structurally.
- scalafix's `DisableSyntax` rule has no `excludePackages` or per-rule `fileFilter`. To exempt a file, use a `// scalafix:off` marker (optionally with a rule id) in the source.

## Plugin and rule type
- Plugin: `sbt-scalafix` (see `project/plugins.sbt`).
- Rule type: syntactic regex (no SemanticDB required; currently run on the Scala 3.7.1 build).

## Running Scalafix
- Compile-time enforcement: enabled in CI only (`scalafixOnCompile := sys.env.getOrElse("CI", "false").toBoolean`).
- Manual run:
  - `sbt scalafixAll` to lint every module.
  - `sbt core/scalafix` to lint just core main sources.

## Suppressing a finding
- File-wide: put `// scalafix:off` (silences all rules) or `// scalafix:off DisableSyntax.NoSysEnv` (silences one) at the top of the file, before the `package` declaration.
- Block: wrap the region with `// scalafix:off RuleId` / `// scalafix:on RuleId`.
- Name only the rules the region needs, and remove the marker when the code no longer trips them: `.scalafix.conf` sets `lint.error = [UnusedScalafixSuppression]`, so a suppression that suppresses nothing fails `scalafix`. A Scala 3 brace-less `try ... catch` does not match `NoKeywordTry`/`NoKeywordCatch` (their patterns need a `{`), so it needs no marker.
- Use sparingly; prefer fixing the violation. App-edge code (samples, workspace, the `org.llm4s.config` package) is the most common legitimate exemption.

## Migration guidance
- Load configuration at the app/test edge via `Llm4sConfig`.
- Inject typed settings into core services rather than reading env vars or `ConfigSource.default` from library code.
- Replace `try/catch` with `Try(...).toResult` and combinators on `Result[A]`.

## Migration rules

`modules/scalafix-rules` holds semantic Scalafix rules that rewrite *user* code across llm4s's breaking changes
([#2330](https://github.com/llm4s/llm4s/issues/2330)). They are not published yet: publishing them as
`org.llm4s::llm4s-scalafix` with the release workflow, and the one-line command in each migration guide, are the
rest of #2330.

`Llm4sMigrate_0_5` runs every rule for the [0.4.1 to 0.5.0 guide](../migrations/0-4-1-to-0-5-0):

| Rule | Rewrites |
|---|---|
| `CopyToWithSetters` | `.copy(maxTokens = Some(500), temperature = 0.2)` on a growth-prone type to `.withMaxTokens(500).withTemperature(0.2)`; `message.copy(contentOpt = Some(t))` to `message.withContent(t)` |
| `MillisToFiniteDuration` | `CrawlerConfig(delayMs = 500, timeoutMs = 30000)` to `CrawlerConfig(delay = 500.millis, timeout = 30.seconds)` (also `UrlLoader`, `HttpConfig`, `ShellConfig` and the search tool configs), `RateLimitError("openai", 1000L)` to `RateLimitError("openai", 1.second)`, and a read of `config.timeoutMs` to `config.timeout.toMillis` |
| `IsRecoverableToTotal` | `error.isRecoverable` to `LLMError.isRecoverable(error)` |
| `MovedConfigLoaders` | `Llm4sConfig.loadBraveSearchTool()` (and the DuckDuckGo and Exa loaders) to `ToolsConfigLoader`, `Llm4sConfig.metrics()` to `MetricsConfigLoader.default()`, `Llm4sConfig.pgSearchIndex()` to `PgSearchIndexConfigLoader.default()` |
| `MessageExtractorToTypePattern` | `case UserMessage(content) =>` to `case userMessage: UserMessage =>`, reading `userMessage.content` in the guard and body (also `ToolMessage(content, toolCallId)`) |
| `RemovedApis` | Lint only: the orchestration package (`PlanRunner`, `DAG`, `TypedAgent`, `CancellationToken`), `org.llm4s.types.AgentId` and `PlanId`, and `getToolDefinitionsSafe`, each with a link to the guide section |

Where a rewrite cannot be made safely, the rule reports a lint instead: a `copy` with a positional argument, a field
that has no setter any more (`Completion.thinking`, `NamedProviderConfig.organization`), or a message extractor
nested inside another pattern. A rewrite changes names, not types: a `String` that is now a `Secret`, or an epoch
`Long` that is now an `Instant`, still needs a hand edit.

The rules are compiled with scalac 2.13, the only compiler `scalafix-core` is published for; they read the SemanticDB of Scala 3 sources. Scalafix's
Scala 3 support resolves the symbol at each name, which every rule here relies on, but has no synthetics or inferred
types, so the rules match on the symbols the source names. Scala 3's SemanticDB also records no symbol for some
calls, such as the `copy` in `CompletionOptions().copy(maxTokens = Some(500))`; the rules then read the method from a
named argument's symbol (`CompletionOptions#copy().(maxTokens)`), so a positional `copy` on such a receiver is
neither rewritten nor reported.

Each rule is tested by `scalafixTests` (`sbt scalafixTests/test`): the files in `modules/scalafix-rules/input` are
compiled with SemanticDB against llm4s-core 0.4.1 from Maven Central, each rule named in a file's header is run on
it, and the result must equal the file of the same name in `modules/scalafix-rules/output`, which is compiled against
the current sources.

## Notes
- CI and pre-commit run scalafix checks.
- If you hit a false positive (e.g. a doc comment containing a banned token), prefer a `// scalafix:off DisableSyntax.<RuleId>` over loosening the regex.
