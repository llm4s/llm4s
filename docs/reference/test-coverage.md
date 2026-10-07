---
layout: page
title: Test Coverage
parent: Reference
nav_order: 15
---

# Test Coverage

LLM4S measures **statement coverage** with [`sbt-scoverage`](https://github.com/scoverage/sbt-scoverage), one policy
per module. There is no single project-wide threshold: each module declares its own floor, and CI checks every
module against it.

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

The `Code Coverage` job in `.github/workflows/ci.yml` runs `<module>/test <module>/coverageReport` for each
measured module. `<module>/coverageReport` is the step that enforces that module's floor. The aggregate report is
informational only, because a build-wide average is not a meaningful gate.

A new module has to be added to that job in the same change that adds it, or the code it holds drops out of
measurement.

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
