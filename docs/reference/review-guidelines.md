---
layout: page
title: Code Review Guidelines
nav_order: 9
parent: Reference
---

# Code Review Guidelines

This document captures coding best practices derived from PR review feedback, particularly from experienced contributors. Following these guidelines will help your PRs pass review faster.

---

## General Contribution Philosophy

### Keep It Simple

LLM4S follows a **soft functional** style: immutable data and pure functions by default, without being religious about pure FP. Pragmatism over purity.

- Prefer immutable data structures
- Write pure functions where practical
- Use `Result[A]` for error handling instead of exceptions
- Don't over-abstract or over-engineer
- If the simple solution works, use it

### Quality Over Speed

In a library/SDK, security bugs and tech debt accumulate quickly. We prefer slower, careful merges over rapid churn.

**Signs of rushed PRs that will need revision:**
- Lots of changes with weak explanation of the "why"
- Missing edge cases and failure tests
- Auto-generated or tool-produced code without human review
- Changes to areas the contributor hasn't fully understood

**Reviews are teaching moments** - especially for changes touching core, config, security, concurrency, or public APIs.

---

## PR Best Practices

### Keep PRs Small and Focused

**One change, one reason.** Large PRs are hard to review and often hide bugs.

- Each PR should do one thing well
- If you're fixing a bug, just fix the bug - don't refactor nearby code
- If you're adding a feature, don't also reorganize the package structure
- Big refactors should start as an issue or design note for discussion first

### Explain the "Why"

Your PR description should answer:

- **Why** is this change needed?
- **What** tradeoffs did you consider?
- **What** did you test? How?
- **What** could break?

A PR with "Add feature X" and no context will get more questions and take longer to merge.

### Test New Behavior and Failure Cases

Every PR with new code needs tests. This includes:

- Happy path tests
- Edge cases (empty inputs, nulls, boundary values)
- **Failure cases** - what happens when things go wrong?
- Concurrency tests if applicable

See the [Testing Guide](testing-guide.md) for details.

### Be Careful with Public APIs

Avoid changing public APIs unless clearly needed. When you do:

- **Call it out explicitly** in the PR description
- Explain why the change is necessary
- Consider backward compatibility
- Document migration path if breaking

Adding a method to a trait is a breaking change for implementers. Adding a required parameter breaks all callers.

---

## Dependency Management

### Don't Add Heavy Dependencies to Core

The core module should stay lean. Before adding a dependency:

- **Discuss with maintainers first** - open an issue
- Consider if it can be optional via `build.sbt`
- If optional, keep it in a separate module

```scala
// BAD - Adding heavyweight dependency to core
libraryDependencies += "org.heavy" %% "framework" % "1.0"

// GOOD - Optional module for optional functionality
lazy val traceOpenTelemetry = project
  .in(file("modules/trace-opentelemetry"))
  .dependsOn(core)
  .settings(
    libraryDependencies += "io.opentelemetry" % "opentelemetry-sdk" % "1.0"
  )
```

### Use Existing Dependencies

Before adding a new library, check if an existing dependency already provides what you need:

- **JSON**: Use `ujson` (already included)
- **HTTP client**: Use `sttp` (already included)
- **Config**: Use `PureConfig` (already included)
- **Testing**: Use `ScalaTest` (already included)

---

## Code Quality Guidelines

### 1. Error Handling - Use `Result[A]`, Not Exceptions

LLM4S uses `Result[A]` (an alias for `Either[LLMError, A]`) for error handling. Avoid throwing exceptions for control flow.

**Best Practices:**

```scala
// GOOD - Return Result directly
def parseConfig(json: String): Result[Config] =
  if (json.isEmpty) Left(ValidationError("Empty JSON"))
  else Right(Config.parse(json))

// BAD - Throwing inside Try
def parseConfig(json: String): Result[Config] = Try {
  if (json.isEmpty) throw new IllegalArgumentException("Empty")
  Config.parse(json)
}.toResult

// GOOD - Use .toResult for external code that may throw
Try(externalLibrary.parse(json)).toResult
```

