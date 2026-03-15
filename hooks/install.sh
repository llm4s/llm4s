#!/bin/bash

# Installation script for LLM4S pre-commit hook

HOOK_DIR="$(dirname "$0")"
GIT_HOOK_DIR=".git/hooks"

# Check if we're in a git repository
if [ ! -d ".git" ]; then
    echo "Error: Not in a git repository root directory"
    echo "Please run this script from the repository root"
    exit 1
fi

# Create hooks directory if it doesn't exist
mkdir -p "$GIT_HOOK_DIR"

# Copy the pre-commit hook
cp "$HOOK_DIR/pre-commit" "$GIT_HOOK_DIR/pre-commit"
chmod +x "$GIT_HOOK_DIR/pre-commit"

echo "Pre-commit hook installed successfully!"
echo "The hook will run automatically before each commit to:"
echo "  - Detect which subprojects are affected by staged changes"
echo "  - Check formatting, compile, and test only those subprojects"
echo "  - Compile downstream dependents when source files change"
echo "  - Fall back to a full build when build config changes"
echo ""
echo "To skip the hook temporarily, use: git commit --no-verify"