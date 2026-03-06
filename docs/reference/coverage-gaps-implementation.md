# Test Coverage Gap Analysis - Implementation Guide

This document outlines the implementation of automated test coverage gap analysis for the llm4s project.

## Overview

The test coverage gap analysis system helps contributors identify and understand which parts of the codebase need more test coverage. It's designed to be:

- **Automated** - Runs in CI on every PR
- **Contributor-friendly** - Easy to use locally
- **Actionable** - Provides specific recommendations
- **Non-intrusive** - Collapsible comment on PRs

## Components Implemented

### 1. Coverage Analysis Scripts

#### `scripts/analyze-coverage.py`
Main Python script that analyzes scoverage XML reports.

**Key features:**
- Parses scoverage XML files from multiple modules
- Calculates coverage statistics per file and module
- Identifies uncovered lines and functions
- Generates both formatted text and JSON output
- Supports Codecov API integration (optional)

**Main Classes:**
- `FileCoverage` - Coverage data for individual files
- `ModuleCoverage` - Aggregated coverage for modules
- `CoverageAnalyzer` - Main analysis engine
- `CodecovAnalyzer` - Codecov API integration

**Entry Point:** `main()` function with argparse CLI

#### `scripts/analyze-coverage.sh` (Linux/macOS)
Shell wrapper for easy invocation on Unix systems.

#### `scripts/analyze-coverage.bat` (Windows)
Batch wrapper for Windows users.

#### `scripts/generate-coverage-summary.py`
Specialized script for generating GitHub Actions workflow summaries.

**Features:**
- Parses scoverage.xml files
- Generates markdown coverage tables
- Creates visual indicators (🟢 🟡 🔴)
- Outputs to `coverage-summary.md` for workflow consumption

### 2. CI/GitHub Actions Integration

#### `.github/workflows/coverage-gaps.yml`
Workflow that runs on every PR to analyze coverage gaps.

**Trigger:** Pull requests to main/master branches

**Job: analyze-coverage-gaps**
Steps:
1. Checkout code
2. Set up Java 21 and SBT
3. Start PostgreSQL service (for tests that need it)
4. Run tests with coverage measurement
5. Analyze coverage gaps locally
6. Generate summary markdown
7. Post detailed comment on PR (collapsible)
8. Upload coverage reports as artifacts
9. Optional: Alert if coverage is very low

**Key Actions:**
- Uses codecov/codecov-action@v4 indirectly (available via coverage reports)
- Uses actions/github-script@v7 for commenting
- Uses actions/upload-artifact@v4 for storing reports

**Outputs:**
- PR comment with coverage analysis
- Artifact: coverage-gaps.txt (detailed report)
- Artifact: scoverage HTML reports

### 3. Documentation

#### `docs/guide/agents/coverage-gaps-guide.md`
Comprehensive user guide for contributors.

**Sections:**
- Overview and quick start
- Understanding coverage reports
- Finding files that need tests
- Guide to creating coverage-improving PRs
- Advanced usage (JSON output, Codecov API)
- Troubleshooting
- Coverage goals and targets

#### `docs/reference/testing-guide.md` (updated)
Added section linking to coverage gaps guide and explaining how to find high-impact testing opportunities.

#### `docs/reference/index.md` (updated)
Added link to coverage gaps guide in contributing section.

#### `scripts/README.md` (updated)
Documentation for all scripts in the scripts directory.

## How It Works

### Local Workflow (Contributor)

1. **Generate coverage:**
   ```bash
   sbt coverage core/test coverageAggregate
   ```

2. **Analyze locally:**
   ```bash
   python3 scripts/analyze-coverage.py --local --output gaps.txt
   cat gaps.txt
   ```

3. **Pick a low-coverage file/function** from the report

4. **Write tests** following the Testing Guide

5. **Verify improvement:**
   ```bash
   sbt coverage core/test coverageReport
   ```

6. **Submit PR** - CI workflow will analyze and post results

### CI Workflow (GitHub Actions)

1. **PR Triggers workflow** - coverage-gaps.yml runs automatically

2. **Tests run with coverage measurement:**
   ```bash
   sbt coverage core/test coverageAggregate
   ```

3. **Coverage analyzed** - Python scripts process results

4. **Results reported:**
   - PR comment with analysis
   - Artifacts uploaded
   - Summary added to workflow run

5. **Contributor reviews** results in PR comment

## Report Format

### Text Report Structure

```
================================================================================
TEST COVERAGE ANALYSIS REPORT
================================================================================

OVERALL SUMMARY
- Overall Coverage: X%
- Total Statements: N
- Covered Statements: N
- Uncovered Lines: N

MODULE COVERAGE BREAKDOWN
- Module name | Coverage % | Uncovered lines | File count

FILES WITH LOWEST COVERAGE (Top 20)
- File path | Coverage % (uncovered lines)
- Uncovered functions list

RECOMMENDATIONS FOR IMPROVING COVERAGE
- Focus on low-coverage modules
- Specific high-impact files to target
```

