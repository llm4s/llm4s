---
layout: page
title: Workspace Agent Protocol
parent: API Reference
---

# Workspace Agent Protocol (WAP) Specification

Version: 1.0

## Introduction

The Workspace Agent Protocol (WAP) defines a standardized interface for Large Language Models (LLMs) to interact with code workspaces. This protocol enables LLMs to explore, read, write, and manipulate files, as well as execute commands within a development environment.

When acting as a Software Engineering assistant, you can use these tools to interact with the user's workspace. Each tool includes detailed parameters and returns structured information to help you assist efficiently. The protocol is designed with hard limits to ensure performance and safety.

## General Guidelines

- All file paths should be specified relative to the workspace root unless otherwise specified
- Default exclusion patterns apply to recursive operations
- All operations have hard limits on returned data to protect against excessively large responses
- When results are truncated, you should inform the user and suggest ways to narrow the scope

## Core Tools

### 1. `exploreFiles`

**Description**: Lists files and directories in a specified path, optionally recursively.

**Parameters**:
```typescript
{
  path: string;               // Starting directory path
  recursive?: boolean;        // Whether to explore recursively
  excludePatterns?: string[]; // Glob patterns to exclude (e.g. ["**/node_modules/**", "**/.git/**"])
  maxDepth?: number;          // Maximum recursion depth (default: 3)
  returnMetadata?: boolean;   // Whether to include file metadata
}
```

**Response**:
```typescript
{
  files: Array<{
    path: string;             // Path to the file or directory
    isDirectory: boolean;     // Whether this is a directory
    metadata?: {              // Optional metadata (if requested)
      path: string;           // Absolute path to file
      size: number;           // Size in bytes
      isDirectory: boolean;   // Whether this is a directory
      lastModified: string;   // ISO timestamp of last modification
    };
  }>;
  isTruncated: boolean;       // Whether results were truncated due to limits
  totalFound: number;         // Total number found (including truncated)
}
```

**Usage Examples**:
```
// List all files in the src directory
exploreFiles({ path: "src" })

// Recursively explore the project, excluding node_modules
exploreFiles({ 
  path: ".", 
  recursive: true, 
  excludePatterns: ["**/node_modules/**", "**/.git/**"] 
})
```

### 2. `readFile`

**Description**: Reads the content of a file, with options to read specific line ranges.

**Parameters**:
```typescript
{
  path: string;               // Path to file
  startLine?: number;         // Optional start line (1-indexed)
  endLine?: number;           // Optional end line (1-indexed)
}
```

**Response**:
```typescript
{
  content: string;            // File content
  metadata: {                 // File metadata
    path: string;             // Absolute path to file
    size: number;             // Size in bytes
    isDirectory: boolean;     // Whether this is a directory
    lastModified: string;     // ISO timestamp of last modification
  };
  isTruncated: boolean;       // Whether content was truncated due to size limits
  totalLines: number;         // Total lines in the file
  returnedLines: number;      // Number of lines returned
}
```

**Usage Examples**:
```
// Read entire file (subject to size limits)
readFile({ path: "src/app.js" })

// Read specific lines from a file
readFile({ path: "src/app.js", startLine: 10, endLine: 20 })
```

### 3. `writeFile`

**Description**: Writes content to a file, creating the file if it doesn't exist.

**Parameters**:
```typescript
{
  path: string;               // Path to file
  content: string;            // Content to write
  mode?: "create" | "overwrite" | "append"; // Write mode (default: "overwrite")
  createDirectories?: boolean; // Create parent directories if they don't exist
}
```

**Response**:
```typescript
{
  success: boolean;           // Whether operation succeeded
  path: string;               // Path of written file
  bytesWritten: number;       // Number of bytes written
}
```

**Usage Examples**:
```
// Create or overwrite a file
writeFile({ 
  path: "src/utils/helper.js", 
  content: "function helper() { return true; }", 
  createDirectories: true 
})

// Append to a file
writeFile({ 
  path: "logs/app.log", 
  content: "New log entry", 
  mode: "append" 
})
```

### 4. `modifyFile`

**Description**: Performs targeted modifications to a file without rewriting the entire content.

**Parameters**:
```typescript
{
  path: string;                // Path to file
  operations: Array<          // List of operations to perform
    | { 
        type: "replace";      // Replace lines in the file
        startLine: number;    // Start line to replace (1-indexed)
        endLine: number;      // End line to replace (1-indexed)
        newContent: string;   // New content to insert
      }
    | { 
        type: "insert";       // Insert after a specific line
        afterLine: number;    // Line after which to insert (1-indexed)
        newContent: string;   // Content to insert
      }
    | { 
        type: "delete";       // Delete lines from the file
        startLine: number;    // Start line to delete (1-indexed)
        endLine: number;      // End line to delete (1-indexed)
      }
    | { 
        type: "regexReplace"; // Replace using regex
        pattern: string;      // Regex pattern to match
        replacement: string;  // Replacement string
        flags?: string;       // Regex flags (e.g., "g" for global)
      }
  >;
}
```

**Response**:
```typescript
{
  success: boolean;           // Whether operation succeeded
  path: string;               // Path of modified file
}
```

