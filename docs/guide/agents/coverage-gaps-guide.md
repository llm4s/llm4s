# Test Coverage Gap Analysis

This guide explains how to use the automated test coverage analysis tools to identify which parts of the llm4s codebase need more tests.

## Overview

The llm4s project uses **scoverage** for coverage measurement and integrates with **Codecov** for tracking coverage over time. To make it easier for contributors to understand where tests are needed, we provide automated tools that analyze coverage reports and highlight gaps.

## Quick Start

### Running Locally

Generate a coverage report and analyze it:

```bash
# Generate coverage data
sbt coverage core/test coverageAggregate

# Analyze coverage gaps
python3 scripts/analyze-coverage.py --local
```

This will print a detailed report to your terminal showing:
- Overall coverage percentage
- Module-by-module breakdown
- Files with the lowest coverage
- Specific uncovered functions
- Recommendations for improvement

### Save Report to File

```bash
python3 scripts/analyze-coverage.py --local --output coverage-gaps.txt
```

## Understanding the Report

### Example Output

```
================================================================================
TEST COVERAGE ANALYSIS REPORT
================================================================================

OVERALL SUMMARY
--------------------------------------------------------------------------------
Overall Coverage:   68.4%
Total Statements:   5,432
Covered Statements: 3,717
Uncovered Lines:    1,715
Modules Analyzed:   5
Files Analyzed:     87

MODULE COVERAGE BREAKDOWN (sorted by coverage)
--------------------------------------------------------------------------------
✗ core                 | Coverage:  62.3% | Uncovered:  850 lines | Files: 45
✗ workspace            | Coverage:  55.1% | Uncovered:  620 lines | Files: 32
⚠ trace-opentelemetry | Coverage:  71.8% | Uncovered:  245 lines | Files: 10

FILES WITH LOWEST COVERAGE (Top 20)
-------------------------------------
1. ✗ Agent.scala                            |  24.5% (127 uncovered)
2. ✗ MemorySystem.scala                     |  31.2% (98 uncovered)
3. ⚠ Guardrails.scala                       |  59.5% (67 uncovered)
```

### Coverage Indicators

- **✓ (Green/Good)**: 80%+ coverage - Strong coverage
- **⚠ (Yellow/Medium)**: 60-79% coverage - Room for improvement
- **✗ (Red/Low)**: <60% coverage - High priority for tests

## Finding Files That Need Tests

### Method 1: Look at the Report

Check the "FILES WITH LOWEST COVERAGE" section to see which files have the most uncovered code.

### Method 2: Examine Specific Functions

The report shows uncovered functions in files with low coverage:

```
    Functions without tests: parseExpression, validateSyntax, execute
```

These are immediate targets for adding tests.

### Method 3: Check Module Gaps

Look at "MODULE COVERAGE BREAKDOWN" to identify which subsystems need work:

1. **core** - Main agent framework - Critical to test thoroughly
2. **workspace** - Workspace runner and client - Important for integration tests
3. **trace-opentelemetry** - Observability features

## Creating Test Coverage PRs

### Step 1: Identify Target

```bash
# Run coverage analysis
python3 scripts/analyze-coverage.py --local --output coverage-gaps.txt

# Pick a low-coverage file from the report
cat coverage-gaps.txt | grep "✗\|⚠"
```

### Step 2: Explore the Code

```bash
# Open the file with lowest coverage
# Look at the uncovered functions listed in the report
# Understand what the functions do
```

### Step 3: Write Tests

