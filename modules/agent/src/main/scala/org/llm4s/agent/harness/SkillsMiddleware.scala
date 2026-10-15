package org.llm4s.agent.harness

import org.llm4s.agent.graph.StateKey
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, MiddlewareId, ModelContext, PromptSection }
import org.llm4s.annotation.Experimental
import org.llm4s.types.Result

/**
 * [[https://agentskills.io/ Agent Skills]] for an agent (design §4.35): finds the skills under `sources` through
 * `backend` - each folder `<source>/<name>/` holding a `SKILL.md` - and lists each one's name and description in one
 * cacheable prompt section. The model loads a skill when a task matches it, reading its `SKILL.md` and other files
 * with `read_file`, so give the agent file tools over the same backend (or the composite it is mounted in): the paths
 * the section shows are the backend's.
 *
 *   - **When.** The skills are read on a run's first model call, from that step's snapshot, and kept for the run (a
 *     bounded cache, `maxCachedRuns` runs). A skill added or edited during a run shows from the next run. A run
 *     recovered or resumed reads again: a state backend's skills come back with the checkpoint, while a store's or a
 *     host's may have changed in between, and the recovered run sees them as they are then.
 *   - **Order.** Sources in order, then folders in path order; a later source's skill replaces an earlier one of the
 *     same name, so list the user's skills before the project's.
 *   - **Bounds.** At most `maxSkills` skills are listed, each description cut at `maxDescriptionChars` code points, the
 *     section at most `maxSectionTokens` estimated tokens; at most `maxScanned` folders are examined across all
 *     sources, and of each `SKILL.md` only its frontmatter (at most [[Skill.MaxFrontmatterChars]] characters).
 *   - **Problems.** A missing source, and a folder without `SKILL.md`, are skipped. A skill that is malformed, not
 *     listed, shadowed or unreadable is skipped and named, by path, in a durable [[HarnessEvents.DocumentsReported]]
 *     committed with the run's first model step; it never fails the run. A backend error that is not a refusal fails
 *     the step, which `recover` runs again.
 *
 * `allowed-tools` is parsed onto [[Skill.allowedTools]] and not enforced. The middleware writes nothing: its `writes`
 * are the backend's keys, so a state backend's files are registered with the graph even with no file tool.
 */
