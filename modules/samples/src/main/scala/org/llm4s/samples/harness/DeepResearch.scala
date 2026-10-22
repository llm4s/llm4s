package org.llm4s.samples.harness

import org.llm4s.agent.Agent
import org.llm4s.agent.graph._
import org.llm4s.agent.graph.middleware.{ GuardrailMiddleware, PermissionRule }
import org.llm4s.agent.graph.tool._
import org.llm4s.agent.guardrails.builtin.SecretLeakGuardrail
import org.llm4s.agent.harness._
import org.llm4s.agent.harness.memory.SemanticMemoryMiddleware
import org.llm4s.agent.memory.{ MemoryStore, SimpleMemoryManager }
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.ContentPart
import org.llm4s.media.{ MediaSource, MediaType }
import org.llm4s.toolapi.{ ObjectSchema, Schema }
import org.llm4s.types.Result
import upickle.default.ReadWriter

import java.util.Base64

/**
 * The deep-research agent of Stage 3's first exit example (design §4.38): `HarnessProfile.deepAgents` on state
 * files, assembled for research.
 *
 *   - '''Files''': the agent's notes and report are thread state (`StateFileBackend`), so they are in every
 *     checkpoint and survive a restart with the thread. The application's own files - one skill and an `AGENTS.md` -
 *     are a read-only `StaticFileBackend` mounted at `/app`, which every thread reads and no tool can change.
 *   - '''Plan, context''': todos; offload of a long fetched page to `/large_tool_results`, which the model reads back
 *     by path; and summarisation of the older conversation, with the model-free digest compressor.
 *   - '''Tool policy''': reads anywhere, writes only under `/research/` and to `/report.md`, fetches only from
 *     [[Site]]; anything else is denied, and the model is told why.
 *   - '''Memory''': each finished turn is recorded in the tenant's semantic memory, outside the guardrail, so a
 *     blocked answer is never recorded.
 *   - '''Output''': a typed [[Brief]] (`withOutput`); an answer that leaks a credential is blocked.
 *   - '''Streaming''': text deltas, so `agent.stream` delivers the answer as it is written.
 *
 * The runtime - and so the checkpointer - is the caller's: `DeepResearchExample` runs it over SQLite, and
 * `DeepResearchAcceptanceSpec` drives it with a scripted model.
 */
object DeepResearch {

  /** The agent's id. */
  val AgentName: String = "researcher"

  /** The one site the agent may fetch from. */
  val Site: String = "https://docs.example.org/"

  /** The page the example researches. */
  val DurableAgentsPage: String = Site + "durable-agents"

  /** Where the application's files are mounted. */
  val AppMount: String = "/app"

  /** Where the agent writes its report. */
  val ReportPath: String = "/report.md"

  /** The query the example asks. */
  val Query: String =
    s"Research how durable agents survive a crash, starting from $DurableAgentsPage, and write a sourced report."

  /** The agent's answer, as its response format makes it. */
  final case class Brief(title: String, report: String, sources: Vector[String]) derives ReadWriter

  val briefSchema: ObjectSchema[Brief] =
    Schema
      .`object`[Brief]("A research brief: the report's title, where it is, and the pages it draws on")
      .withRequiredField("title", Schema.string("The report's title"))
      .withRequiredField("report", Schema.string("The path of the report file"))
      .withRequiredField("sources", Schema.array("The URLs of the pages the report cites", Schema.string("A URL")))

  /** The skill the application ships. */
  val ResearchSkill: String =
    """---
      |name: research
      |description: How to research a topic from web pages and write a sourced report.
      |---
      |# Research
      |
      |1. Plan the work with write_todos, and keep the plan current.
      |2. Fetch each page with fetch_page. A long page is stored under /large_tool_results: read the parts you need
      |   with read_file, by the path the result names.
      |3. Keep notes under /research/, one file a source.
      |4. Write the report to /report.md, citing every page by URL.
      |""".stripMargin

  /** The standing instructions the application ships. */
  val Instructions: String =
    """Cite every source by its URL.
      |Never quote a credential, a key or a token from a page, even an example one.
      |""".stripMargin

  /** The application's files, by path under [[AppMount]]. */
  def appFiles: Map[String, String] = Map(
    "/skills/research/SKILL.md" -> ResearchSkill,
    "/AGENTS.md"                -> Instructions
  )

