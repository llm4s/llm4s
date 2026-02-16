# Workspace Sandbox Configuration

The LLM4S workspace subsystem provides powerful capabilities (read/write files, execute commands, search) that require explicit sandboxing and security configuration.

## Overview

- **WorkspaceSandboxConfig**: Explicit configuration describing allowed paths, resource limits, shell access, and timeouts
- **Validation at startup**: Config is validated when the runner starts; invalid config falls back to permissive
- **Enforcement**: Runner enforces limits and shell allowance; file path boundaries are enforced via `resolvePath`

## Configuration

### Environment Variables (Runner)

When running the workspace runner (e.g. in Docker):

| Variable | Description | Default |
|----------|-------------|---------|
| `WORKSPACE_PATH` | Workspace root directory | `/workspace` |
| `WORKSPACE_SANDBOX_PROFILE` | Sandbox profile: `permissive` or `locked` | `permissive` |

### Profiles

- **permissive**: Current behavior—shell allowed, standard limits (1MB file size, 500 dir entries, 30s command timeout)
- **locked**: Read-only file ops only; shell disabled; strict limits (10s timeout)

### HOCON (Client)

For the workspace client, sandbox config can be loaded via `WorkspaceConfigSupport.loadSandboxConfig()`:

```hocon
llm4s.workspace.sandbox {
  profile = "locked"  # or "permissive"
}
```

## WorkspaceSandboxConfig Structure

| Field | Type | Description |
|-------|------|-------------|
| `limits` | WorkspaceLimits | maxFileSize, maxDirectoryEntries, maxSearchResults, maxOutputSize |
| `excludePatterns` | List[String] | Glob patterns excluded from explore/search (e.g. node_modules, .git) |
| `shellAllowed` | Boolean | Whether executeCommand is allowed |
| `defaultCommandTimeoutSeconds` | Int | Default timeout for shell commands |
| `readOnlyPaths` | List[String] | Paths under workspace that are read-only (Phase 2) |
| `allowedPaths` | List[String] | If non-empty, only these paths accessible (Phase 2) |
| `networkAllowed` | Boolean | Documentation only; Phase 2: enforce network restrictions |

## Security Gaps Addressed

| Gap | Phase 1 | Phase 2 |
|-----|---------|---------|
| Explicit config | ✓ WorkspaceSandboxConfig | |
| Validation at startup | ✓ | |
| Shell allow/block | ✓ shellAllowed | |
| Resource limits | ✓ limits configurable | |
| Read-only areas | Config present | Enforcement |
| Allowed/blocked paths | Config present | Enforcement |
| Network restrictions | Documentation only | Enforcement |

## Example: Locked-Down Sandbox

Run the minimal demo (local filesystem, no Docker):

```bash
sbt "workspaceSamples/runMain org.llm4s.samples.workspace.LockedDownSandboxDemo"
```

Run the containerized runner with locked sandbox:

1. After `sbt workspaceRunner/docker:publishLocal`, get the image tag:
   ```bash
   docker images llm4s/workspace-runner --format "{{.Tag}}"
   ```
   Use that tag (e.g. `0.1.0-SNAPSHOT` or `0.1.0-abc123-SNAPSHOT`) in place of `TAG` below.

2. Run the container (replace `TAG` and the host path to your workspace):
   ```bash
   docker run --rm -e WORKSPACE_SANDBOX_PROFILE=locked -v /path/to/workspace:/workspace -p 8080:8080 llm4s/workspace-runner:TAG
   ```
   On Windows with Docker Desktop, use a path Docker can mount (e.g. `C:\Users\you\workspace` or `//c/Users/you/workspace` depending on your setup).

## Phased Implementation Plan

### Phase 1: Config + docs + sample ✓
- **Affected**: workspaceShared, workspaceRunner, workspaceClient, workspaceSamples, docs
- **Complexity**: Low
- **Risks**: Minimal; backward compatible (default = permissive)

### Phase 2: Enforcement
- **Affected**: WorkspaceAgentInterfaceImpl (readOnlyPaths, allowedPaths), tools
- **Complexity**: Medium
- **Risks**: Path validation edge cases; breaking changes if strict

### Phase 3: Advanced policies (optional)
- **Affected**: New policies module, profiles (dev/staging/prod)
- **Complexity**: High
- **Risks**: Over-engineering; maintenance burden
