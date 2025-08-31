# Release Process

## Creating a New Release

### 1. Tag Format
All release tags MUST use the `v` prefix format: `v0.1.11`, `v1.0.0`, etc.

### 2. Release Steps

```bash
# 1. Ensure you're on main branch with latest changes
git checkout main
git pull origin main

# 2. Create and push the version tag (ALWAYS use 'v' prefix)
git tag v0.1.11
git push origin v0.1.11

# 3. The GitHub Actions release workflow will automatically:
#    - Run all CI checks
#    - Build and sign artifacts
#    - Publish to Maven Central
#    - Build and push Docker images
```

### 3. Creating GitHub Release (Optional)

After the tag is pushed, you can create a GitHub Release:

1. Go to https://github.com/llm4s/llm4s/releases/new
2. Select the tag you just created (e.g., `v0.1.11`)
3. Set release title (e.g., "v0.1.11")
4. Add release notes
5. Click "Publish release"

### 4. Verify Release

- Check GitHub Actions: https://github.com/llm4s/llm4s/actions/workflows/release.yml
- Verify Maven Central: https://central.sonatype.com/artifact/com.thetradedesk/llm4s
- Check Docker images: https://github.com/llm4s/llm4s/pkgs/container/workspace-runner

## Troubleshooting

### Release workflow didn't trigger

- Ensure tag starts with `v` (e.g., `v0.1.11` not `0.1.11`)
- Check that tag was pushed: `git push origin v0.1.11`
- Verify workflow status at GitHub Actions page

### Re-triggering a failed release

```bash
# Delete and recreate the tag
git tag -d v0.1.11
git push origin :v0.1.11
git tag v0.1.11
git push origin v0.1.11
```

## Version Numbering

We follow semantic versioning (MAJOR.MINOR.PATCH):
- MAJOR: Breaking API changes
- MINOR: New features, backwards compatible
- PATCH: Bug fixes, backwards compatible

Current version series: 0.1.x (pre-1.0 development)