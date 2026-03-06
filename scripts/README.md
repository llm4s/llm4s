# Scripts Directory

This directory contains utility scripts to help with development, testing, and analysis of the llm4s project.

## Coverage Analysis Scripts

### `analyze-coverage.py` / `analyze-coverage.sh` / `analyze-coverage.bat`

Analyzes test coverage reports and identifies gaps in test coverage.

**Usage:**

```bash
# Linux/macOS
./scripts/analyze-coverage.sh --local

# Windows
scripts\analyze-coverage.bat --local

# Or directly with Python
python3 scripts/analyze-coverage.py --local
```

**Features:**
- Analyzes scoverage XML reports
- Identifies lowest-coverage files and functions
- Provides module-by-module breakdown
- Generates actionable recommendations
- Supports JSON output for automation

**Common options:**
- `--local` - Analyze local scoverage reports (default)
- `--output FILE` - Save report to file
- `--json` - Output as JSON
- `--root DIR` - Search in different directory

**Example:**

```bash
# Generate coverage data
sbt coverage core/test coverageAggregate

# Analyze and save report
python3 scripts/analyze-coverage.py --local --output coverage-gaps.txt
cat coverage-gaps.txt
```

See [Coverage Gaps Guide](../docs/guide/agents/coverage-gaps-guide.md) for detailed documentation.

### `generate-coverage-summary.py`

Generates a markdown summary of coverage for GitHub Actions workflow summaries.

**Usage:**

```bash
python3 scripts/generate-coverage-summary.py
```

Creates `coverage-summary.md` with formatted coverage statistics.

**Features:**
- Parses scoverage XML files
- Generates module-by-module coverage table
- Creates GitHub Actions workflow summary
- Includes visual indicators (🟢 🟡 🔴)

## Data / Utility Scripts

### `download-datasets.sh`

Downloads datasets used in llm4s examples and tests.

**Usage:**

```bash
./scripts/download-datasets.sh
```

## Dependencies

### For Coverage Analysis

- **Python 3.8+**
  - No external dependencies required (uses only standard library)
  - xml.etree for XML parsing
  - json for JSON output
  - urllib for Codecov API (optional)

### For Other Scripts

- **bash** - Bash 4.0+ recommended for shell scripts
- **curl** - For downloading files (in `download-datasets.sh`)

## Adding New Scripts

When adding new scripts:

1. Use appropriate language:
   - Python for data analysis/processing
   - Shell/Bash for automation
   - Bat for Windows-specific tasks

2. Include:
   - Clear usage documentation in comments
   - Error handling
   - Help text (`--help` flag)
   - Exit codes

3. Update this README with:
   - Script name and purpose
   - Usage examples
   - Key features
   - Dependencies (if any)

## Troubleshooting

### Python scripts not finding coverage files

Ensure you've generated coverage data first:

```bash
sbt coverage core/test coverageAggregate
```

Files should be in:
- `target/scala-3.7.1/scoverage-report/scoverage.xml`
- `modules/*/target/scala-3.7.1/scoverage-report/scoverage.xml`

### Python not found

Ensure Python 3 is installed and available:

```bash
# Check Python installation
python3 --version

# On some systems, you might need
python --version
```

Update the script shebang or wrapper if needed.

## Performance Notes

Coverage analysis scripts are optimized for speed:

- XML parsing is streaming where possible
- Large files are processed efficiently
- No unnecessary file I/O

For large projects, analysis typically completes in < 5 seconds.

## Integration with CI/CD

These scripts are automatically run in GitHub Actions workflows:

- **coverage-gaps.yml** - Analyzes coverage on PRs
- Posts results as PR comments
- Uploads detailed reports as artifacts

See [Continuous Integration](../docs/reference/contributing.html#continuous-integration) for details.
