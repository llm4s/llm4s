package org.llm4s.agent.harness

import org.llm4s.agent.graph.GraphTestSupport.testRunContext
import org.llm4s.agent.graph.HarnessTestSupport.stateOf
import org.llm4s.agent.graph.{ IdempotencyKey, StateKey, StateUpdate, ToolCallId }
import org.llm4s.agent.graph.tool.{ LockKey, ToolContext }
import org.llm4s.error.LLMError
import org.llm4s.types.Result

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/** Files for the harness middleware specs: a store backend seeded directly, and a backend that counts and fails. */
object HarnessFiles {

  def p(s: String): VPath = VPath(s).fold(e => throw new IllegalArgumentException(e.message), identity)

  /** A store backend over a fresh in-memory store: external to thread state, so it can change between runs. */
  def storeBackend(): StoreFileBackend =
    StoreFileBackend(InMemoryLongTermStore(), Vector("files"))
      .fold(e => throw new IllegalStateException(e.message), identity)

  /** Writes `files` into `backend`, as a tool call would. */
  def seed(backend: FileBackend, files: (String, String)*): Unit =
    files.foreach { case (path, content) =>
      val ctx = FileContext(
        ToolContext(testRunContext(), ToolCallId("seed"), IdempotencyKey(UUID.randomUUID().toString), stateOf())
      )
      backend.write(p(path), content, ctx).fold(e => throw new IllegalStateException(e.message), _ => ())
    }

  /** `underlying`, counting reads and listings, and failing every one with `failing` while it is set. */
  final class CountingBackend(underlying: FileBackend) extends FileBackend {
    val reads                               = new AtomicInteger(0)
    val lists                               = new AtomicInteger(0)
    @volatile var failing: Option[LLMError] = None

    def writes: Set[StateKey[?, ?]]      = underlying.writes
    def locks(path: VPath): Set[LockKey] = underlying.locks(path)

    def ls(path: VPath, limit: Int, ctx: FileContext): Result[Listing[FileInfo]] = {
      lists.incrementAndGet()
      failing.fold(underlying.ls(path, limit, ctx))(Left(_))
    }

    def read(path: VPath, offset: Int, limit: Int, ctx: FileContext): Result[FileLines] = {
      reads.incrementAndGet()
      failing.fold(underlying.read(path, offset, limit, ctx))(Left(_))
    }

    def write(path: VPath, content: String, ctx: FileContext): Result[(FileWritten, StateUpdate)] =
      underlying.write(path, content, ctx)
    def edit(path: VPath, old: String, replacement: String, replaceAll: Boolean, ctx: FileContext) =
      underlying.edit(path, old, replacement, replaceAll, ctx)
    def delete(path: VPath, ctx: FileContext): Result[(FileDeleted, StateUpdate)] = underlying.delete(path, ctx)
    def find(under: VPath, accept: VPath => Boolean, limit: Int, ctx: FileContext): Result[Listing[VPath]] =
      underlying.find(under, accept, limit, ctx)
    def search(under: VPath, regex: Pattern, accept: VPath => Boolean, limit: Int, ctx: FileContext) =
      underlying.search(under, regex, accept, limit, ctx)
  }
}
