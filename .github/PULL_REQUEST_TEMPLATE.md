## What does this PR do?
<!-- Brief description of the change and WHY it's needed -->

## Related issue
<!-- Link the issue this addresses: Fixes #XXX or Relates to #XXX -->

## How was this tested?
<!-- Describe what you tested and how. Include relevant commands or test names. -->

## Release impact
<!-- Pick one and add the matching label. See https://llm4s.org/contribute/release#merging-after-10 -->

- [ ] `semver:patch` — a fix, docs, tests, build, or `private[llm4s]` code only
- [ ] `semver:minor` — adds or deprecates API, or changes an `@Experimental` type or a Beta/Experimental module
- [ ] `semver:major` — breaks a `@Stable` API (not merged until 2.0)

If this touches a `@Stable` API: the old API still compiles and behaves the same (no changed signature or default,
no new ambiguous overload or wildcard-importable `given`, no renamed config key, no change to stored JSON), and
anything replaced is `@deprecated` rather than changed.

## Checklist

- [ ] I have read the [Contributing Guide](https://llm4s.org/reference/contributing)
- [ ] PR is small and focused — one change, one reason
- [ ] `sbt scalafixAll` passes
- [ ] `sbt scalafmtAll` — code is formatted
- [ ] `sbt test` — tests pass on Scala 3
- [ ] New code includes tests
- [ ] No unrelated changes included (branched from `main`, not from another PR)
- [ ] Commit messages explain the "why"
- [ ] Every commit is signed off (`git commit -s`, see [CONTRIBUTING.md](https://github.com/llm4s/llm4s/blob/main/CONTRIBUTING.md#developer-certificate-of-origin-dco))
- [ ] If this changes a Frozen module's public API, a module's tier, an SPI or a wire format: links the accepted [RFC](https://github.com/llm4s/llm4s/blob/main/docs/rfcs/README.md)
- [ ] Added a changelog fragment, `changelog.d/<area>/<issue>.md` ([format](https://github.com/llm4s/llm4s/blob/main/changelog.d/README.md)), if this adds a feature, fixes a bug, or introduces a breaking change
- [ ] A source break (a `### Breaking changes` entry) adds or updates a [Scalafix migration rule](https://llm4s.org/reference/scalafix#migration-rules) in `modules/scalafix-rules`, or this PR says why it cannot be automated