**Preserve error causes:**

```scala
// BAD - Dropping the cause
Try { ... }.toEither.left.map(e => ProcessingError(e.getMessage))

// GOOD - Preserve stack traces
Try { ... }.toEither.left.map(e => ProcessingError(e.getMessage, cause = Some(e)))
```

---

### 2. Configuration - Dependency Injection, Not Direct Access

Core code should not read configuration directly. Configuration loading belongs at the application edge (samples, CLIs, tests), and typed settings should be injected into core code.

**Best Practices:**

```scala
// BAD - Reading config in tool/core code
class BraveSearchTool {
  def search(query: String): Result[String] = {
    val config = Llm4sConfig.loadBraveSearchTool()  // DON'T DO THIS
    // ...
  }
}

// GOOD - Accept config as parameter (DI style)
object BraveSearchTool {
  def create(config: BraveToolConfig): BraveSearchTool =
    new BraveSearchTool(config)
}

// At app edge (samples/CLI):
for {
  config <- Llm4sConfig.loadBraveSearchTool()
  tool = BraveSearchTool.create(config)
} yield tool
```

Scalafix enforces this: imports of `Llm4sConfig`, `ConfigSource.default`, `sys.env`, or `System.getenv` are blocked in core main sources (except inside `org.llm4s.config`).

---

### 3. Type Safety - Avoid `Any`

Using `Any` hides type errors until runtime. Prefer typed alternatives.

**Best Practices:**

```scala
// BAD - Using Any
def setParameter(stmt: PreparedStatement, idx: Int, value: Any): Unit

// GOOD - Use a typed ADT
sealed trait SqlParam
case class StringParam(value: String) extends SqlParam
case class IntParam(value: Int) extends SqlParam
case class TimestampParam(value: Timestamp) extends SqlParam

def setParameter(stmt: PreparedStatement, idx: Int, param: SqlParam): Unit = param match {
  case StringParam(v) => stmt.setString(idx, v)
  case IntParam(v) => stmt.setInt(idx, v)
  case TimestampParam(v) => stmt.setTimestamp(idx, v)
}

// GOOD - For JSON, use ujson types instead of Any
val params: ujson.Obj = ujson.Obj("key" -> "value")
```

---

### 4. Resource Management - Close What You Open

Resources like HTTP backends, database connections, and file handles must be properly closed to avoid leaks.

**Best Practices:**

```scala
// BAD - Resource leak
def fetchUrl(url: String): String = {
  val backend = DefaultSyncBackend()  // Never closed!
  basicRequest.get(uri"$url").send(backend).body
}

// GOOD - Using.resource ensures cleanup
def fetchUrl(url: String): String =
  Using.resource(DefaultSyncBackend()) { backend =>
    basicRequest.get(uri"$url").send(backend).body
  }

// GOOD - For JDBC
def withConnection[A](f: Connection => A): A =
  Using.resource(dataSource.getConnection)(f)
```

---

### 5. Constructor Side Effects - Keep Construction Pure

Side effects in constructors make code harder to test and reason about. Prefer explicit initialization via factory methods.

**Best Practices:**

```scala
// BAD - Side effects in constructor
class PostgresStore(config: Config) {
  initializeSchema()  // Side effect during construction
}

// GOOD - Factory method makes initialization explicit
class PostgresStore private (config: Config, dataSource: DataSource) { ... }

object PostgresStore {
  def create(config: Config): Result[PostgresStore] =
    for {
      dataSource <- initializeDataSource(config)
      _ <- initializeSchema(dataSource)
    } yield new PostgresStore(config, dataSource)
}
```

---

### 6. Thread Safety - Synchronize Iterations

When using synchronized collections, individual operations are thread-safe but iterations are not.

