# Changelog fragments

Each pull request writes its `CHANGELOG.md` entry here, as `changelog.d/<area>/<issue>.md`, instead of editing
`CHANGELOG.md`, so two pull requests in one area never conflict. At release, `scripts/changelog-fragments.sh assemble`
folds the fragments into `[Unreleased]` under their area headings, in issue order, and deletes them.

| Directory | `[Unreleased]` heading |
|---|---|
| `core` | Core |
| `providers` | Providers |
| `observability` | Observability |
| `agent-runtime` | Agent runtime |
| `tools` | Tools |
| `embeddings-rag` | Embeddings & RAG |
| `configuration` | Configuration |
| `build-tooling` | Build & tooling |

A fragment holds one or more entries, exactly as they will read in `CHANGELOG.md`:

```markdown
---
breaking: `llm4s-foo` (Experimental): what breaks ([migration](docs/reference/migration.md#the-note)) [#1234](https://github.com/llm4s/llm4s/issues/1234).
---
- **Changed** the user-visible change ([#1234](https://github.com/llm4s/llm4s/issues/1234); [Guide](docs/guide/foo.md#section)).
```

- Each entry starts `- **Added**`, `**Changed**`, `**Removed**` or `**Fixed**`, is at most 2 lines (a continuation
  line is indented two spaces) and 900 characters, and links its issue. Detail goes in the docs and the migration guide.
- The front matter is optional: `breaking:` adds a line under `### Breaking changes`, which also links its note in
  `docs/reference/migration.md`.
- Relative links are written from the repository root, as in `CHANGELOG.md`, and must name a file that exists.
- A follow-up to unreleased work edits that issue's fragment (or its entry, if it is already in `CHANGELOG.md`); it
  never adds another.

`scripts/changelog-fragments.sh check`, run by Quick Checks and `scripts/check-docs.sh`, fails a malformed fragment.