Use the [Testing Guide](https://llm4s.org/reference/testing-guide.html) as a reference:

```scala
// Example: Testing a function that appears in the coverage report
class AgentSpec extends AnyFlatSpec with Matchers {
  "Agent initialization" should "set default values" in {
    val agent = Agent.create()
    agent.id should not be empty
  }
}
```

### Step 4: Verify Coverage

Before submitting your PR, verify your tests improved coverage:

```bash
sbt coverage core/test coverageReport
```

Check the HTML report:
```
target/scala-3.7.1/scoverage-report/index.html
```

### Step 5: Submit PR

Your PR will automatically get a coverage analysis comment showing the improvement! 🎉

## CI/GitHub Actions Integration

### Automatic Coverage Analysis in PRs

When you open a pull request, the **Coverage Gaps Analysis** workflow automatically:

1. Runs your tests with coverage measurement
2. Analyzes which parts of the code still need tests
3. Posts a detailed comment on your PR with findings
4. Uploads coverage reports as artifacts

### What You'll See in PRs

Your coverage comment will include:
- Overall coverage summary
- Module-by-module breakdown
- Files with lowest coverage
- Links to testing docs
- Warnings for low coverage areas

## Advanced Usage

### JSON Output

For tooling and automation, get machine-readable output:

```bash
python3 scripts/analyze-coverage.py --local --json
```

Returns JSON like:
```json
{
  "overall_coverage": 68.4,
  "total_statements": 5432,
  "total_covered": 3717,
  "total_uncovered": 1715,
  "num_modules": 5,
  "num_files": 87
}
```

### Using Codecov API (Optional)

If you want to check coverage for a specific commit on the main branch:

```bash
export CODECOV_TOKEN="your_codecov_token"
python3 scripts/analyze-coverage.py --codecov --repo llm4s/llm4s
```

**Note**: This requires a Codecov account and token. Scoverage reports (local method) are usually sufficient for most use cases.

### Customizing Reports

The analysis script supports several options:

```bash
python3 scripts/analyze-coverage.py --help
```

Common options:
- `--local` - Analyze local scoverage reports (default)
- `--output FILE` - Save report to file
- `--json` - Output as JSON instead of formatted text
- `--root DIR` - Search for coverage files in different directory

## Coverage Goals

The llm4s project aims for:

- **Overall coverage**: Minimum 50% (checked in CI)
- **Core module**: Target 75%+
- **New code**: Ideally 80%+
- **Critical paths**: 90%+ (agent execution, LLM calls, etc.)

## Troubleshooting

### "No scoverage.xml files found"

Make sure you've run coverage generation first:

```bash
sbt coverage core/test coverageAggregate
```

### Report shows different coverage than Codecov

This can happen because:
- Local coverage includes all test runs
- Codecov aggregates across CI runs
- Exclusions in `codecov.yml` affect Codecov but not local reports

To match CI exactly:
```bash
# Run with same exclusions as CI
sbt coverage core/test coverageAggregate
```

### Python script errors

Ensure Python 3.8+ is installed:

```bash
python3 --version
```

## Contributing Coverage Improvements

Coverage improvements are always welcome! Here's how to contribute:

1. **Pick a low-coverage file** from the coverage report
2. **Understand the code** - Read the functions that need tests
3. **Write tests** - Follow patterns from similar test files
4. **Verify improvement** - Run coverage locally to check
5. **Submit PR** - Include coverage report in PR description

### PR Description Template

```markdown
## Coverage Improvement: [Module Name]

### Motivation
Current coverage for [module] is [X]%. This PR adds tests for...

### Changes
- Added tests for function `foo()`
- Added tests for error cases in `bar()`
- Added integration tests for...

### Coverage Impact
Before: [X]%
After: [Y]%

### Testing
- [x] Tests pass locally
- [x] Coverage improved
- [ ] Codecov should report improvement
```

## Further Reading

- [Testing Guide](https://llm4s.org/reference/testing-guide.html)
- [Contributing Guidelines](https://llm4s.org/reference/contributing.html)
- [Codecov Documentation](https://docs.codecov.io/)
- [Scoverage Documentation](https://scoverage.org/)

## Questions or Issues?

If you have questions about coverage analysis or need help finding tests to write:

1. Check the [troubleshooting section](#troubleshooting) above
2. Review the [Testing Guide](https://llm4s.org/reference/testing-guide.html)
3. Open a discussion or issue on [GitHub](https://github.com/llm4s/llm4s)
