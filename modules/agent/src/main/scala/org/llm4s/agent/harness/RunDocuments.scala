package org.llm4s.agent.harness

import org.llm4s.agent.graph.{ FencingToken, RunContext, RunId, TaskId, ThreadId }
import org.llm4s.agent.graph.middleware.{ MiddlewareId, ModelContext, PromptSection }
import org.llm4s.error.CancelledError
import org.llm4s.types.Result

import scala.util.control.Exception.ultimately

/*
 * The mechanism shared by the middleware that show documents read through a backend as a prompt section - skills
 * (F5, §4.35) and instruction memory (F6, §4.36): read once per run, bounded per document and in total, cached by
 * run in a bounded LRU, read again after recovery, and reported in a durable event.
 */

/**
 * A value computed once per run incarnation, kept for at most `maxRuns` incarnations (the least recently used
 * evicted first).
 *
 * The key is the thread, the run id, the claim's fencing token and the runtime's own object for the run
 * (`RunContext.incarnation`): a run id can be reused (`RunConfig.withRunId`, a recovery under the same id), and two
 * runtimes over different stores can issue one thread the same token, but no two runs share that object - so a
 * recovered or resumed run, or one in another runtime, computes its value again. The value is computed under its incarnation's own lock, held until the last caller
 * waiting on it leaves, so the concurrent hooks of one run compute it once - or, after a failure, one at a time, a
 * waiter retrying in turn - and never two at once. Only a computed value takes a place in the LRU: a computation in flight, or one that failed
 * (returned or thrown), evicts nothing and leaves nothing behind.
 *
 * `report` runs when the value is computed, in the task that computed it, and again whenever that task runs the hook
 * again - a retried attempt, whose durable events were discarded - so a report committed with the task's events is
 * committed at least once per run that computed the value. A later task of the run does not report: it runs only
 * after the computing task committed. An evicted run computes, and reports, again.
 */
final private[harness] class RunCache[A](maxRuns: Int):
  require(maxRuns >= 1, s"maxCachedRuns must be at least 1, was $maxRuns")

  /** A run's identity: its thread, id and claim, and the runtime's own object for it, compared by reference. */
  final private class Incarnation(
    val thread: ThreadId,
    val run: RunId,
    val token: Option[FencingToken],
    val of: AnyRef
  ):
    override def equals(other: Any): Boolean = other match
      case that: Incarnation => (that.of eq of) && that.thread == thread && that.run == run && that.token == token
      case _                 => false
    override def hashCode: Int = (thread, run, token, System.identityHashCode(of)).hashCode

  /** A computed value, and the task that computed it. */
  final private case class Entry(value: A, reporter: TaskId)

  // guards both maps; a computation runs outside it, under its incarnation's in-flight lock
  private val lock = new Object

  // the computed values, least recently used first: only a success takes a place, so a failure never evicts one
  private val done = new java.util.LinkedHashMap[Incarnation, Entry](16, 0.75f, true):
    override def removeEldestEntry(eldest: java.util.Map.Entry[Incarnation, Entry]): Boolean = this.size() > maxRuns

  /** An incarnation's in-flight lock, and how many callers hold or wait on it. */
  final private class Slot:
    var users: Int = 0

  // the incarnations being computed or waited on, one lock each, so the callers of one run compute one at a time: a
  // caller that waited finds the value, or - after a failure - retries in turn on the same lock. Removed when its
  // last caller leaves, so at most one entry per caller in flight
  private val inFlight = new java.util.HashMap[Incarnation, Slot]()

  /** The number of incarnations whose value is held. */
  def cached: Int = lock.synchronized(done.size)

  /** The number of incarnations being computed or waited on. */
  def inFlightRuns: Int = lock.synchronized(inFlight.size)

  /** The run's value: computed by `compute` on the run's first call, and reported by `report` as above. */
  def apply(run: RunContext)(compute: => Result[A])(report: A => Unit): Result[A] =
    val position = run.position
    val key      = new Incarnation(position.threadId, position.runId, position.fencingToken, run.incarnation)
    def held     = lock.synchronized(Option(done.get(key)))
    def hit(entry: Entry): Result[A] =
      if entry.reporter == position.taskId then report(entry.value)
      Right(entry.value)
    held match
      case Some(entry) => hit(entry)
      case None =>
        val slot = lock.synchronized {
          val s = inFlight.computeIfAbsent(key, _ => new Slot)
          s.users += 1
          s
        }
        // released whatever ends the wait or the computation - a value, a failure, or a throw `Try` would not catch,
        // such as an interrupt - so no slot outlives its callers
        def release(): Unit = lock.synchronized {
          slot.users -= 1
          if slot.users == 0 then inFlight.remove(key, slot): Unit
        }
        ultimately(release()) {
          slot.synchronized {
            held match
              case Some(entry) => hit(entry)
              case None        =>
                // a failure, returned or thrown, keeps nothing; a waiter retries in turn
                val computed = compute
                computed.foreach { value =>
                  lock.synchronized(done.put(key, Entry(value, position.taskId))): Unit
                  report(value)
                }
                computed
          }
        }

/** What one run's read produced: its value, the text of its section (if any), and its report. */
final private[harness] case class RunRead[A](value: A, section: Option[String], report: Option[DocumentsReported])

/**
 * A middleware's documents, read through a backend once per run incarnation ([[RunCache]], `maxRuns` runs) by `read`
 * - from the run's first model step's snapshot, recording problems in the [[Report]] it is given - and shown as one
 * cacheable prompt section whose id is the middleware's. The report is emitted as a durable
 * [[HarnessEvents.DocumentsReported]] as [[RunCache]] reports.
 */
