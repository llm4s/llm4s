# Changelog

All notable changes to llm4s are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- `Neo4jGraphStore.traverse` never worked against a real Neo4j. The variable-length pattern
  was built from a non-interpolated string, so `$maxDepth` reached Cypher as a parameter
  reference, which is illegal in a `MATCH` pattern ("Parameter maps cannot be used in MATCH
  patterns") and failed every traversal. It is now a breadth-first expansion of one level per
  query, which also avoids the path enumeration a variable-length pattern implies: `[*0..]`
  matches every relationship-unique path before `min(length(p))` reduces them to one row per
  node, which grows exponentially on a cyclic or dense graph - and unbounded is the default
  depth. Semantics follow `GraphTraversal.bfs`, which backs the in-memory store.
- `QdrantVectorStore` could not store a record whose ID was not already a UUID. Qdrant accepts
  only an unsigned integer or a UUID as a point ID and rejected everything else with
  `"test-1" is not a valid point ID`, so `upsert`, `get`, `delete` and `search` all failed.
  Non-UUID IDs are now mapped to a UUID derived from the ID, with the record's own ID carried
  in the payload and read back from there; UUID IDs are unchanged. Derived IDs are version 8
  UUIDs (RFC 9562's "custom" space) and an ID that is itself a version 8 UUID is derived from
  rather than passed through, so a caller cannot land two records on one point by supplying
  the UUID that another ID maps to. A mocked-HTTP unit test had been asserting the broken
  request shape, which is why nothing caught this.
- `QdrantVectorStore` reported absence as failure. Qdrant answers 404 both for a point that
  does not exist and for a collection that does not exist - and the collection is created
  lazily on first upsert and deleted outright by `clear()` - so `get` returned a `Left`
  instead of `Right(None)`, and `count`, `list`, `search` and `stats` failed on an empty
  store rather than reporting it empty. Reads now treat 404 as empty; writes still error.
- 11 of the 18 integration suites in `modules/it` were executed by nothing - not locally, not
  in CI, not on release - because the tier aliases named two `testOnly` patterns and every
  suite outside them matched nothing. Tier membership is now declared per suite by class
  annotation (`@Local`, `@Docker`, `@Workspace`, `@Ollama`, `@Cloud` in `org.llm4s.it.tags`)
  and `sbt it/itTierCheck` fails the build when a suite declares none or more than one.
  ([#1143](https://github.com/llm4s/llm4s/issues/1143))
- Suites no longer report a pass when their dependency is absent. `Tier.require` cancels
  locally and, under `LLM4S_IT_STRICT=true` (set by every tier's CI job), fails - so a
  service that did not start is visible instead of green. This also exposes that
  `GeminiSmokeSpec` reads `GEMINI_API_KEY` while the cloud job only passed `GOOGLE_API_KEY`;
  the workflow now passes both, plus `COHERE_API_KEY`.
- `ContainerisedWorkspaceTest` pinned a `workspace-runner:0.1.0-SNAPSHOT` image tag that the
  build stopped producing long ago. The tag now comes from the build itself.

### Added
- `sbt testIntegration` runs the containerised tier (pgvector, Qdrant, Neo4j) and a CI job
  runs it on **every PR** with those services as service containers - the suites covering
  pgvector, the Postgres keyword index, Qdrant, permission-aware RAG, Postgres-backed agent
  memory and Neo4j now have execution signal ahead of the modularisation carve
  ([#1126](https://github.com/llm4s/llm4s/issues/1126)), which moves most of that code.
- `sbt testWorkspace` runs the containerised workspace tier, in a CI job on pushes to `main`
  that first builds the `workspace-runner` image.

### Changed
- `modules/it` joined the root aggregate, so it is compiled, formatted and linted with every
  other module; it had been outside it, where its suites could stop compiling unnoticed.
  Default `sbt test` runs only its `@Local` tier, so the dependency-free tier stays fast.

## [0.4.0] - 2026-08-29

### Changed
- **Breaking (coordinates only):** every published artifact was renamed to carry an `llm4s-`
  prefix in consistent kebab-case. No API, package or source changes accompany the rename.

  | Old coordinate | New coordinate |
  |---|---|
  | `org.llm4s:core` | `org.llm4s:llm4s-core` |
  | `org.llm4s:workspaceshared` | `org.llm4s:llm4s-workspace-shared` |
  | `org.llm4s:workspaceclient` | `org.llm4s:llm4s-workspace-client` |
  | `org.llm4s:trace-opentelemetry` | `org.llm4s:llm4s-observability-otel` |
  | `org.llm4s:knowledgegraph-neo4j` | `org.llm4s:llm4s-knowledgegraph-neo4j` |

  The rename also escapes the accidental `2.1.593` version mis-published under `core_3` /
  `core_2.13`, which cannot be retracted from Maven Central and sorts as "latest" in some
  resolvers. `0.3.4` and earlier remain published under the old coordinates, so no existing
  build breaks until it upgrades. See
  [docs/reference/migration.md](docs/reference/migration.md#artifact-coordinate-rename-v040).
- Unpublished modules (`samples`, `workspaceRunner`, `workspaceSamples`, `it`, `benchmarks`)
  had their `name` values aligned to the same convention; they set `publish / skip := true`,
  so this has no downstream effect.

### Docs
- Updated every published coordinate in the docs, including the Maven Central badge and the
  Maven `<artifactId>` snippet, to the new names.

## [0.3.4] - 2026-06-19

### Changed
- Updated build dependencies; fixed Docker image `apt-key` removal

### Docs
- Added ScalaDoc for `LLMCompressor` and `ContextManager`
- Clarified that `LLMCompressor` cost is charged per digest, not per over-cap message
- Aligned production-readiness documentation

## [0.3.3] - 2026-06-14

### Added
- SSE transport (2024-11-05 spec) and bearer-token authentication for `MCPServer`
- Image generation with integrated cost tracking and metrics
- LLM-driven memory entity extraction
- Streaming chat-TUI sample built on termflow

### Changed
- Enabled Scalafix configuration-boundary enforcement across the codebase

### Security
- Redact API keys from provider error messages
- Added Dependabot and published a threat model
- Constant-time MCP auth comparison, public-bind guard, and bounded connection pool

### Fixed
- ReDoS vulnerability in regex handling (workspace and core)
- Incorrect WAV headers and metadata in `WavFileGenerator`
- Validate SQLite metadata keys and table names

## [0.3.2] - 2026-04-26

### Added
- `ModelRegistryService` trait and default implementation for provider/model abstraction
- Modular RAG example with guide and tests
- Provider dashboard sample demo
- Expanded STT domain model with richer metadata, validation, and error handling

### Changed
- Refactored `AssistantAgent` to expose `AgentContext` on the constructor
- **Breaking:** Removed `LLMProvider` sealed trait in favour of `ProviderKind` — update exhaustive pattern matches; see [0.x → 1.0 migration guide](docs/migrations/0x-to-1x.md#llmprovider-replaced-by-providerkind)
- Simplified project names in `build.sbt`

### Fixed
- Thread `ModelRegistryService` through `ModularRAGExample`
- Hardened `ShellTool` allowlist to block shell-metacharacter injection
- Broken markdown table in README
- Build and clean up of modular RAG example

### Docs
- Result-based error handling in all examples

## [0.3.1] - 2026-02-22

### Added
- Adaptive windowing pruning strategy and context window guide
- ScalaDoc Waves 1–5 across all public API packages (entry-point types, agent/message/toolapi, provider implementations, infrastructure, usage summary)

### Changed
- Split monolithic `Agent.scala` into focused modules for improved testability

### Fixed
- 12 Scaladoc errors that caused the `core/doc` build to fail

## [0.3.0] - 2026-02-22

### Added
- Queryable in-process `TraceStore` with `InMemoryTraceStore`
- Thread-safe `CircuitBreaker` with optimistic locking and improved testability via clock injection
- Production deployment guide (`docs/PRODUCTION_DEPLOYMENT.md`)
- PostgreSQL `MemoryStore` usage examples and troubleshooting docs

### Changed
- **Breaking:** Removed all previously-deprecated throwing APIs (`agent.initialize`, `DateTimeTool.tool`, `BuiltinTools.core`, etc.) — migrate to their `Safe` variants; see [Migrating from v0.2.9 to v0.3.0](docs/migrations/v0.2.9-to-v0.3.0.md)

### Fixed
- WAV header bugs, incorrect metadata and resource leaks in the speech module
- Empty-embedding error assertions in RAG tests
- Provider test coverage via `Llm4sHttpClient` injection

## [0.2.9] - 2026-01-11

### Added
- Unified document processing pipeline with S3 source support
- Multi-tonal translation example

### Changed
- Replaced `println` with structured logging in memory samples
- Removed duplicate `sbt`/`coursier` cache entries from CI test matrix

### Fixed
- `DisableSyntax` scalafix violations

## [0.2.8] - 2026-01-05

### Added
- Unified `EMBEDDING_MODEL` configuration format (`provider/model-name`)
- Shared `MCPConfig` extracted to eliminate duplication across MCP server/client samples
- PureConfig-based configuration for MCP server and client samples

### Fixed
- `PgSearchIndex` not persisting vectors when using `RAGConfig.withSearchIndex`

## [0.2.7] - 2026-01-02

### Added
- `WebCrawlerLoader` for RAG document ingestion from live URLs
- `WebCrawlerLoader` design specification

### Fixed
- Code review issues in `Agent` and `WorkspaceAgentInterfaceImpl`

## [0.2.6] - 2026-01-02

### Added
- Permission-based RAG for enterprise multi-tenant access control
- PostgreSQL integration tests in CI

### Fixed
- Cross-collection chunk ID collision (IDs are now namespaced by collection name)
- Permission validation and CI environment variables
- PostgreSQL permission test reliability

## [0.2.5] - 2025-12-30

### Fixed
- Restore working `publishTo` configuration with local staging

## [0.2.4] - 2025-12-29

### Fixed
- Publishing: remove custom `publishTo`, rely on `sbt-ci-release` defaults

## [0.2.3] - 2025-12-28

### Fixed
- Out-of-memory error during release caused by Scaladoc generation; disable test-module Scaladoc and increase heap

## [0.2.2] - 2025-12-28

### Fixed
- Scaladoc generation failures for Scala 2.13

## [0.2.1] - 2025-12-26

### Added
- Comprehensive tests for `types`, `rag`, `agent`, and `assistant` packages
- Code coverage reporting to CI via Codecov

### Changed
- Consolidated tracing implementations; removed duplicate code and legacy type aliases
- Renamed `EnhancedAgent` / `TypedAgent` to simpler canonical names

### Fixed
- Flaky tests in `StdioTransportConcurrencySpec` and `SessionStateSpec`
- Codecov token configuration for v4 action

## [0.2.0] - 2025-12-17

### Added
- RAG Phase 2: complete RAG pipeline with evaluation, benchmarking, guardrails, and cost tracking
- Comprehensive documentation overhaul
- Validation using the Cats `Validated` framework

### Fixed
- Race condition in `StdioTransportImpl` concurrent request handling
- Flaky truncation test in `ShellToolsSpec`
- Maven artifact coordinates in documentation

## [0.1.16] - 2025-10-20

### Added
- Debug logging to `Agent` and comprehensive game-tools integration tests

### Fixed
- Critical `NoSuchElementException` bug in `healthCheck()`

## [0.1.15] - 2025-10-20

### Fixed
- Zero-parameter tools now correctly accept `null` argument payloads

## [0.1.14] - 2025-10-12

### Changed
- **Breaking:** Removed `DBx` module — PostgreSQL/vector-store functionality has been replaced by the RAG module

### Fixed
- Tool calling bug causing HTTP 400 errors with the Anthropic API
- Parameter ordering in `OpenAIClient` builder methods
- Simplified cross-test commands and cleaned up unused SBT configurations

## [0.1.13] - 2025-09-28

### Added
- Multi-agent orchestration: type-safe `Agent[I, O]` contracts, DAG planning, topological execution with level-based parallelism
- Vector operations foundation and `VectorStore` layer
- Security documentation and exception-safety refactor (Try → Either throughout)

### Changed
- Moved all modules into `modules/` subdirectory; cleaned up `build.sbt`
- Removed legacy codegen module and deprecated workspace classes

### Fixed
- SQL injection prevention and connection pooling in database utilities

## [0.1.12] - 2025-09-13

### Added
- Scalafix rule to ban direct environment-variable access (`sys.env`, `System.getenv`)
- Improved speech-file generation with comprehensive error handling and test coverage

### Fixed
- Tool call failures and improved error messages

## [0.1.11] - 2025-08-30

### Added
- Ollama LLM provider support with example implementations and tests
- Assistant agent with session management (`AssistantAgent`, `SessionManager`, `ConsoleInterface`)

### Changed
- Replaced `EnvLoader` with `ConfigReader` for flexible configuration handling
- Refactored MCPClient for better maintainability
- Enhanced Agent framework and tracing

## [0.1.10] - 2025-08-18

### Added
- Native SDK streaming support for OpenAI and Anthropic

## [0.1.9] - 2025-08-01

### Fixed
- Scala 3 build compilation errors

## [0.1.8] - 2025-08-01

### Changed
- Refactored `Agent.run` to support resuming execution from an existing `AgentState`

## [0.1.0] – [0.1.7] - 2025-04-06 – 2025-08-01

Initial release. Versions 0.1.0 through 0.1.7 were primarily focused on establishing the release pipeline:

### Added
- Core LLM client abstraction with OpenAI and Anthropic providers
- `Result[A]` / `Either[LLMError, A]` error model
- Tool calling API with `ToolRegistry` and `ToolBuilder`
- Agent framework with basic run/step loop
- SBT multi-module build with cross-compilation for Scala 2.13 and 3.x

### Fixed (release infrastructure)
- Windows ScalaFmt build issue
- Publishing configuration and v-prefixed tag handling
- GitHub Actions release workflow

---

[Unreleased]: https://github.com/llm4s/llm4s/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/llm4s/llm4s/compare/v0.3.4...v0.4.0
[0.3.4]: https://github.com/llm4s/llm4s/compare/v0.3.3...v0.3.4
[0.3.3]: https://github.com/llm4s/llm4s/compare/v0.3.2...v0.3.3
[0.3.2]: https://github.com/llm4s/llm4s/compare/v0.3.1...v0.3.2
[0.3.1]: https://github.com/llm4s/llm4s/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/llm4s/llm4s/compare/v0.2.9...v0.3.0
[0.2.9]: https://github.com/llm4s/llm4s/compare/v0.2.8...v0.2.9
[0.2.8]: https://github.com/llm4s/llm4s/compare/v0.2.7...v0.2.8
[0.2.7]: https://github.com/llm4s/llm4s/compare/v0.2.6...v0.2.7
[0.2.6]: https://github.com/llm4s/llm4s/compare/v0.2.5...v0.2.6
[0.2.5]: https://github.com/llm4s/llm4s/compare/v0.2.4...v0.2.5
[0.2.4]: https://github.com/llm4s/llm4s/compare/v0.2.3...v0.2.4
[0.2.3]: https://github.com/llm4s/llm4s/compare/v0.2.2...v0.2.3
[0.2.2]: https://github.com/llm4s/llm4s/compare/v0.2.1...v0.2.2
[0.2.1]: https://github.com/llm4s/llm4s/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/llm4s/llm4s/compare/v0.1.16...v0.2.0
[0.1.16]: https://github.com/llm4s/llm4s/compare/v0.1.15...v0.1.16
[0.1.15]: https://github.com/llm4s/llm4s/compare/v0.1.14...v0.1.15
[0.1.14]: https://github.com/llm4s/llm4s/compare/v0.1.13...v0.1.14
[0.1.13]: https://github.com/llm4s/llm4s/compare/v0.1.12...v0.1.13
[0.1.12]: https://github.com/llm4s/llm4s/compare/v0.1.11...v0.1.12
[0.1.11]: https://github.com/llm4s/llm4s/compare/v0.1.10...v0.1.11
[0.1.10]: https://github.com/llm4s/llm4s/compare/v0.1.9...v0.1.10
[0.1.9]: https://github.com/llm4s/llm4s/compare/v0.1.8...v0.1.9
[0.1.8]: https://github.com/llm4s/llm4s/compare/v0.1.7...v0.1.8
[0.1.7]: https://github.com/llm4s/llm4s/compare/v0.1.0...v0.1.7
[0.1.0]: https://github.com/llm4s/llm4s/releases/tag/v0.1.0
