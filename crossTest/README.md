# Cross-Version Testing Strategy

## Overview

This directory contains specialized test projects that verify the cross-version compatibility of LLM4S. Unlike conventional tests, these projects test against published artifacts rather than local target directories, providing a more realistic validation of how end users will experience the library.

## Test Projects Structure

- `crossTestScala2/` - Tests running against Scala 2.13
- `crossTestScala3/` - Tests running against Scala 3.x

## Why Test Against Published JARs?

Testing against published JARs rather than target directories offers several advantages:
1. Catches packaging issues early
2. Validates the actual artifacts users will depend on
3. Ensures proper dependency resolution
4. Verifies binary compatibility
5. Replicates real-world usage scenarios

## Available Commands

### Basic Cross-Testing
```scala
addCommandAlias("testCross", ";crossTestScala2/test;crossTestScala3/test")
```
This command:
- Runs tests for both Scala 2 and Scala 3 projects
- Tests against published artifacts
- Executes sequentially to ensure clean test states

### Full Verification
```scala
addCommandAlias("fullCrossTest", ";clean ;crossTestScala2/clean ;crossTestScala3/clean ;+publishLocal ;testCross")
```
This command performs a complete verification cycle:
1. Cleans all projects
2. Cleans individual cross-test projects
3. Publishes all versions locally
4. Runs all cross-version tests

## Test Implementation Guidelines

When writing cross-version tests:
1. Always depend on the published artifacts:
   ```scala
   libraryDependencies += "org.llm4s" %% "0.1.0-SNAPSHOT" % version
   ```
2. Test both API compatibility and functionality
3. Include version-specific features where applicable
4. Verify binary compatibility
5. Test integration scenarios

## Common Issues and Solutions

1. **Stale Artifacts**: Always run `fullCrossTest` when making significant changes
2. **Version Mismatches**: Ensure proper version management in build.sbt
3. **Binary Compatibility**: Watch for breaking changes between Scala versions

## CI/CD Integration

These tests are crucial in our CI pipeline:
1. Run on every PR
2. Required for release approval
3. Verify against multiple Scala versions

## Adding New Cross-Tests

When adding new cross-version tests:
1. Create test cases in both Scala versions
2. Verify against published artifacts
3. Include both positive and negative test cases
4. Document version-specific behaviors

## Future Improvements

- [ ] Add automated binary compatibility checking
- [ ] Expand test coverage across more Scala versions
- [ ] Add performance benchmarks across versions