final private[harness] class RunDocuments[A](id: MiddlewareId, maxRuns: Int)(
  read: (FileContext, Report) => Result[(A, Option[String])]
):
  private val cache = new RunCache[RunRead[A]](maxRuns)

  /** The runs held. */
  def cached: Int = cache.cached

  /** The run's read, made on its first call. */
  def apply(context: ModelContext): Result[RunRead[A]] =
    cache(context.run) {
      val report = new Report
      read(FileContext(context.run, context.state), report).map((value, section) =>
        RunRead(value, section, report.result(id))
      )
    }(done => Report.emit(context.run, done.report))

  /** The run's section, if it has one. */
  def sections(context: ModelContext): Result[Vector[PromptSection]] =
    apply(context).map(_.section.map(PromptSection(id.value, _, cacheable = true)).toVector)

/** A document's text as read: at most the bound asked for, `cut` when the file held more. */
final private[harness] case class BoundedText(text: String, cut: Boolean)

private[harness] object DocumentText:

  /** Lines asked of the backend per read. */
  private val PageLines = 256

  /**
   * At most `maxChars` characters of the file `path`, its lines joined with `\n`, cut on a code-point boundary; read
   * page by page, stopping after the first line `stop` accepts. `None` when the file does not exist. Each line is
   * bounded as it is added, so nothing over `maxChars` is kept.
   */
  def read(
    backend: FileBackend,
    path: VPath,
    maxChars: Long,
    ctx: FileContext,
    stop: String => Boolean = _ => false
  ): Result[Option[BoundedText]] =
    val out     = new java.lang.StringBuilder()
    var started = false // whether a line was added: every later line is preceded by `\n`
    @annotation.tailrec
    def loop(offset: Int): Result[Option[BoundedText]] =
      if Thread.currentThread().isInterrupted then Left(CancelledError(s"read ${path.value}"))
      else
        backend.read(path, offset, PageLines, ctx) match
          case Left(refused: BackendRefused) if offset == 0 && refused.kind == BackendRefused.Kind.NotFound =>
            Right(None)
          case Left(error) => Left(error)
          case Right(page) =>
            val lines   = page.lines.iterator
            var outcome = Option.empty[Boolean] // Some(cut) once reading is over
            while outcome.isEmpty && lines.hasNext do
              val line      = lines.next()
              val separator = if started then 1 else 0
              started = true
              val room = maxChars - out.length - separator
              // measured before anything is copied: a line over the room is cut, not appended whole
              if room < 0 then outcome = Some(true)
              else
                if separator == 1 then out.append('\n')
                if line.length.toLong > room then
                  out.append(cut(line, room))
                  outcome = Some(true)
                else
                  out.append(line)
                  if stop(line) then outcome = Some(false)
            val next = offset.toLong + page.lines.size
            outcome match
              case Some(wasCut) => Right(Some(BoundedText(out.toString, wasCut)))
              case None if page.lines.isEmpty || next >= page.totalLines =>
                Right(Some(BoundedText(out.toString, cut = false)))
              case None => loop(next.toInt)
    loop(0)

  /** At most `max` characters of `s`, on a code-point boundary. */
  def cut(s: String, max: Long): String =
    if s.length.toLong <= max then s
    else if max <= 0 then ""
    else
      val end = max.toInt
      s.substring(0, if Character.isHighSurrogate(s.charAt(end - 1)) then end - 1 else end)

  /** `s` with line breaks and other control characters replaced by spaces, so it stays on its line. */
  def oneLine(s: String): String =
    s.map(c => if Character.isISOControl(c) || c == '\u2028' || c == '\u2029' then ' ' else c)

  /** `s` cut to `max` code points, marked with an ellipsis when cut. */
  def clipMarked(s: String, max: Int): String =
    if s.codePointCount(0, s.length) <= max then s else s.substring(0, s.offsetByCodePoints(0, max)) + "\u2026"

/**
 * A prompt section's text, built within `maxChars` characters: a part is added whole or not at all, or cut with a
 * marker that is counted against the bound.
 */
final private[harness] class SectionText(maxChars: Long):
  private val out = new java.lang.StringBuilder()

  def length: Int      = out.length
  def remaining: Long  = maxChars - out.length
  def isEmpty: Boolean = out.length == 0

  /** Adds `part` if it fits; whether it did. */
  def addWhole(part: String): Boolean =
    if part.length.toLong > remaining then false
    else
      out.append(part)
      true

  /**
   * Adds `part`, or as much of it as fits followed by `marker`; `None` when not even `prefix` (the part's own
   * heading, never cut) and the marker fit, else whether it was cut. `part` must start with `prefix`.
   */
  def addCut(part: String, prefix: Int, marker: String): Option[Boolean] =
    if part.length.toLong <= remaining then
      out.append(part)
      Some(false)
    else if prefix.toLong + marker.length > remaining then None
    else
      out.append(DocumentText.cut(part, remaining - marker.length)).append(marker)
      Some(true)

  def text: String = out.toString

/** The issues of one read, at most [[Report.MaxIssues]] kept and the rest counted. */
final private[harness] class Report:
  private val kept    = Vector.newBuilder[DocumentIssue]
  private var count   = 0
  private var omitted = 0

  def add(issue: DocumentIssue): Unit =
    if count < Report.MaxIssues then
      kept += issue
      count += 1
    else omitted += 1

  def result(middleware: MiddlewareId): Option[DocumentsReported] =
    val issues = kept.result()
    Option.when(issues.nonEmpty)(DocumentsReported(middleware.value, issues, omitted))

private[harness] object Report:

  /** The most issues one event names. */
  val MaxIssues: Int = 32

  /** Emits `reported`, if any, as a durable event of `run`'s task. */
  def emit(run: RunContext, reported: Option[DocumentsReported]): Unit =
    reported.foreach(HarnessEvents.DocumentsReported.emit(run, _))
