# Release Process

LLM4S follows [Semantic Versioning](https://semver.org/) (`MAJOR.MINOR.PATCH`), e.g. `v0.5.0`. Releases are cut by
pushing a `v`-prefixed tag to `main`; the release workflow runs CI, publishes the Scala 3 artifacts to Maven Central,
then creates the GitHub Release, deploys the docs and pushes the Docker image.

The full procedure, what each job does, and how to verify or recover a release are in
[docs/reference/release.md](docs/reference/release.md).

Release notes are the GitHub Releases (https://github.com/llm4s/llm4s/releases) and [CHANGELOG.md](CHANGELOG.md).
