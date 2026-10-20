package org.llm4s.samples.harness

import org.llm4s.agent.Agent
import org.llm4s.agent.delegation._
import org.llm4s.agent.graph._
import org.llm4s.agent.graph.middleware.PermissionRule
import org.llm4s.agent.graph.tool._
import org.llm4s.agent.harness._
import org.llm4s.llmconnect.LLMClient
import org.llm4s.toolapi.{ ObjectSchema, Schema }
import org.llm4s.types.Result
import upickle.default.ReadWriter

/**
 * The deep-research agent of Stage 4's exit example (design §4.49): Stage 3's research ported to parallel specialists.
 *
 * A lead agent on `HarnessProfile.deepAgents` hands each research question to a `researcher` subagent, several in one
 * model message, so they run in parallel; it gives the draft it composes from their findings to a `critic` subagent,
 * writes the report and answers with Stage 3's typed [[DeepResearch.Brief]]. Only typed data crosses: the lead sends a
 * [[ResearchQuestion]] or a [[Draft]], and gets back [[Findings]] or a [[Review]]; the children's conversations stay
 * on their own threads.
 *
 *   - '''Subagents''': the preset's general-purpose subagent is off; the two specialists are typed, each with its own
 *     `task_<id>` tool. The `researcher` is granted the lead's `fetch_page`; the `critic` has no tools.
 *   - '''Bounds''': at most [[MaxConcurrentChildren]] children run at once, whatever the batch; the rest of the
 *     profile's `DelegationConfig` is its default.
 *   - '''Tool policy''': the lead's permission rules bind its children too (they only narrow). Fetches from [[Site]]
 *     run; a fetch from any other site asks, and a child's question suspends the lead, which is resumed through
 *     `Agent.resume`.
 *   - '''Streaming''': the lead and both specialists stream their text, so a nested subscription shows each child's
 *     answer as it is written, between its `TaskStarted` and `TaskEnded`.
 *
 * The runtime - and so the checkpointer - is the caller's: `ParallelDeepResearchExample` runs it over SQLite, and
 * `ParallelDeepResearchAcceptanceSpec` drives it with a scripted model.
 */
object ParallelDeepResearch {

  /** The lead agent's id. */
  val AgentName: String = "lead"

  /** The site children may fetch from without asking. */
  val Site: String = DeepResearch.Site

  /** Where the lead writes its report. */
  val ReportPath: String = DeepResearch.ReportPath

  /** How many children may run at once. */
  val MaxConcurrentChildren: Int = 2

  /** The three questions the example researches, each on its own page of [[Site]]. */
  val Questions: Vector[ResearchQuestion] = Vector(
    ResearchQuestion("checkpoints", Site + "checkpoints"),
    ResearchQuestion("recovery", Site + "recovery"),
    ResearchQuestion("delegation", Site + "delegation")
  )

  /** The query the example asks: it names each topic's page, so a live model knows what it may research. */
  val Query: String =
    "Research how durable agents checkpoint, recover and delegate, one researcher a topic, and write a sourced report. " +
      Questions.map(q => s"${q.topic}: ${q.url}").mkString("Start each topic from its page - ", "; ", ".")

  /** What the lead asks a researcher: one topic, and the page to start from. */
  final case class ResearchQuestion(topic: String, url: String) derives ReadWriter

  /** What a researcher reports: a summary of the topic, and the pages it draws on. */
  final case class Findings(topic: String, summary: String, sources: Vector[String]) derives ReadWriter

  /** What the lead asks the critic to review. */
  final case class Draft(text: String) derives ReadWriter

  /** What the critic reports. */
  final case class Review(approved: Boolean, comments: String) derives ReadWriter

  val questionSchema: ObjectSchema[ResearchQuestion] =
    Schema
      .`object`[ResearchQuestion]("One research question")
      .withRequiredField("topic", Schema.string("The topic to research"))
      .withRequiredField("url", Schema.string("The URL of the page to start from"))

  val findingsSchema: ObjectSchema[Findings] =
    Schema
      .`object`[Findings]("A researcher's findings")
      .withRequiredField("topic", Schema.string("The topic researched"))
      .withRequiredField("summary", Schema.string("What the pages say about it"))
      .withRequiredField("sources", Schema.array("The URLs of the pages the summary draws on", Schema.string("A URL")))