### PR Comment Format

```markdown
## 📊 Test Coverage Analysis

<details>
<summary>Click to view coverage gaps</summary>

[Text report in code block]

</details>

📚 **Tips for improving coverage:**
- Focus on files with lowest coverage
- Add tests for uncovered functions
- Run: `sbt coverage core/test coverageReport`
- See Testing Guide
```

## Integration Points

### Build System (SBT)
- Uses existing `coverage` alias: `sbt coverage core/test coverageAggregate`
- Generates scoverage.xml at standard locations

### Version Control (Git)
- Triggered on PR to main/master
- Posts results on PR
- No permanent artifacts stored (30-day retention)

### Documentation Site
- Links from Testing Guide
- Linked in Reference section
- Part of contributor onboarding path

### Testing Framework
- Works with existing ScalaTest setup
- No changes needed to test code
- Purely analysis of existing coverage data

## Configuration

### Codecov.yml
No changes needed to existing config, but worth noting:
- Flags for per-module tracking still work
- PR comments still posted by codecov-action
- Coverage gaps tool complements existing Codecov integration

### Coverage Thresholds
- CI checks: 50% overall (build.sbt: `coverageMinimumStmtTotal := 50`)
- Can be adjusted per project need
- Coverage gaps tool doesn't enforce thresholds, just reports

### Scalacov Exclusions
- Current exclusions in build.sbt:
  ```scala
  "org\\.llm4s\\.runner\\..*",
  "org\\.llm4s\\.samples\\..*",
  "org\\.llm4s\\.workspace\\..*"
  ```
- These modules excluded because they're not published libraries
- Coverage tool respects SBT configuration

## Error Handling

### Script Robustness
- Graceful failures for missing files
- Helpful error messages
- Exit codes for CI integration
- No external dependencies (no pip install needed)

### CI Robustness
- Continue on non-critical errors
- Always upload artifacts
- Warnings vs. errors clearly differentiated

## Performance

### Local Analysis
- Typical: < 5 seconds
- Scales well with codebase size
- Minimal memory footprint

### CI Workflow
- Total time: ~3-5 minutes (dominated by test run)
- Analysis script: < 1 minute
- Report generation: < 10 seconds

## Maintenance

### Script Updates

If analysis logic needs changes:
1. Update `scripts/analyze-coverage.py`
2. Run locally to verify: `python3 scripts/analyze-coverage.py --local`
3. Test wrapper scripts work on all platforms
4. Update documentation if output format changes

### Workflow Updates

If CI behavior needs changes:
1. Update `.github/workflows/coverage-gaps.yml`
2. Test with workflow_dispatch trigger
3. Verify PR comment format still helpful
4. Update docs if new features added

### Documentation Updates

Keep docs current:
- Update when features/options change
- Update when workflow structure changes
- Keep example output current
- Test links regularly

## Extensibility

### Possible Enhancements

1. **Historical tracking:**
   - Store coverage data over time
   - Generate trend graphs
   - Identify regression areas

2. **Codecov integration:**
   - Fetch data from Codecov API
   - Compare across branches
   - Identify high-impact areas

3. **Smart recommendations:**
   - ML to predict coverage increase from tests
   - Priority scoring for functions
   - Estimate time to improve coverage

4. **Integration with issues:**
   - Create issues for low coverage areas
   - Link coverage to issue discussions
   - Track coverage improvement tasks

5. **Custom rules:**
   - Mark functions as "TODO: test"
   - Require tests for APIs
   - Skip certain modules in analysis

## Support and Documentation

### For Contributors
- [Coverage Gaps Guide](../docs/guide/agents/coverage-gaps-guide.md)
- [Testing Guide](../docs/reference/testing-guide.md)
- [Scripts README](../scripts/README.md)

### For Maintainers
- This document (implementation guide)
- Inline comments in Python scripts
- CI workflow documentation

### For Issues/Questions
- GitHub Issues for bugs
- Discussions for questions
- PRs welcome for improvements

## Testing the Implementation

### Local Testing
```bash
# Generate coverage
sbt coverage core/test coverageAggregate

# Test analysis script
python3 scripts/analyze-coverage.py --local

# Test wrapper scripts
./scripts/analyze-coverage.sh --local
scripts\analyze-coverage.bat --local

# Test JSON output
python3 scripts/analyze-coverage.py --local --json
```

### CI Testing
```bash
# Trigger workflow manually via Actions UI
# Or push PR to test branch
# Verify PR comment appears
# Check artifacts upload
```

## Future Roadmap

- [ ] Dashboard for coverage trends
- [ ] Historical comparison across sprints
- [ ] Integration with code coverage standards
- [ ] Custom alerts for critical modules
- [ ] Integration with project management tools