**Usage Examples**:
```
// Replace lines 5-10 with new content
modifyFile({
  path: "src/components/Button.jsx",
  operations: [
    {
      type: "replace",
      startLine: 5,
      endLine: 10,
      newContent: "// New implementation\nfunction Button() {\n  return <button>Click me</button>;\n}"
    }
  ]
})

// Replace all instances of a deprecated API
modifyFile({
  path: "src/api/client.js",
  operations: [
    {
      type: "regexReplace",
      pattern: "oldAPI\\.method\\(([^)]+)\\)",
      replacement: "newAPI.improvedMethod($1)",
      flags: "g"
    }
  ]
})
```

### 5. `searchFiles`

**Description**: Searches for content in files across the workspace.

**Parameters**:
```typescript
{
  paths: string[];            // Paths to search in
  query: string;              // Search query
  type: "regex" | "literal";  // Search type
  recursive?: boolean;        // Whether to search recursively
  excludePatterns?: string[]; // Glob patterns to exclude
  contextLines?: number;      // Number of context lines to include
}
```

**Response**:
```typescript
{
  matches: Array<{
    path: string;             // Path to file with match
    line: number;             // Line number of match (1-indexed)
    matchText: string;        // Matched text
    contextBefore: string[];  // Lines before match
    contextAfter: string[];   // Lines after match
  }>;
  isTruncated: boolean;       // Whether results were truncated due to limits
  totalMatches: number;       // Total matches found (including truncated)
}
```

**Usage Examples**:
```
// Search for a specific string across all JS files
searchFiles({
  paths: ["src"],
  query: "TODO:",
  type: "literal",
  recursive: true,
  excludePatterns: ["**/node_modules/**"],
  contextLines: 2
})

// Use a regex to find all API calls
searchFiles({
  paths: ["src/api"],
  query: "api\\.(get|post|put|delete)\\(",
  type: "regex",
  recursive: true
})
```

### 6. `executeCommand`

**Description**: Executes a shell command in the workspace.

**Parameters**:
```typescript
{
  command: string;            // Command to execute
  workingDirectory?: string;  // Working directory (default: workspace root)
  timeout?: number;           // Timeout in milliseconds
  environment?: Record<string, string>; // Environment variables
}
```

**Response**:
```typescript
{
  stdout: string;             // Standard output
  stderr: string;             // Standard error
  exitCode: number;           // Exit code
  isOutputTruncated: boolean; // Whether output was truncated
  durationMs: number;         // Execution duration in milliseconds
}
```

**Usage Examples**:
```
// Run tests in a specific directory
executeCommand({
  command: "npm test",
  workingDirectory: "packages/frontend"
})

// Install a dependency
executeCommand({
  command: "npm install lodash --save",
  timeout: 60000
})
```

### 7. `getWorkspaceInfo`

**Description**: Retrieves information about the workspace, including default settings and limits.

**Parameters**:
```typescript
{}  // No parameters required
```

**Response**:
```typescript
{
  root: string;               // Workspace root path
  defaultExclusions: string[]; // Default exclusion patterns
  limits: {
    maxFileSize: number;      // Maximum file size in bytes for reading
    maxDirectoryEntries: number; // Maximum directory entries for listing
    maxSearchResults: number; // Maximum search results
    maxOutputSize: number;    // Maximum command output size in bytes
  };
}
```

**Usage Examples**:
```
// Get workspace information
getWorkspaceInfo()
```

## Error Handling

All tools may return errors in the following format:

```typescript
{
  error: string;              // Error message
  code: string;               // Error code (e.g. "FILE_NOT_FOUND", "SIZE_LIMIT_EXCEEDED")
  details?: any;              // Optional error details
}
```

Common error codes include:
- `FILE_NOT_FOUND`: The specified file does not exist
- `PERMISSION_DENIED`: Insufficient permissions to perform the operation
- `SIZE_LIMIT_EXCEEDED`: Operation exceeded a size or count limit
- `INVALID_ARGUMENT`: An argument provided is invalid
- `EXECUTION_FAILED`: Command execution failed
- `TIMEOUT`: Operation timed out

## Recommended System Limits

Implementors should consider the following recommended limits:

```json
{
  "maxFileSize": 1048576,         // 1MB max file size
  "maxDirectoryEntries": 500,     // 500 max entries in directory listing
  "maxSearchResults": 100,        // 100 max search results
  "maxOutputSize": 1048576,       // 1MB max command output
  "maxExecutionTime": 30000       // 30 seconds max execution time
}
```

## Default Exclusion Patterns

The following default exclusion patterns are recommended:

```json
[
  "**/node_modules/**",
  "**/.git/**",
  "**/dist/**",
  "**/build/**",
  "**/.venv/**",
  "**/target/**",
  "**/__pycache__/**",
  "**/vendor/**"
]
```

## Best Practices for LLM Agents

When using these tools, follow these best practices:

1. **Start with exploration**: First understand the workspace structure before making changes
2. **Read before modifying**: Always read a file before attempting to modify it
3. **Targeted operations**: Use line ranges when possible to work with specific parts of files
4. **Be mindful of limits**: If results are truncated, use more specific paths or queries
5. **Error handling**: When operations fail, check the error code and provide helpful guidance to the user
6. **Atomicity**: Perform one logical change at a time when modifying files
7. **Verify changes**: After making modifications, read the file again to ensure changes were applied correctly

## Future Extensions

This specification covers version 1.0 of the protocol focusing on core file and workspace operations. Future versions may add support for:

1. Advanced code analysis tools
2. Semantic search capabilities
3. Version control operations
4. Project-specific operations
5. Language-specific tooling
6. Pagination for large results
