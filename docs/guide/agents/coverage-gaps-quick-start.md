# Test Coverage Gap Analysis - Quick Start

Copy this document for quick reference on using the coverage analysis tools.

## TL;DR - For Contributors

```bash
# 1. Generate coverage
sbt coverage core/test coverageAggregate

# 2. See coverage gaps
python3 scripts/analyze-coverage.py --local

# 3. Pick a low-coverage file and write tests for it
# (follow patterns from existing tests)

# 4. Verify improved coverage
sbt coverage core/test coverageReport
```

Then commit and push - CI will analyze your changes!

## Commands Quick Reference

### Generate Local Coverage Report

```bash
# Generate coverage data
sbt coverage core/test coverageAggregate

# Analyze and display to console
python3 scripts/analyze-coverage.py --local

# Save to file for later review
python3 scripts/analyze-coverage.py --local --output gaps.txt

# Get JSON for tooling
python3 scripts/analyze-coverage.py --local --json
```

### View HTML Coverage Report

```bash
# Open in web browser
open target/scala-3.7.1/scoverage-report/index.html

# (Linux: xdg-open, Windows: start)
```

### Wrapper Scripts

**Linux/macOS:**
```bash
./scripts/analyze-coverage.sh --local
./scripts/analyze-coverage.sh --local --output gaps.txt
```

**Windows:**
```cmd
scripts\analyze-coverage.bat --local
scripts\analyze-coverage.bat --local --output gaps.txt
```

## Understanding the Report

### Coverage Levels
- **✓ Green (80%+)** - Good! Keep tests current
- **⚠ Yellow (60-79%)** - Room for improvement
- **✗ Red (<60%)** - High priority for tests

### What to Look For
1. Files with `✗` symbol - These need tests most
2. "Uncovered functions" listed below each file
3. "RECOMMENDATIONS" section at bottom

### Example Report Section
```
FILES WITH LOWEST COVERAGE (Top 20)
1. ✗ Agent.scala                    |  24.5% (127 uncovered)
   Functions without tests: parseExpression, validateSyntax
2. ✗ MemorySystem.scala             |  31.2% (98 uncovered)
   Functions without tests: storeMemory, retrieveMemory
```

## Writing Tests

### 1. Pick a low-coverage file
Pick from the "FILES WITH LOWEST COVERAGE" section of the report.

### 2. Look at existing tests
```bash
# Find similar test file
find modules/core/src/test/scala -name "*AgentSpec.scala"
```

### 3. Follow the pattern
```scala
class MyComponentSpec extends AnyFlatSpec with Matchers {
  "MyComponent" should "do X" in {
    val result = MyComponent.myFunction()
    result should equal(expectedValue)
  }
}
```

### 4. Test uncovered functions
The report shows which functions need tests. Prioritize those.

### 5. Verify locally
```bash
sbt coverage core/test coverageReport
# Check target/scala-3.7.1/scoverage-report/index.html
```

## CI Integration

### What Happens on Your PR

1. ✅ Tests run with coverage
2. ✅ Coverage analyzed
3. ✅ Results posted as PR comment
4. ✅ Report artifacts uploaded

### Review the Results
- Look for PR comment with 📊 emoji
- Expand "Click to view coverage gaps"
- See exactly which functions are uncovered

## Troubleshooting

### "No scoverage.xml files found"
```bash
# You forgot to generate coverage!
sbt coverage core/test coverageAggregate
```

### Can't find Python
```bash
# Check Python is installed
python3 --version

# Or try without 3 suffix
python --version
```

### Wrapper script fails
```bash
# Run Python directly
python3 scripts/analyze-coverage.py --local
```

### Different coverage than expected
- Local reports may differ from CI
- Codecov.yml may exclude some files
- Coverage varies by test run

## Resources

- **[Full Coverage Guide](../docs/guide/agents/coverage-gaps-guide.md)** - Complete documentation
- **[Testing Guide](../docs/reference/testing-guide.md)** - How to write good tests
- **[Scripts Docs](../scripts/README.md)** - Script reference
- **[Implementation Guide](../docs/reference/coverage-gaps-implementation.md)** - For maintainers

## Tips for High-Impact PRs

1. **Pick a low-coverage file** - Focus on files with < 50% coverage
2. **Write 5-10 new tests** - Add multiple focused tests
3. **Cover edge cases** - Don't just cover the happy path
4. **Run locally** - Verify improvement before submitting
5. **Mention in PR** - Tell reviewers coverage improved

Example PR description:
```markdown
## Improve coverage for Agent module

### Changes
- Added tests for Agent.parseExpression()
- Added tests for Agent.validateSyntax()
- Added error case tests

### Coverage Impact
- Before: 24.5%
- After: 58.2%
```

## Getting Help

- **Questions?** Check the [full guide](../docs/guide/agents/coverage-gaps-guide.md)
- **Issues?** Post on GitHub
- **Ideas?** Open a discussion

## One More Thing

Coverage is a tool, not a goal. Focus on:
- ✅ Testing important behavior
- ✅ Testing edge cases
- ✅ Making it easy for contributors
- ❌ Not just hitting a number

Good tests help everyone!
