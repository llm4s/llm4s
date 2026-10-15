package org.llm4s.agent.harness

import org.llm4s.agent.graph.StateKey
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, MiddlewareId, ModelContext, PromptSection }
import org.llm4s.annotation.Experimental
import org.llm4s.types.Result

/**
 * One instruction file as a run shows it: its `path`, its `text` (at most the middleware's limits), and whether the text
 * was `cut` at one.
 */
@Experimental
final case class Instructions private (path: VPath, text: String, cut: Boolean):
  def withPath(p: VPath): Instructions  = new Instructions(p, text, cut)
  def withText(t: String): Instructions = new Instructions(path, t, cut)
  def withCut(c: Boolean): Instructions = new Instructions(path, text, c)

@Experimental
object Instructions:
  def apply(path: VPath, text: String, cut: Boolean = false): Instructions = new Instructions(path, text, cut)

/**
 * Instruction memory for an agent (design §4.36): the `AGENTS.md` files at `paths`, read through `backend` in order -
 * for example the user's, then the project's - and shown to the model as one cacheable prompt section, each file under
 * a heading naming its path. A later file refines an earlier one.
 *
 *   - **When.** The files are read on a run's first model call, from that step's snapshot, and kept for the run (a
 *     bounded cache, `maxCachedRuns` runs). A run recovered or resumed reads again: a state backend's files come back
 *     with the checkpoint, while a store's or a host's may have changed in between.
 *   - **Editing.** The agent edits a file with `edit_file`, over the same backend, when the backend accepts the write
 *     and the permission rules allow `Write` on its path; the change shows from the next run. Make a scope read-only
 *     with a deny rule, or by mounting it on a backend that refuses writes.
 *   - **Bounds.** Each file is read at most `maxFileTokens` estimated tokens, and the section - guide, headings and
 *     markers included - is at most `maxTotalTokens`. A file over either is shown cut and marked; the files after the
 *     section is full are left out. Each is named, by path, in a durable [[HarnessEvents.DocumentsReported]].
 *   - **Problems.** A missing file is skipped, and an empty one shows nothing. A file the backend refuses is skipped
 *     and reported. A backend error that is not a refusal fails the step, which `recover` runs again.
 *
 * Instruction memory is a few whole files the agent always sees; semantic memory (`llm4s-agent-memory`) is facts
 * recalled per query. The middleware writes nothing: its `writes` are the backend's keys, so a state backend's files
 * are registered with the graph even with no file tool.
 */
@Experimental
final class InstructionMemoryMiddleware(
  val paths: Vector[VPath],
  val backend: FileBackend,
  val maxFileTokens: Int = InstructionMemoryMiddleware.DefaultMaxFileTokens,
  val maxTotalTokens: Int = InstructionMemoryMiddleware.DefaultMaxTotalTokens,
  val maxCachedRuns: Int = InstructionMemoryMiddleware.DefaultMaxCachedRuns,
  val id: MiddlewareId = MiddlewareId("instructions")
) extends AgentMiddleware:
  import InstructionMemoryMiddleware.*

  require(maxFileTokens >= 1, s"InstructionMemoryMiddleware: maxFileTokens must be at least 1, was $maxFileTokens")
  require(
    maxTotalTokens >= maxFileTokens,
    s"InstructionMemoryMiddleware: maxTotalTokens ($maxTotalTokens) must be at least maxFileTokens ($maxFileTokens)"
  )

  private val documents = new RunDocuments[Vector[Instructions]](id, maxCachedRuns)(load)

  override def writes: Set[StateKey[?, ?]] = backend.writes

  /** The runs whose files are cached. */
  private[harness] def cachedRuns: Int = documents.cached

  /** The files this run shows, read on its first call. */
  def instructions(context: ModelContext): Result[Vector[Instructions]] = documents(context).map(_.value)

  override def promptSections(context: ModelContext): Result[Vector[PromptSection]] = documents.sections(context)

  private val fileChars   = PromptSection.charsFor(maxFileTokens)
  private val fileMarker  = s"\n[cut: this file is over $maxFileTokens tokens]"
  private val totalMarker = s"\n[cut: the instructions are over $maxTotalTokens tokens]"

  /** Every file in order, each bounded, and the section built from them within its bound. */
  private def load(ctx: FileContext, report: Report): Result[(Vector[Instructions], Option[String])] =
    paths
      .foldLeft[Result[Vector[(VPath, BoundedText)]]](Right(Vector.empty)) { (acc, path) =>
        acc.flatMap { read =>
          DocumentText.read(backend, path, fileChars, ctx) match
            case Left(refused: BackendRefused) =>
              report.add(
                DocumentIssue(path, DocumentIssue.Kind.Unreadable, None, s"the backend refused it (${refused.kind})")
              )
              Right(read)
            case Left(error)                                 => Left(error)
            case Right(None)                                 => Right(read)
            case Right(Some(text)) if text.text.trim.isEmpty => Right(read)
            case Right(Some(text))                           => Right(read :+ (path -> text))
        }
      }
      .map(section(_, report))

  /** The section of `files`: the guide, then each file under its heading, cut to the room left once it is full. */
  private def section(files: Vector[(VPath, BoundedText)], report: Report): (Vector[Instructions], Option[String]) =
    val text  = new SectionText(PromptSection.charsFor(maxTotalTokens))
    val fits  = text.addWhole(Guide)
    var full  = !fits
    val shown = Vector.newBuilder[Instructions]
    files.foreach { (path, read) =>
      val heading = s"\n\n## ${path.value}\n"
      val body    = read.text + (if read.cut then fileMarker else "")
      if full then
        report.add(DocumentIssue(path, DocumentIssue.Kind.NotListed, None, s"over maxTotalTokens of $maxTotalTokens"))
      else
        val before = text.length
        text.addCut(heading + body, heading.length, totalMarker) match
          case None =>
            full = true
            report.add(
              DocumentIssue(path, DocumentIssue.Kind.NotListed, None, s"over maxTotalTokens of $maxTotalTokens")
            )
          case Some(totalCut) =>
            if totalCut then
              full = true
              report.add(DocumentIssue(path, DocumentIssue.Kind.Cut, None, s"over maxTotalTokens of $maxTotalTokens"))
            else if read.cut then
              report.add(DocumentIssue(path, DocumentIssue.Kind.Cut, None, s"over maxFileTokens of $maxFileTokens"))
            val added = text.text.substring(before + heading.length)
            shown += Instructions(path, added, cut = read.cut || totalCut)
    }
    val all = shown.result()
    (all, Option.when(all.nonEmpty)(text.text))

@Experimental
object InstructionMemoryMiddleware:

  /** The default for `maxFileTokens`. */
  val DefaultMaxFileTokens: Int = 2000

  /** The default for `maxTotalTokens`: with the other harness sections, within the agent's default budget. */
  val DefaultMaxTotalTokens: Int = 4000

  /** The default for `maxCachedRuns`. */
  val DefaultMaxCachedRuns: Int = 64

  /** The section's first line. */
  private[harness] val Guide: String =
    "Standing instructions from AGENTS.md files follow, in order; where they differ, a later file refines an earlier " +
      "one. They are files: when you are permitted to, edit one with edit_file to change your standing instructions, " +
      "and the change applies from the next run."
