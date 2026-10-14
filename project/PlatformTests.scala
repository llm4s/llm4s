import sbt._
import sbt.Keys._

/**
 * The tests CI's Windows job runs: `sbt testPlatform`.
 *
 * Windows runs the same JVM as Linux, so a test of pure JVM logic - parsing, a state machine, a provider's JSON -
 * tells nothing new there, and the whole suite took 16-18 minutes on `windows-latest`. Windows runs only the tests
 * that exercise what differs between platforms: file paths, separators, permissions and links; deleting or moving a
 * file that is still open (Windows refuses); spawning a process and how its arguments are parsed; SQLite files and
 * their locks; line endings written to a file; temporary files; the user's home and the environment. Ubuntu's `Test`
 * job still runs everything (`sbt test`).
 *
 * Two lists say which tests those are:
 *
 *  - [[wholeModules]]: modules whose job is platform-facing (the workspace runner, the filesystem and shell tools,
 *    the SQLite stores, speech's external processes, rag's and image's file handling); every suite in them runs,
 *    so a new suite there is covered without anyone deciding it.
 *  - [[suites]]: the platform-facing suites of modules that are otherwise pure logic. A name may be a glob
 *    (`org.llm4s.assistant.*`), as `testOnly` takes it. `testPlatform` fails when a name matches no suite of its
 *    module, so a renamed or deleted suite cannot drop out of the Windows run silently.
 *
 * `scripts/check-platform-tests.sh` (Quick Checks) keeps the lists honest from the other side: every main source
 * file that touches one of those platform APIs must be listed in `scripts/platform-sensitive-sources.txt` with the
 * module or suite here that tests it on Windows. It reads these lists from the build model (`dumpBuildModel`).
 */
object PlatformTests {

  val testPlatform =
    taskKey[Unit]("Runs only the tests that exercise platform-specific behaviour, as CI's Windows job does")

  val wholeModules: Seq[String] = Seq(
    "workspaceShared",
    "workspaceRunner",
    "workspaceClient",
    "agentTools",
    "agentCheckpointSqlite",
    "memory",
    "speech",
    "image",
    "rag"
  )

  val suites: Seq[(String, Seq[String])] = Seq(
    "core" -> Seq(
      "org.llm4s.llmconnect.ProviderExchangeSinkSpec",
      "org.llm4s.llmconnect.provider.ProviderExchangeRecorderSpec",
      "org.llm4s.config.ProviderExchangeLoggingGuideCoreSpec",
      "org.llm4s.config.ProviderExchangeLoggingConfigLoaderSpec",
      "org.llm4s.config.ProviderAuthConfigSpec",
      "org.llm4s.llmconnect.auth.IdentityTokenSourceSpec",
      "org.llm4s.model.ModelRegistryServiceSpec",
      "org.llm4s.http.Llm4sHttpClientSpec"
    ),
    "agent" -> Seq("org.llm4s.assistant.*"),
    "knowledgegraph" -> Seq(
      "org.llm4s.knowledgegraph.storage.JsonGraphStoreSpec",
      "org.llm4s.knowledgegraph.storage.JsonGraphStoreTest",
      "org.llm4s.knowledgegraph.storage.GraphStoreConsistencySpec"
    ),
    "mcp" -> Seq(
      "org.llm4s.mcp.StdioTransportConcurrencySpec",
      "org.llm4s.mcp.MCPCancellationSpec",
      "org.llm4s.mcp.MCPErrorPathsSpec",
      "org.llm4s.mcp.PayloadLogSpec",
      "org.llm4s.mcp.MCPClientImplSpec"
    ),
    "anthropic" -> Seq("org.llm4s.llmconnect.provider.AnthropicWorkloadIdentitySpec"),
    "openai"    -> Seq("org.llm4s.llmconnect.provider.OpenAIWorkloadIdentitySpec"),
    "openaiCompatible" -> Seq(
      "org.llm4s.llmconnect.provider.OpenAICompatibleWorkloadIdentitySpec",
      "org.llm4s.config.OpenAICompatibleListModelsWorkloadIdentitySpec",
      "org.llm4s.llmconnect.provider.ProviderExchangeLoggingGuideSpec"
    ),
    "gemini" -> Seq("org.llm4s.llmconnect.provider.VertexAIAuthProviderSpec"),
    "configPolicy" -> Seq(
      "org.llm4s.configpolicy.DoctorSpec",
      "org.llm4s.configpolicy.DoctorClassLoaderSpec",
      "org.llm4s.configpolicy.CheckPoliciesSourceSpec"
    ),
    // `it` holds integration suites by tier. Only `@Local` ones (no Docker, model server or API key) belong here, as
    // in `sbt test`; of those, the vision clients' file-encoding paths create, write, read and delete temp files.
    // The other Local suites (OpenTelemetry, MCP, smoke contract, OkHttp classpath, tier helpers) are pure logic or
    // loopback HTTP and stay on ubuntu.
    "it" -> Seq(
      "org.llm4s.imageprocessing.provider.OpenAIVisionClientTest",
      "org.llm4s.imageprocessing.provider.AnthropicVisionClientTest"
    )
  )