**Best Practices:**

```scala
// BAD - Iteration not thread-safe
val syncMap = Collections.synchronizedMap(new LinkedHashMap)
syncMap.entrySet().asScala.foreach { ... }  // UNSAFE!

// GOOD - Synchronize the entire iteration
syncMap.synchronized {
  syncMap.entrySet().asScala.foreach { ... }
}

// BETTER - Use proper concurrent collections
val cache = new ConcurrentHashMap[K, V]()
```

---

### 7. JSON Handling - Use Libraries, Don't Roll Your Own

Manual JSON encoding is error-prone and misses edge cases. Use ujson, which is already a project dependency.

**Best Practices:**

```scala
// BAD - Manual JSON encoding (misses escapes!)
def toJson(map: Map[String, String]): String =
  map.map { case (k, v) => s""""$k":"${v.replace("\"", "\\\"")}"""" }.mkString("{", ",", "}")

// GOOD - Use ujson
import ujson._

def toJson(map: Map[String, String]): String = ujson.write(map)

def fromJson(json: String): Map[String, String] =
  ujson.read(json).obj.map { case (k, v) => k -> v.str }.toMap
```

---

### 8. Validation - Validate Config and Inputs

Validate inputs early with clear error messages. This prevents security issues and improves debuggability.

**Best Practices:**

```scala
// GOOD - Validate in config constructors
case class CacheConfig(
  similarityThreshold: Double,
  ttlSeconds: Long,
  maxSize: Int
) {
  require(similarityThreshold >= 0.0 && similarityThreshold <= 1.0,
    s"threshold must be 0-1, got $similarityThreshold")
  require(ttlSeconds > 0, s"ttl must be positive, got $ttlSeconds")
  require(maxSize > 0, s"maxSize must be positive, got $maxSize")
}

// GOOD - Validate for security (e.g., SQL injection prevention)
private val ValidTableNamePattern = "^[a-zA-Z_][a-zA-Z0-9_]{0,62}$".r

case class Config(tableName: String) {
  require(ValidTableNamePattern.matches(tableName),
    s"Invalid table name: must match [a-zA-Z_][a-zA-Z0-9_]*")
}
```

---

### 9. Silent Failures - Fail Loudly or Log Clearly

Silent failures hide bugs and confuse users. Make failures explicit through errors or logging.

**Best Practices:**

```scala
// BAD - Silent failure
case _ => ("FALSE", Seq.empty)  // Returns nothing, user doesn't know why

// GOOD Option A - Throw/return error to make it explicit
case unsupported =>
  throw new UnsupportedOperationException(
    s"Filter ${unsupported.getClass.getSimpleName} not supported")

// GOOD Option B - Log warning and return sensible default
case unsupported =>
  logger.warn(s"Unsupported filter: ${unsupported.getClass.getSimpleName}")
  ("TRUE", Seq.empty)  // Return all, not empty
```

---

### 10. Testing Best Practices

Every new feature needs unit tests. See the [Testing Guide](testing-guide.md) for comprehensive guidance.

**Key points:**

- Every PR with new code should include tests
- Avoid `Thread.sleep` in tests - use controlled time
- Don't use `???` in mocks - use `Left(...)` or proper stubs
- Test edge cases: empty inputs, special characters, boundary values
- Never call real LLM APIs in unit tests

```scala
// BAD - Sharp edge in mock
def complete(conv: Conversation): Result[Response] = ???

// GOOD - Safe stub
def complete(conv: Conversation): Result[Response] =
  Left(ProcessingError("Mock not configured for this call"))
```

---

### 11. Naming and Config Consistency

Maintain consistent naming between configuration files and Scala code.

**Best Practices:**

- Config keys should match Scala field names (PureConfig convention)
- Be consistent: `openTelemetry` in both HOCON and Scala, not mixed
- Update comments when changing behavior
- Keep naming consistent across related components

---

