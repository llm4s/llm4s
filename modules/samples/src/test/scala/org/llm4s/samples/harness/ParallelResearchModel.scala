package org.llm4s.samples.harness

import org.llm4s.error.ServiceError
import org.llm4s.llmconnect.model._
import org.llm4s.samples.harness.ParallelDeepResearch._
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }

import scala.util.Try

/**
 * The parallel deep-research example's scripted model: the lead's, each researcher's and the critic's, told apart by
 * the system prompt each is sent with. It answers from the conversation it is sent, as `ResearchModel` does - by its
 * last message - so a recovered run that sends a conversation again gets the reply the lost call did, whichever
 * process makes it.
 */
object ParallelResearchModel {

  /** A site outside the lead's allowlist: a researcher's fetch from it asks. */
  val OutsideSite: String = "https://blog.example.net/agents-in-practice"

  /** What [[OutsideSite]] says. */
  val OutsidePage: String = "In practice, teams delegate research to subagents and keep the lead's context small."

  /** A query with one question on [[Site]] and one on [[OutsideSite]]. */
  val ApprovalQuery: String = "Research how durable agents delegate, from the docs and from a practitioner's blog."

  /** The questions the lead asks for each query it knows. */
  val questions: Map[String, Vector[ResearchQuestion]] = Map(
    Query         -> Questions,
    ApprovalQuery -> Vector(Questions(2), ResearchQuestion("practice", OutsideSite))
  )

  /** Every page the script's researchers fetch. */
  def pages: Map[String, String] = ParallelDeepResearch.pages.updated(OutsideSite, OutsidePage)

  private val usage = TokenUsage(promptTokens = 100, completionTokens = 20, totalTokens = 120)

  private def call(id: String, name: String, args: (String, ujson.Value)*): ToolCall =
    ToolCall(id, name, ujson.Obj.from(args))

  private def calls(cs: ToolCall*): Reply = Reply.toolCalls(cs*).withUsage(usage)
  private def text(s: String): Reply      = Reply.text(s).withUsage(usage)

  /** The lead's id for the `task_researcher` call of `topic`. */
  def researchCall(topic: String): String = s"research-$topic"

  /** The line a draft gives a topic its researcher could not report on. */
  def gapLine(topic: String): String = s"- $topic: not researched (its researcher failed)"

  /** Each topic's findings, or `None` for a topic whose call ended in an error, from the lead's tool messages. */
  private def findingsIn(messages: Seq[Message]): Vector[(String, Option[Findings])] =
    messages.collect {
      case t: ToolMessage if t.toolCallId.startsWith("research-") =>
        t.toolCallId.stripPrefix("research-") -> Try(upickle.default.read[Findings](t.content)).toOption
    }.toVector

  /** The draft the lead composes from its researchers' findings, naming every gap. */
  def draft(findings: Vector[(String, Option[Findings])]): String =
    findings
      .map {
        case (_, Some(f))  => s"- ${f.topic}: ${f.summary} (${f.sources.mkString(", ")})"
        case (topic, None) => gapLine(topic)
      }
      .mkString("# How durable agents work\n\n", "\n", "\n")

  private def lead(messages: Seq[Message]): Option[Reply] =
    messages.lastOption.flatMap {
      case u: UserMessage =>
        questions.get(u.content).map { qs =>
          // one message, every question: the children run in parallel, at most MaxConcurrentChildren at once
          calls(qs.map(q => call(researchCall(q.topic), "task_researcher", "topic" -> q.topic, "url" -> q.url))*)
        }
      case t: ToolMessage if t.toolCallId.startsWith("research-") =>
        Some(calls(call("review", "task_critic", "text" -> draft(findingsIn(messages)))))
      case t: ToolMessage if t.toolCallId == "review" =>
        val review = Try(upickle.default.read[Review](t.content)).toOption.fold("not reviewed")(_.comments)
        val report = draft(findingsIn(messages)) + s"\nReview: $review\n"
        Some(calls(call("report", "write_file", "path" -> ReportPath, "content" -> report)))
      case t: ToolMessage if t.toolCallId == "report" =>
        val sources = findingsIn(messages).flatMap(_._2).flatMap(_.sources)
        Some(text(upickle.default.write(DeepResearch.Brief("How durable agents work", ReportPath, sources))))
      case _ => None
    }

  /** The question a researcher was given: its first user message is the rendered input, as JSON after one line. */
  def questionOf(messages: Seq[Message]): Option[ResearchQuestion] =
    messages.collectFirst { case u: UserMessage => u.content }.flatMap { content =>
      Try(upickle.default.read[ResearchQuestion](content.dropWhile(_ != '\n'))).toOption
    }

  private def researcher(messages: Seq[Message], failing: Set[String]): Option[Reply] =
    questionOf(messages).flatMap { q =>
      messages.lastOption.collect {
        case _: UserMessage if failing.contains(q.topic) =>
          Reply.failure(ServiceError(503, "scripted", s"the provider is down for '${q.topic}'"))
        case _: UserMessage => calls(call("fetch", "fetch_page", "url" -> q.url))
        case t: ToolMessage if t.toolCallId == "fetch" =>
          val fetched = pages.get(q.url).exists(page => t.content.contains(page))
          val findings =
            if (fetched) Findings(q.topic, t.content.linesIterator.next(), Vector(q.url))
            else Findings(q.topic, s"The page could not be read: ${t.content}", Vector.empty)
          text(upickle.default.write(findings))
      }
    }

  private def critic(messages: Seq[Message]): Option[Reply] =
    messages.lastOption.collect { case u: UserMessage =>
      val gaps = u.content.contains("not researched")
      val comments =
        if (gaps) "Every claim is sourced; the gaps are named, so readers know what is missing."
        else "Every claim is sourced."
      text(upickle.default.write(Review(approved = !gaps, comments)))
    }

  /** Who a conversation is sent by: the researcher, the critic or the lead. */
  enum Speaker {
    case Lead, Researcher, Critic
  }

  def speaker(messages: Seq[Message]): Speaker =
    messages.collectFirst { case s: SystemMessage => s.content } match {
      case Some(p) if p.startsWith(ResearcherPrompt) => Speaker.Researcher
      case Some(p) if p.startsWith(CriticPrompt)     => Speaker.Critic
      case _                                         => Speaker.Lead
    }

  /** The reply to `conversation`, or `None` for one the script does not expect; `failing` topics' providers fail. */
  def reply(failing: Set[String])(conversation: Conversation): Option[Reply] = {
    val messages = conversation.messages
    speaker(messages) match {
      case Speaker.Researcher => researcher(messages, failing)
      case Speaker.Critic     => critic(messages)
      case Speaker.Lead       => lead(messages)
    }
  }

  /** A fresh client over the script: one per "process", as a model is outside every process. */
  def client(failing: Set[String] = Set.empty): ScriptedLLMClient =
    ScriptedLLMClient.respondingTo(Function.unlift(reply(failing))).withChunkSize(16)
}