  val draftSchema: ObjectSchema[Draft] =
    Schema.`object`[Draft]("A draft report").withRequiredField("text", Schema.string("The draft's text"))

  val reviewSchema: ObjectSchema[Review] =
    Schema
      .`object`[Review]("A review of a draft")
      .withRequiredField("approved", Schema.boolean("Whether the draft may be published as it is"))
      .withRequiredField("comments", Schema.string("What to change, or why it is fine"))

  /** The researcher's instructions. */
  val ResearcherPrompt: String =
    "You are a researcher. Fetch the page you are given with fetch_page, and report only what it says, citing it by URL."

  /** The critic's instructions. */
  val CriticPrompt: String =
    "You are a critic. Review the draft you are given: is every claim sourced, and is every gap named?"

  /** The lead's standing instructions, after the harness's own prompt. */
  val LeadInstructions: String =
    """Give each research question to a researcher, all of them in one message so they run in parallel.
      |Compose a draft from their findings, naming any topic that could not be researched, and have the critic review it.
      |Write the report to /report.md, then answer with the brief.
      |""".stripMargin

  /** `researcher`: one question in, typed findings out, with the lead's `fetch_page` granted. */
  val researcher: TypedAgent[ResearchQuestion, Findings] =
    TypedAgent("researcher", "Researches one question from one web page", questionSchema, findingsSchema)
      .withSystemPrompt(ResearcherPrompt)
      .withInheritance(Inheritance().withTools(ToolGrant.Only(Set("fetch_page"))))
      .withMaxSteps(6)
      .withStreaming()

  /** `critic`: a draft in, a typed review out, with no tools. */
  val critic: TypedAgent[Draft, Review] =
    TypedAgent("critic", "Reviews a draft report for unsourced claims and unnamed gaps", draftSchema, reviewSchema)
      .withSystemPrompt(CriticPrompt)
      .withMaxSteps(2)
      .withStreaming()

  /** The pages `fetch_page` serves: a canned site, so the example needs no network. */
  def pages: Map[String, String] = Map(
    Site + "checkpoints" -> "A checkpoint is a full snapshot of the thread, taken after every superstep.",
    Site + "recovery"    -> "A recovered run replays committed tool results; it never calls a committed tool again.",
    Site + "delegation"  -> "A subagent runs on a child thread of its own, and only its typed report comes back."
  )

  /**
   * The permission rules of the lead, which bind its children too: anything they do not allow or ask about is denied.
   */
  val rules: Vector[PermissionRule] = Vector(
    PermissionRule.allow(Operation.Read, "/**"),
    PermissionRule.allow(Operation.Write, ReportPath),
    PermissionRule.allow(Operation.Invoke, "write_todos"),
    PermissionRule.allow(Operation.Invoke, "task:researcher"),
    PermissionRule.allow(Operation.Invoke, "task:critic"),
    PermissionRule.allow(Operation.Network, Site + "**"),
    // any other site: ask the person running the lead, wherever in the tree the fetch is made
    PermissionRule.ask(Operation.Network, "**")
  )

  /** The lead's harness: the deep-agents preset, with its general-purpose subagent off and the two specialists on. */
  val profile: HarnessProfile =
    HarnessProfile.deepAgents
      .withSystemPrompt(PromptMode.Suffix(LeadInstructions))
      .withPermissions(rules)
      .withSubagents(
        SubagentConfig.deepAgents
          .withGeneralPurpose(None)
          .withSpecialists(researcher, critic)
          .withDelegation(DelegationConfig(maxConcurrentChildren = MaxConcurrentChildren))
      )

  /**
   * The lead agent: `client` is the model of the lead and of its children, `runtime` the store every thread of the tree
   * checkpoints to, and `pages` what `fetch_page` finds at a URL.
   */
  def agent(
    client: LLMClient,
    runtime: GraphRuntime,
    pages: String => Option[String] = ParallelDeepResearch.pages.get
  ): Result[Agent] =
    for {
      fetch <- DeepResearch.fetchPage(pages)
      tools <- ToolSet.of(fetch)
      agent <- Agent
        .builder(AgentName, client)
        .withHarness(profile)
        .withTools(tools)
        .withOutput(DeepResearch.briefSchema)
        .withStreaming()
        .withMaxSteps(20)
        .withRuntime(runtime)
        .build()
    } yield agent
}
