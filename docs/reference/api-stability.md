# API Stability (MiMa)

## What this covers

[`sbt-mima-plugin`](https://github.com/lightbend-labs/mima) checks published modules for
binary-incompatible changes against their last Maven Central release. It runs as part of `ci`
(see [Release Process](release)), before `publish`, so a break fails the PR instead of being
discovered by a downstream consumer after a release ships.

MiMa auto-enables on every sbt project (`MimaPlugin.trigger = allRequirements`), so
`ThisBuild / mimaFailOnNoPrevious := false` is set once in `build.sbt` to stop modules with no
prior artifact from failing for the wrong reason. Only modules that explicitly set
`mimaPreviousArtifacts` are actually checked.

### Enabled today

| Module | Checked against |
|---|---|
| `workspaceClient` (`llm4s-workspace-client`) | `0.4.1` |
| `workspaceShared` (`llm4s-workspace-shared`) | `0.4.1` |
| `traceOpentelemetry` (`llm4s-observability-otel`) | `0.4.1` |
| `knowledgegraphNeo4j` (`llm4s-knowledgegraph-neo4j`) | `0.4.1` |

### `core` - deliberately not enabled yet

`core` has been published (`0.4.0`, `0.4.1`), so on paper it belongs in the table above. It isn't
there because 0.4.1 predates the [modularisation programme](https://github.com/llm4s/llm4s/issues/1126)
(slices 1-4): `rag`, `knowledgegraph`, `agent.memory`, `mcp`, `vectorstore` (bar
`PostgresVectorHelpers`), `chunking`, `reranker`, `eval`, `imagegeneration`, `imageprocessing`,
`speech`, and the provider-capability classes (`ProviderCapabilities`, `NamedProviderValidators`)
have all already been carved out of `core`'s source, but no new version has been published since.
Diffing current `core` against `0.4.1` reports over a thousand `MissingClassProblem`s that are
all already-shipped-in-source, not-yet-released intentional removals - not a signal of anything
going wrong today.

Enabling MiMa against that stale baseline would fail every PR for changes nobody is making
anymore, so `core` stays out of the checked set until it is next published. At that point its
`build.sbt` entry is a one-line change, same shape as the other four:

```scala
mimaPreviousArtifacts := Set(organization.value %% moduleName.value % "<next-release>"),
```

(see the comment at that setting in `build.sbt` for the exact location). From that release
forward, `core` enforces the same contract as everything else in this doc - including the
provider-registration-SPI carve in slice 5 ([#1131](https://github.com/llm4s/llm4s/issues/1131)/
[#1132](https://github.com/llm4s/llm4s/issues/1132)), which should add justified
`mimaBinaryIssueFilters` entries for whatever it removes, not disable the check again.

### Not yet published

`rag`, `knowledgegraph`, `memory`, `memoryPostgres`, `mcp`, `media`, `image`, `speech` were
carved out of `core` during slices 1-3 but have never been released to Maven Central - there is
no prior artifact for MiMa to diff against, so they're left unconfigured rather than pinned to a
version that doesn't exist. **Enable MiMa on a module in the same PR that first publishes it** -
add `mimaPreviousArtifacts` pinned to that first released version once a second version exists to
check the next release against.

Everything else (`workspaceRunner`, `samples`, `configPolicy`, `workspaceSamples`, `it`, `docs`,
`benchmarks`, and the Maven relocation stubs) is `publish / skip := true` and never enters this
picture. The root `llm4s` aggregate is the same case, but spells out
`mimaPreviousArtifacts := Set.empty` next to its `publish / skip := true` rather than relying on
that alone - same convention `sbt/sbt` and `sbt/librarymanagement` use on their own root
aggregates, so the setting's absence elsewhere in the build reads as "not applicable," never
"forgotten."

## Public API vs internal

Not every package MiMa can see is a contract. [1.0 Scope](v1-scope) is the authoritative,
package-by-package table - **Frozen at 1.0** is the public, stable surface this document's
compatibility contract applies to; **Beta** and **Experimental** may still change in a minor
release, with a migration note in the same release's `CHANGELOG.md` entry.

The split the issue that created this doc used as its example still holds: `org.llm4s.llmconnect`
(the client API), `org.llm4s.agent`, and `org.llm4s.toolapi` are Frozen. `org.llm4s.llmconnect.provider`
is not one tier - most concrete provider clients (OpenAI, Anthropic, Gemini, Ollama) are Frozen
once they reach their own target module, but the shared plumbing in that same directory today
(cost estimation, HTTP error mapping) has its home still unsettled pending
[#1131](https://github.com/llm4s/llm4s/issues/1131), and community clients (Cohere, Mistral) are
Beta. Check [1.0 Scope](v1-scope) per package rather than assuming a package's parent tier applies
uniformly to everything under it.

## The compatibility contract

`versionScheme := Some("early-semver")` (`build.sbt`) is the policy MiMa enforces, pre-1.0:

- A patch bump within a `0.x.y` series (`0.4.0` -> `0.4.1`) **must** stay binary compatible.
- Bumping the second component (`0.4.y` -> `0.5.0`) is the signal that a release contains an
  intentional binary break.

Post-1.0 this becomes standard SemVer: only a MAJOR bump may break compatibility.

## Adding a `mimaBinaryIssueFilters` entry

When a break is intentional (an API is deliberately removed or changed), suppress it explicitly
rather than disabling the check for the whole module:

```scala
mimaBinaryIssueFilters += ProblemFilters.exclude[MissingClassProblem]("org.llm4s.foo.Removed"),
// Removed in the provider-SPI carve - see #1131. Foo moved to <module>.
```

Always pair the filter with a comment naming the reason and linking the issue or PR - a filter
with no explanation is indistinguishable from a break nobody noticed.

## Verifying locally

```bash
sbt workspaceClient/mimaReportBinaryIssues
sbt workspaceShared/mimaReportBinaryIssues
sbt traceOpentelemetry/mimaReportBinaryIssues
sbt knowledgegraphNeo4j/mimaReportBinaryIssues
```

CI runs all four as one step in the `quick-checks` job.
