---
layout: page
title: Test Coverage
parent: Reference
nav_order: 15
---

# Test Coverage

LLM4S measures **statement coverage** with [`sbt-scoverage`](https://github.com/scoverage/sbt-scoverage), one policy
per module. There is no single project-wide threshold: each module declares its own policy, and the CI coverage
job checks the floors of the modules it measures (with the exceptions below).

## How coverage is enforced

### Per-module floors

Every sbt project in `build.sbt` makes an explicit decision with one of two settings (defined in
`project/Dependencies.scala`, object `Coverage`):

| Setting | Meaning |
|---------|---------|
| `coverageFloor(n)` | Statement coverage must stay at or above `n` percent. It sets `coverageMinimumStmtTotal := n` and `coverageFailOnMinimum := true` for that module. |
| `coverageDisabled` | The module is deliberately not measured. It carries a comment in `build.sbt` saying why. |

A floor is the measured statement coverage **rounded down to the nearest 5**, so a module has headroom for normal
churn. Floors only ratchet up: raise one when a module's coverage grows, and never lower one to make a change pass.

A floor is never inherited. The build-wide default is `Coverage.Policy.Undeclared`, so a new module has to choose.

### The policy check

`sbt coveragePolicyCheck` lists every module's policy and fails if any module has declared neither a floor nor an
opt-out. It runs in CI as a quick check, and prints one line per module:

```
Coverage policy per module:
  agent                          floor 80%
  configPolicy                   disabled
  ...
```

Run it to see the current floors. The numbers are deliberately not copied here, because they change.

### What CI runs

Coverage runs in four parallel legs, the `Code Coverage (<leg>)` jobs (`coverage-leg` in
`.github/workflows/ci.yml`). Each leg lists the modules it owns in the job's matrix and runs
`<module>/test <module>/coverageReport` for each, in order; `<module>/coverageReport` is the step that enforces that
module's floor. A report counts every measurement taken so far in the leg, so a module's floor is judged on its own
tests plus those of modules that ran ahead of it in the same leg. The legs keep the order the single coverage job
used, and mostly keep a module with the modules whose tests exercise its code (`rag` before `knowledgegraph`,
`media` and `observability`; `image` before `observabilityPrometheus`; the providers before `providerTestkit`).
Two such pairs are now split: `providerTestkit` no longer counts `rag`'s, `mcp`'s, `speech`'s and `image`'s tests
(96.66% to 96.46%, floor 90), nor `agent` `observability`'s (93.19% to 93.14%, floor 90). Each leg uploads its modules' Codecov flags, and only
those, so every flag is uploaded once. The `Code Coverage` job passes only when every leg does, and is what
`All Tests Pass` waits for. It also writes the aggregate report from the legs' coverage data, which is informational
only, because a build-wide average is not a meaningful gate.

`knowledgegraphNeo4j` and `benchmarks` declare floors but are in no leg, so CI does not currently enforce their
floors. The benchmark workflow runs tests without coverage.

The `Integration Tests` job adds the `modules/it` Docker tier's hits (#2349). Some code is reachable only against a
real service: the pgvector, Qdrant and permission-index stores in `rag`, the Elasticsearch/OpenSearch and Redis/Valkey
stores, `memoryPostgres`, `knowledgegraphNeo4j`, and the Postgres checkpointer and run queue in
`agentCheckpointPostgres`. The job instruments just those modules (its test step's `COVERAGE_MODULES`), runs
`sbt testIntegration`, writes each module's `coverageReport` with its floor switched off, and uploads each report under
the module's own flag. Codecov merges the two uploads of a flag on a commit, so the integration hits count towards
patch and project coverage; the floors stay with the legs, because a report from the integration suites alone is not
what a floor measures. `knowledgegraphNeo4j`, in no leg, is uploaded only from this job. To run it locally against
the services, as CI does: `sbt "set rag / coverageEnabled := true" "set rag / coverageFailOnMinimum := false"
"set rag / coverageMinimumStmtTotal := 0" testIntegration rag/coverageReport` (those three settings and one report
per module).

A new module with a floor has to join a leg, normally the one holding its dependencies, after them, with an upload step for
its flag, in the same change that adds it. `scripts/check-coverage-legs.sh`, run in Quick Checks against the build sbt
loaded, fails until it does: it checks that every module with a floor is in exactly one leg (or in the script's
short, reasoned exemption list) and has exactly one upload step, under the `codecov.yml` flag for its directory.
The integration job's uploads are the one allowed second upload of a flag: the script checks that each module in its
`COVERAGE_MODULES` has exactly one upload there, of its own report under its own flag, that the job switches those
floors off, and that the job uploads nothing else.

To run one leg locally as CI does, list its modules' commands after `coverage`, for example
`sbt coverage core/test core/coverageReport memory/test memory/coverageReport`.

### Codecov

`codecov.yml` configures the pull-request statuses:

- **`patch`**: new and changed lines must be covered at **90%**, with a **5%** threshold.
- **`project`**: the target is `auto` (the base commit's coverage), with a **1%** threshold.
- **Flags**: one flag per module, whose `paths` are that module's `src/main/scala`. Codecov only attributes a file
  to a flag whose `paths` contain it, so a module with no flag silently disappears from per-module reporting
  instead of failing. Every change that adds a module adds its flag to `codecov.yml`.

Only main sources are tracked. `modules/benchmarks` is ignored.

### Not measured

`ThisBuild / coverageExcludedPackages` excludes these packages:

- `org.llm4s.runner.*`
- `org.llm4s.deploy.DeployServiceMain` (the deploy service's entry point; its routes and checks are measured)
- `org.llm4s.examples.http.*Main` (the HTTP reference apps' entry points; their routes and servers are measured)
- `org.llm4s.samples.*`
- `org.llm4s.workspace.*`

## Running coverage locally

Aliases from `build.sbt`:

| Command | Does |
|---------|------|
| `sbt cov` | `clean; coverage; test; coverageAggregate; coverageReport; coverageOff`: the whole test suite with coverage, plus the aggregate report at the root. It is for looking at the combined picture and enforces no module floor, because the root project's own policy is `disabled`. |
| `sbt covReport` | `clean; coverage; test; coverageReport; coverageOff`: the same for the current project only. |

To check one module the way CI does, including its floor:

```bash
sbt coverage core/test core/coverageReport
```

Replace `core` with the module's sbt project name (the one `coveragePolicyCheck` prints). The report is written to
`target/scala-3.7.1/scoverage-report/` inside the module's directory: `index.html` for reading, `scoverage.xml` for tooling.
Run `sbt coverageOff` afterwards, or start a new sbt session, so later compiles are not instrumented.

To see a module's report without failing on its floor, for a one-off run:

```bash
sbt "set core / coverageFailOnMinimum := false" coverage core/test core/coverageReport
```

## Troubleshooting

- **Coverage is 0% everywhere.** Run `coverage` before `test` in the same sbt invocation.
- **`coveragePolicyCheck` says a module is `UNDECLARED`.** Add `coverageFloor(<pct>)` or `coverageDisabled` (with a
  comment) to the project in `build.sbt`, and add its flag to `codecov.yml` in the same change.
- **A module's floor fails after your change.** Add tests for the new code. Do not lower the floor.
