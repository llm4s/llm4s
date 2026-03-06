#!/bin/bash
# Wrapper script for coverage analysis
# Makes it easier to run the Python coverage analysis from any directory

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Check if Python 3 is available
if ! command -v python3 &> /dev/null; then
    echo "Error: Python 3 is not installed or not in PATH"
    exit 1
fi

# Change to project root for file discovery
cd "$PROJECT_ROOT"

# Run the analysis script with all passed arguments
python3 "$SCRIPT_DIR/analyze-coverage.py" "$@"