  /** The tier filter `sbt test` applies to `it` (`it / Test / test / testOptions`), which `testOnly` does not apply. */
  private val itLocalTag = "org.llm4s.it.tags.Local"

  /**
   * `testPlatform`, defined on the root project. Every module's tests are started together, as `sbt test` starts
   * them, rather than one command after another. `excludeTag` is left out of the named suites as `sbt test` leaves
   * it out of every module (`testOnly` does not apply `Test / test / testOptions`).
   */
  def settings(excludeTag: String): Seq[Def.Setting[_]] = Seq(
    testPlatform / aggregate := false,
    testPlatform := Def
      .task(())
      .dependsOn(
        (unmatchedSuitesCheck +: itTierCheck +: (wholeModules
          .map(m => (LocalProject(m) / Test / test): Def.Initialize[Task[Unit]]) ++
          suites.map { case (m, names) =>
            // `it` is also restricted to its Local tier, so a suite retagged to Docker/Cloud drops out, not runs.
            val tier = if (m == "it") s" -n $itLocalTag" else ""
            (LocalProject(m) / Test / testOnly).toTask(names.mkString(" ", " ", s" -- -l $excludeTag$tier"))
          })): _*
      )
      .value
  )

  /**
   * Fails when a listed `it` suite does not carry the `@Local` annotation. `-n Local` keeps non-Local suites off
   * Windows, but a suite retagged to Docker/Cloud would then run nothing while `testPlatform` stayed green, so the
   * retag must be answered here: move the suite out of the list, or restore its tier.
   */
  private def itTierCheck: Def.Initialize[Task[Unit]] = Def.task {
    val names   = suites.filter(_._1 == "it").flatMap(_._2)
    val defined = (LocalProject("it") / Test / definedTestNames).value
    val urls    = (LocalProject("it") / Test / fullClasspath).value.map(_.data.toURI.toURL).toArray
    val loader  = new java.net.URLClassLoader(urls, null)
    val listed  = defined.filter(d => names.exists(n => GlobFilter(n).accept(d)))
    try {
      val notLocal = listed.filterNot { d =>
        loader.loadClass(d).getAnnotations.exists(_.annotationType.getName == itLocalTag)
      }
      if (notLocal.nonEmpty)
        throw new MessageOnlyException(
          s"testPlatform: these `it` suites in project/PlatformTests.scala are not @Local, so `-n Local` would " +
            s"run nothing for them on Windows: ${notLocal.mkString(", ")}. Remove them from the list or restore @Local."
        )
    } finally loader.close()
  }

  /** Fails when a name in [[suites]] matches none of its module's suites, which `testOnly` would run as nothing. */
  private def unmatchedSuitesCheck: Def.Initialize[Task[Unit]] = {
    val unmatched: Def.Initialize[Task[Seq[Seq[String]]]] = suites
      .map { case (m, names) =>
        Def.task {
          val defined = (LocalProject(m) / Test / definedTestNames).value
          names.filterNot(n => defined.exists(GlobFilter(n).accept)).map(n => s"$m: $n")
        }
      }
      .join
      .apply(_.join)
    Def.task {
      val missing = unmatched.value.flatten
      if (missing.nonEmpty)
        throw new MessageOnlyException(
          s"testPlatform: these names in project/PlatformTests.scala match no suite of their module, so Windows " +
            s"would not run them (renamed or deleted?): ${missing.mkString(", ")}"
        )
    }
  }
}