### 12. Don't Commit Generated/Large Files

Generated files and logs should never be committed to the repository.

**Best Practices:**

- Add generated files to `.gitignore`
- Never commit log files, build artifacts, or large binary files
- Review `git status` before committing

---

### 13. Sensitive Data in Telemetry/Logging

Be careful not to leak sensitive data through logs or telemetry.

**Best Practices:**

- Truncate or redact potentially sensitive data before logging/tracing
- Don't export full LLM responses or tool outputs to external systems
- Be careful with parameters that might contain API keys
- Consider what data could end up in error messages

---

### 14. API Stability - Consider Breaking Changes

Changes to public traits and classes can break downstream code.

**Best Practices:**

- Adding new methods or parent traits is a breaking change
- Prefer optional methods with defaults: `def shutdown(): Unit = ()`
- Document breaking changes in release notes
- Consider backward compatibility for widely-used APIs

---

### 15. Idiomatic Scala Patterns

Prefer idiomatic Scala over imperative Java-style code.

**Best Practices:**

```scala
// Less idiomatic
val results = new ArrayBuffer[Row]()
while (rs.next()) {
  results += extractRow(rs)
}
results.toSeq

// More idiomatic
Iterator.continually(rs).takeWhile(_.next()).map(extractRow).toSeq
```

---

### 16. Avoid Casting - Use Pattern Matching

Direct casting with `asInstanceOf` hides type errors and can cause runtime `ClassCastException`. Use pattern matching with proper error handling instead.

**Best Practices:**

```scala
// BAD - Direct casting can fail at runtime
val connection: HttpURLConnection =
  url.openConnection().asInstanceOf[HttpURLConnection]

// GOOD - Pattern match and return Result
val connection: Result[HttpURLConnection] = url.openConnection() match {
  case httpConn: HttpURLConnection => Right(httpConn)
  case other => Left(ProcessingError(
    s"Expected HttpURLConnection but got ${other.getClass.getName}"))
}

// GOOD - When you're certain of the type, use collect with match
val httpConnections: Seq[HttpURLConnection] = connections.collect {
  case http: HttpURLConnection => http
}
```

**Why this matters:**
- `asInstanceOf` throws `ClassCastException` at runtime if types don't match
- Pattern matching makes the type check explicit and handles failures gracefully
- Easier to debug when you get a proper error message instead of a stack trace

---

## PR Submission Checklist

Before submitting a PR, verify:

**PR Quality:**
- [ ] PR is small and focused - one change, one reason
- [ ] PR description explains the "why", tradeoffs, and what was tested
- [ ] No unnecessary refactoring bundled with the change
- [ ] Breaking API changes are called out explicitly

**Code Quality:**
- [ ] No exceptions for control flow - use `Result[A]`
- [ ] Config loaded at app edge, injected into core code
- [ ] No `Any` types - use ADTs or typed collections
- [ ] No `asInstanceOf` casting - use pattern matching
- [ ] Resources closed with `Using.resource()`
- [ ] No side effects in constructors
- [ ] Thread-safe iteration on synchronized collections
- [ ] JSON handled with ujson, not manual string building
- [ ] Input validation with clear error messages
- [ ] No silent failures - log or error explicitly

**Testing:**
- [ ] Unit tests for new behavior
- [ ] Tests for failure/edge cases
- [ ] Tests pass with `sbt +test`

**Hygiene:**
- [ ] No new heavy dependencies in core without discussion
- [ ] No generated files committed
- [ ] Sensitive data not leaked to logs/traces
- [ ] Code formatted with `sbt scalafmtAll`

---

## Related Documentation

- [Testing Guide](testing-guide.md) - Detailed testing practices
- [Configuration Boundary](configuration-boundary.md) - Config architecture
- [Scalafix Rules](scalafix.md) - Automated enforcement

---

*This guide is based on actual PR review feedback. Suggestions for improvement are welcome.*