  /** A 1x1 PNG: the figure the page carries, a media part beside its text. */
  private val Figure: Array[Byte] = Base64.getDecoder.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
  )

  /** The line of [[DurableAgentsPage]] that holds the fact the report needs: past what a preview shows. */
  val KeyFactLine: Int = 240

  /** The fact on [[KeyFactLine]]. */
  val KeyFact: String = "A recovered run replays committed tool results; it never calls a committed tool again."

  /** The pages `fetch_page` serves: a canned site, so the example needs no network. */
  def pages: Map[String, String] = {
    val lines = (1 to 400).map {
      case KeyFactLine => KeyFact
      case 300         => "Example only, never a real key: AKIAIOSFODNN7EXAMPLE."
      case n           => s"Section $n: a checkpoint is a full snapshot of the thread, taken after every superstep."
    }
    Map(DurableAgentsPage -> lines.mkString("\n"))
  }

  final case class FetchArgs(url: String) derives ReadWriter

  /**
   * `fetch_page`: the text of the page `pages` finds at the URL, then its figure. Its resource is `(Network, url)`, so a permission
   * rule decides which sites it may reach.
   */
  def fetchPage(pages: String => Option[String]): Result[AgentTool[FetchArgs]] =
    ContentPart.image(MediaSource.Inline(IArray.from(Figure)), MediaType.Png).map { figure =>
      AgentTool(
        AgentToolSpec[FetchArgs](
          "fetch_page",
          "Fetches a web page: its text, then its figures",
          Schema.`object`[FetchArgs]("The page to fetch").withRequiredField("url", Schema.string("The page's URL"))
        ).withResources(args => Vector(ToolResource(Operation.Network, args.url)))
      ) { (args, _) =>
        pages(args.url) match {
          case Some(text) => ToolOutcome.Success(ToolContent.Parts(Vector(ContentPart.Text(text), figure)))
          case None       => ToolOutcome.Error(s"no page at ${args.url}")
        }
      }
    }

  /** The permission rules: anything they do not allow is denied. */
  val rules: Vector[PermissionRule] = Vector(
    PermissionRule.allow(Operation.Read, "/**"),
    PermissionRule.allow(Operation.Write, "/research/**"),
    PermissionRule.allow(Operation.Write, ReportPath),
    PermissionRule.allow(Operation.Network, Site + "**"),
    PermissionRule.allow(Operation.Invoke, "write_todos")
  )

  /** The agent and the backend its files are in, so a caller can read them back from a checkpoint. */
  final case class Research(agent: Agent, files: StateFileBackend) {

    /** The agent's own files (not the application's), as the latest checkpoint of `thread` in `store` holds them. */
    def filesIn(store: Checkpointer, thread: ThreadId): Result[Map[String, String]] =
      stateIn(store, thread).map(_.get(files.files).getOrElse(Map.empty).map((p, e) => p.value -> e.content))

    /** The thread's state at its latest checkpoint in `store`, decoded by this agent's graph. */
    def stateIn(store: Checkpointer, thread: ThreadId): Result[ThreadState] =
      store
        .latest(thread)
        .flatMap(_.toRight(ValidationError("thread", s"'${thread.value}' has no checkpoint")))
        .flatMap(latest => agent.loop.graph.restore(latest.checkpoint.snapshot))
        .map(_.state)
  }

  /** The harness profile, over `files` with the application's files mounted at [[AppMount]]. */
  def profile(files: StateFileBackend): Result[HarnessProfile] =
    for {
      app <- appFiles.toVector.foldLeft[Result[Map[VPath, String]]](Right(Map.empty)) { case (acc, (p, text)) =>
        acc.flatMap(m => VPath(p).map(path => m.updated(path, text)))
      }
      bundle  <- StaticFileBackend(app)
      mount   <- VPath(AppMount)
      skills  <- VPath(AppMount + "/skills")
      agents  <- VPath(AppMount + "/AGENTS.md")
      backend <- CompositeBackend(files, mount -> bundle)
    } yield HarnessProfile.deepAgents
      .withBackend(backend)
      .withSkills(SkillsConfig(sources = Vector(skills)))
      .withInstructions(InstructionsConfig(paths = Vector(agents)))
      // small, so the example shows both: a page of a few thousand tokens is offloaded, and a turn of a few steps
      // is summarised
      .withOffload(OffloadConfig(thresholdTokens = 1000))
      .withSummarization(SummarizationConfig(trigger = SummaryTrigger.Messages(12), keepRecent = 4))
      .withPermissions(rules)
      // one agent: Stage 3's example does not delegate (design 4.48's general-purpose subagent is off)
      .withSubagents(SubagentConfig.none)

  /**
   * The research agent: `client` is the model, `runtime` the store it checkpoints to, `memories` the tenant's memory
   * store (`MemoryFiles.store`), and `pages` what `fetch_page` finds at a URL.
   */
  def agent(
    client: LLMClient,
    runtime: GraphRuntime,
    memories: Option[TenantId] => Result[MemoryStore],
    pages: String => Option[String] = DeepResearch.pages.get
  ): Result[Research] = {
    val files  = StateFileBackend()
    val memory = new SemanticMemoryMiddleware(memories, (store, _) => SimpleMemoryManager.withStore(store), client)
    for {
      profile <- profile(files)
      fetch   <- fetchPage(pages)
      tools   <- ToolSet.of(fetch)
      agent <- Agent
        .builder(AgentName, client)
        .withHarness(profile)
        .withTools(tools)
        // memory outside the guardrail, so a blocked answer is never recorded (build() checks it)
        .withMiddleware(memory, new GuardrailMiddleware(Nil, Seq(SecretLeakGuardrail())))
        .withOutput(briefSchema)
        .withStreaming()
        .withMaxSteps(20)
        .withRuntime(runtime)
        .build()
    } yield Research(agent, files)
  }
}
