import sbt._

/**
 * What a frozen module may not pull onto its users' classpath.
 *
 * The modularisation programme (#1126) split `llm4s-core` so that a 1.0 promise is scoped by artifact:
 * freezing `core` while it dragged in a document-parsing, speech, cloud-storage and database stack would
 * freeze that stack too, and a "hello world" LLM call would still put it on a Spring Boot classpath. The
 * programme's definition of done says no frozen module declares one of those dependencies, and
 * `CLAUDE.md` says core depends on no vendor SDK and must not again. Nothing enforced either, and a
 * single transitive dependency added by accident breaks them silently.
 *
 * `check` reads each frozen module's resolved runtime dependencies, transitive ones included, and fails
 * the build for a forbidden group, naming the dependency and what pulled it in. Test-scoped
 * dependencies are not on a user's classpath and are not checked.
 */
object FrozenDependencies {

  /** A group of artifacts a frozen module may not resolve, and where it belongs instead. */
  final case class Ban(group: String, why: String)

  /** Heavy dependencies the carves moved out; no frozen module may resolve them. */
  val everywhere: Seq[Ban] = Seq(
    Ban("org.apache.tika", "document parsing; belongs to llm4s-rag"),
    Ban("org.apache.poi", "document parsing; belongs to llm4s-rag"),
    Ban("org.apache.pdfbox", "document parsing; belongs to llm4s-rag"),
    Ban("org.jsoup", "HTML parsing; belongs to llm4s-rag"),
    Ban("com.alphacephei", "speech recognition (Vosk); belongs to llm4s-speech"),
    Ban("net.java.dev.jna", "native access for speech; belongs to llm4s-speech"),
    Ban("software.amazon.awssdk", "cloud SDK; belongs to llm4s-rag or llm4s-bedrock"),
    Ban("org.postgresql", "database driver; belongs to llm4s-memory-postgres or llm4s-rag"),
    Ban("org.xerial", "SQLite driver; belongs to llm4s-memory"),
    Ban("com.zaxxer", "connection pool; belongs to the Postgres modules"),
    Ban("io.prometheus", "metrics backend; belongs to llm4s-observability-prometheus"),
    Ban("io.micrometer", "metrics facade; belongs to llm4s-observability-micrometer"),
    Ban("io.opentelemetry", "tracing backend; belongs to llm4s-observability-otel"),
    Ban("org.java-websocket", "WebSocket transport; belongs to llm4s-mcp"),
    Ban("org.neo4j", "graph database driver; belongs to llm4s-knowledgegraph-neo4j"),
    Ban("com.azure", "Azure SDK; llm4s-openai reaches Azure through openai-java")
  )

  /** Vendor SDKs: each belongs to exactly one provider module and to no other frozen module. */
  val vendorSdks: Seq[(String, String)] = Seq(
    "com.openai"    -> "llm4s-openai",
    "com.anthropic" -> "llm4s-anthropic"
  )

  /** A resolved dependency, and whether its group is declared in the frozen module's own `libraryDependencies`. */
  final case class Resolved(module: ModuleID, declaredHere: Boolean)

  final case class Violation(frozen: String, dependency: ModuleID, why: String, declaredHere: Boolean)

  private def isLlm4s(m: ModuleID): Boolean = m.organization == "org.llm4s"

  /** The violations among one frozen module's resolved dependencies. */
  def violations(frozen: String, resolved: Seq[Resolved]): Seq[Violation] = {
    val bans = everywhere ++ vendorSdks.collect {
      case (group, owner) if owner != frozen =>
        Ban(group, s"vendor SDK; belongs to $owner")
    }
    for {
      r   <- resolved.sortBy(r => (r.module.organization, r.module.name))
      ban <- bans.find(b => r.module.organization == b.group || r.module.organization.startsWith(b.group + "."))
    } yield Violation(frozen, r.module, ban.why, r.declaredHere)
  }

  def check(modules: Seq[(String, Seq[Resolved])], log: Logger): Unit = {
    // An empty report would pass every check; a module always resolves at least the Scala library.
    val empty = modules.collect { case (name, resolved) if resolved.isEmpty => name }
    if (empty.nonEmpty)
      throw new MessageOnlyException(
        s"No resolved runtime dependencies for: ${empty.mkString(", ")}. The check read an empty update report."
      )

    log.info("Resolved runtime dependencies per frozen module (external, transitive):")
    val width = modules.map(_._1.length).max
    modules.foreach { case (name, resolved) =>
      log.info(s"  ${name.padTo(width, ' ')}  ${resolved.count(r => !isLlm4s(r.module))} external")
    }

    val bad = modules.flatMap { case (name, resolved) => violations(name, resolved) }
    if (bad.nonEmpty) {
      val rows = bad.map { v =>
        val via =
          if (v.declaredHere) s"declared in ${v.frozen}'s libraryDependencies"
          else "reached transitively, through a library or a project it depends on"
        s"  ${v.frozen}: ${v.dependency.organization}:${v.dependency.name}:${v.dependency.revision} (${v.why}); $via"
      }
      throw new MessageOnlyException(
        s"""${bad.size} forbidden dependency(ies) on a frozen module's classpath:
           |${rows.mkString("\n")}
           |A frozen module may not resolve a document-parsing, speech, cloud-storage, database,
           |observability-backend or foreign vendor-SDK dependency (#1126, definition of done). Move the
           |code that needs it to the module it belongs to, or, if a frozen module genuinely needs it,
           |change this list in project/FrozenDependencies.scala and say why in the same commit.
           |To find how a transitive one arrives: sbt "<module>/dependencyTree".""".stripMargin
      )
    }
  }
}