@Experimental
final class SkillsMiddleware(
  val sources: Vector[VPath],
  val backend: FileBackend,
  val maxSkills: Int = SkillsMiddleware.DefaultMaxSkills,
  val maxDescriptionChars: Int = SkillsMiddleware.DefaultMaxDescriptionChars,
  val maxSectionTokens: Int = SkillsMiddleware.DefaultMaxSectionTokens,
  val maxScanned: Int = SkillsMiddleware.DefaultMaxScanned,
  val maxCachedRuns: Int = SkillsMiddleware.DefaultMaxCachedRuns,
  val id: MiddlewareId = MiddlewareId("skills")
) extends AgentMiddleware:
  import SkillsMiddleware.*

  require(maxSkills >= 1, s"SkillsMiddleware: maxSkills must be at least 1, was $maxSkills")
  require(
    maxDescriptionChars >= 1,
    s"SkillsMiddleware: maxDescriptionChars must be at least 1, was $maxDescriptionChars"
  )
  require(maxSectionTokens >= 1, s"SkillsMiddleware: maxSectionTokens must be at least 1, was $maxSectionTokens")
  require(maxScanned >= 1, s"SkillsMiddleware: maxScanned must be at least 1, was $maxScanned")

  private val cache = new RunCache[Listed](maxCachedRuns)

  override def writes: Set[StateKey[?, ?]] = backend.writes

  /** The runs whose skills are cached. */
  private[harness] def cachedRuns: Int = cache.cached

  /** The skills this run lists, read on its first call. */
  def skills(context: ModelContext): Result[Vector[Skill]] = listed(context).map(_.skills)

  override def promptSections(context: ModelContext): Result[Vector[PromptSection]] =
    listed(context).map(l => l.section.map(text => PromptSection(id.value, text, cacheable = true)).toVector)

  private def listed(context: ModelContext): Result[Listed] =
    cache(context.run)(discover(FileContext(context.run, context.state)))(l => Report.emit(context.run, l.report))

  /** Every source's skills, the listed ones rendered, and the report of what was left out. */
  private def discover(ctx: FileContext): Result[Listed] =
    val report = new Report
    candidates(ctx, report).flatMap { found =>
      // a later source's skill of a name replaces an earlier one's
      val last = found.zipWithIndex.groupMapReduce(_._1.name)(_._2)(math.max)
      val kept = found.zipWithIndex.flatMap { (skill, i) =>
        if last(skill.name) == i then Some(skill)
        else
          report.add(
            DocumentIssue(
              skill.path,
              DocumentIssue.Kind.Shadowed,
              None,
              "replaced by a later source's skill of the same name"
            )
          )
          None
      }
      val section = new SectionText(PromptSection.charsFor(maxSectionTokens))
      val listed  = Vector.newBuilder[Skill]
      // the guide first: when not even it fits, no skill is listed
      val fits  = section.addWhole(Guide)
      var count = 0
      kept.foreach { skill =>
        if count >= maxSkills then
          report.add(DocumentIssue(skill.path, DocumentIssue.Kind.NotListed, None, s"over maxSkills of $maxSkills"))
        else if fits && section.addWhole(entry(skill)) then
          listed += skill
          count += 1
        else
          report.add(
            DocumentIssue(skill.path, DocumentIssue.Kind.NotListed, None, s"over maxSectionTokens of $maxSectionTokens")
          )
      }
      val skills = listed.result()
      Right(Listed(skills, Option.when(skills.nonEmpty)(section.text), report.result(id)))
    }

  /** `- name: description (path)` on a line of its own, the description on one line and clipped. */
  private def entry(skill: Skill): String =
    val description = DocumentText.clipMarked(DocumentText.oneLine(skill.description.trim), maxDescriptionChars)
    s"\n- ${skill.name}: $description (${skill.path.value})"

  /** The parsed skills of every source, in order; the problems in `report`. */
  private def candidates(ctx: FileContext, report: Report): Result[Vector[Skill]] =
    sources
      .foldLeft[Result[(Vector[Skill], Int)]](Right((Vector.empty, 0))) { (acc, source) =>
        acc.flatMap { (found, scanned) =>
          val room = maxScanned - scanned
          if room <= 0 then
            report.add(DocumentIssue(source, DocumentIssue.Kind.NotScanned, None, s"over maxScanned of $maxScanned"))
            Right((found, scanned))
          else
            backend.ls(source, room, ctx) match
              case Left(refused: BackendRefused) if refused.kind == BackendRefused.Kind.NotFound =>
                Right((found, scanned))
              case Left(refused: BackendRefused) =>
                report.add(unreadable(source, refused))
                Right((found, scanned))
              case Left(error) => Left(error)
              case Right(listing) =>
                if listing.truncated then
                  report.add(
                    DocumentIssue(
                      source,
                      DocumentIssue.Kind.NotScanned,
                      None,
                      s"more entries than maxScanned of $maxScanned"
                    )
                  )
                val folders = listing.items.filter(_.isDirectory)
                folders
                  .foldLeft[Result[Vector[Skill]]](Right(found)) { (acc, folder) =>
                    acc.flatMap(skills => skillAt(folder.path, ctx, report).map(skills ++ _))
                  }
                  .map(skills => (skills, scanned + listing.items.size))
        }
      }
      .map(_._1)

  /** The skill in `folder`, if it has a `SKILL.md` that parses; a problem is reported, not returned. */
  private def skillAt(folder: VPath, ctx: FileContext, report: Report): Result[Option[Skill]] =
    (folder / Skill.FileName) match
      case Left(_) => Right(None) // the folder's path is too long to have a SKILL.md beneath it
      case Right(path) =>
        DocumentText.read(backend, path, Skill.MaxFrontmatterChars.toLong, ctx, stop = closesFrontmatter()) match
          case Left(refused: BackendRefused) =>
            report.add(unreadable(path, refused))
            Right(None)
          case Left(error) => Left(error)
          case Right(None) => Right(None)
          case Right(Some(read)) =>
            Skill.fromLines(path, Text.lines(read.text), closed = !read.cut) match
              case Right(skill) => Right(Some(skill))
              case Left(e: SkillFormatError) =>
                report.add(DocumentIssue(path, DocumentIssue.Kind.Malformed, Some(e.line), e.problem))
                Right(None)
              case Left(error) => Left(error)

@Experimental
object SkillsMiddleware:

  /** The default for `maxSkills`. */
  val DefaultMaxSkills: Int = 20

  /** The default for `maxDescriptionChars`. */
  val DefaultMaxDescriptionChars: Int = 256

  /** The default for `maxSectionTokens`: with the other harness sections, within the agent's default budget. */
  val DefaultMaxSectionTokens: Int = 2000

  /** The default for `maxScanned`. */
  val DefaultMaxScanned: Int = 100

  /** The default for `maxCachedRuns`. */
  val DefaultMaxCachedRuns: Int = 64

  /** The section's first line. */
  private[harness] val Guide: String =
    "Skills are available. Each is a folder with a SKILL.md file of instructions. When a task matches a skill's " +
      "description, read its SKILL.md with read_file before you start, and follow it; read the other files it names, " +
      "by paths relative to its folder, the same way. The skills:"

  /** What one run lists: its skills, the section showing them, and the report of what it left out. */
  final private[harness] case class Listed(
    skills: Vector[Skill],
    section: Option[String],
    report: Option[DocumentsReported]
  )

  /**
   * A stop predicate for [[DocumentText.read]]: true at the line closing the frontmatter, or at the first line when it
   * does not open one, so only the frontmatter is read.
   */
  private def closesFrontmatter(): String => Boolean =
    var seen   = 0
    var fences = 0
    line =>
      seen += 1
      val fence = (if seen == 1 then line.stripPrefix("\uFEFF") else line).stripTrailing == "---"
      if fence then fences += 1
      (seen == 1 && !fence) || fences >= 2

  private def unreadable(path: VPath, refused: BackendRefused): DocumentIssue =
    DocumentIssue(path, DocumentIssue.Kind.Unreadable, None, s"the backend refused it (${refused.kind})")
