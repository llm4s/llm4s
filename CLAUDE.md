# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# LLM4S Development Guidelines

## Build & Test Commands
```bash
# Build the project (Scala 3)
sbt compile

# Build for all Scala versions (2.13 and 3)
sbt +compile

# Build and test all versions
sbt buildAll

# Run a specific sample 
sbt "samples/runMain org.llm4s.samples.basic.BasicLLMCallingExample"

# Run a sample with Scala 2.13
sbt ++2.13.16 "samples/runMain org.llm4s.samples.basic.BasicLLMCallingExample"

# Run tests for the current Scala version
sbt test

# Run tests for all Scala versions
sbt +test

# Run a single test
sbt "testOnly org.llm4s.shared.WorkspaceAgentInterfaceTest"

# Format code
sbt scalafmtAll
```

## Cross Compilation Guidelines
- The project supports both Scala 2.13 and Scala 3
- Common code goes in `src/main/scala`
- Scala 2.13-specific code goes in `src/main/scala-2.13`
- Scala 3-specific code goes in `src/main/scala-3`
- Always test with both versions: `sbt +test`
- Use the cross-building commands: `buildAll`, `testAll`, `compileAll`

## Code Style Guidelines
- **Formatting**: Follow `.scalafmt.conf` settings (120 char line length)
- **Imports**: Use curly braces for imports (`import { x, y }`)
- **Error Handling**: Use `Either[LLMError, T]` for operations that may fail
- **Types**: Prefer immutable data structures and pure functions
- **Naming**: Use camelCase for variables/methods, PascalCase for classes/objects
- **Documentation**: Use Asterisk style (`/** ... */`) for ScalaDoc comments
- **Code Organization**: Keep consistent with existing package structure
- **Functional Style**: Prefer pattern matching over if/else statements