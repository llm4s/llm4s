import sbt._

import java.net.URLClassLoader

/**
 * Test tiers for `modules/it`.
 *
 * Each tier names the external thing a suite needs - nothing, a containerised service, a
 * locally built image, a local model server, or a paid API key - and therefore which command
 * and which CI job runs it. Membership is declared by annotating the suite class with the
 * matching tag in `org.llm4s.it.tags`, never by a name pattern in a `testOnly` argument: a
 * suite that matches no pattern is run by nothing and says nothing about it, which is how 11
 * of 18 suites here came to be dead weight (issue #1143).
 *
 * `check` turns that silence into a build failure, in the same spirit as `coveragePolicyCheck`.
 */
object ItTiers {

  val Local     = "org.llm4s.it.tags.Local"
  val Docker    = "org.llm4s.it.tags.Docker"
  val Workspace = "org.llm4s.it.tags.Workspace"
  val Ollama    = "org.llm4s.it.tags.Ollama"
  val Cloud     = "org.llm4s.it.tags.Cloud"

  val all: Seq[String] = Seq(Local, Docker, Workspace, Ollama, Cloud)

  /** The `sbt` command that runs one tier: select it by tag, replacing the Local-tier default. */
  def alias(tag: String): String =
    s""";set it / Test / test / testOptions := Seq(Tests.Argument(TestFrameworks.ScalaTest, "-n", "$tag")); it/test"""

  private def simpleName(tag: String): String = tag.split('.').last

  /**
   * Fails unless every discovered suite declares exactly one tier.
   *
   * Reads the tags off the compiled classes rather than the sources, so the check sees what
   * ScalaTest's own tag filtering will see.
   */
  def check(suites: Seq[String], classpath: Seq[File], log: Logger): Unit = {
    // Platform loader as parent: the test classpath supplies ScalaTest and the tag
    // annotations, and sbt's own loader must not shadow either of them.
    val loader = new URLClassLoader(classpath.map(_.toURI.toURL).toArray, ClassLoader.getPlatformClassLoader)
    val rows =
      try suites.sorted.map { suite =>
        val tiers = loader
          .loadClass(suite)
          .getAnnotations
          .map(_.annotationType.getName)
          .filter(all.contains)
          .toSeq
        suite -> tiers
      } finally loader.close()

    if (rows.isEmpty)
      throw new MessageOnlyException("No integration suites were discovered in modules/it - is the module compiling?")

    val width = rows.map(_._1.length).max
    log.info("Integration test tier per suite:")
    rows.foreach { case (suite, tiers) =>
      val declared = if (tiers.isEmpty) "NO TIER DECLARED" else tiers.map(simpleName).mkString(" + ")
      log.info(s"  ${suite.padTo(width, ' ')}  $declared")
    }

    val untagged = rows.collect { case (suite, Nil) => suite }
    val multiple = rows.collect { case (suite, tiers) if tiers.size > 1 => suite }

    if (untagged.nonEmpty || multiple.nonEmpty) {
      val problems =
        (if (untagged.nonEmpty) Seq(s"No tier declared for: ${untagged.mkString(", ")}") else Nil) ++
          (if (multiple.nonEmpty) Seq(s"More than one tier declared for: ${multiple.mkString(", ")}") else Nil)
      throw new MessageOnlyException(
        s"""${problems.mkString("\n")}
           |Every suite in modules/it must be annotated with exactly one tier tag, or nothing
           |runs it. Add ONE of the following above the class declaration:
           |  @Local      // needs nothing external; runs in the default `sbt test`
           |  @Docker     // needs Postgres/pgvector, Qdrant or Neo4j; `sbt testIntegration`
           |  @Workspace  // needs a built workspace-runner image; `sbt testWorkspace`
           |  @Ollama     // needs a local Ollama server;  `sbt testOllama`
           |  @Cloud      // needs live provider API keys;  `sbt testSmoke`
           |The tags live in modules/it/src/test/java/org/llm4s/it/tags/ and the tiers are
           |documented in docs/reference/testing-guide.md.""".stripMargin
      )
    }
  }
}
