package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.graph.spi.{ CheckpointStore, CheckpointerBackend, CheckpointerSettings }
import org.llm4s.annotation.Experimental
import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result

import java.nio.file.{ InvalidPathException, Path, Paths }
import java.time.Clock
import scala.util.Try

/**
 * Registers [[SqliteCheckpointer]] for `llm4s.agent.checkpointer.kind = sqlite` (design 4.54).
 *
 * Declared in this module's `META-INF/services/org.llm4s.agent.graph.spi.CheckpointerBackend`, so depending on
 * `llm4s-agent-checkpoint-sqlite` is all it takes. It reads `llm4s.agent.checkpointer.sqlite`, whose defaults and
 * bindings are in this module's `reference.conf`:
 *
 *  - `path` (`LLM4S_CHECKPOINTER_SQLITE_PATH`): the database file, created if it does not exist (its directory must);
 *    required;
 *  - `busyTimeout` (`5 seconds`): [[SqliteCheckpointerConfig.busyTimeout]].
 *
 * Claims expire by the system clock. A `class` rather than an `object` because `java.util.ServiceLoader` instantiates
 * it through a public no-arg constructor.
 */
@Experimental
final class SqliteCheckpointerBackend extends CheckpointerBackend:
  val kind: String = SqliteCheckpointerBackend.Kind

  def open(settings: CheckpointerSettings): Result[CheckpointStore] =
    for
      raw         <- settings.string("path", Some("LLM4S_CHECKPOINTER_SQLITE_PATH"))
      path        <- path(settings, raw)
      busyTimeout <- settings.duration("busyTimeout", SqliteCheckpointerConfig.default.busyTimeout)
      config <- SqliteCheckpointerConfig
        .of(busyTimeout)
        .left
        .map(e =>
          ConfigurationError(s"Invalid ${settings.key("busyTimeout")}: ${e.message}", List(settings.key("busyTimeout")))
        )
      store <- SqliteCheckpointer.open(path, Clock.systemUTC(), config)
    yield CheckpointStore(kind, store).withRunQueue(store.runQueue)

  private def path(settings: CheckpointerSettings, raw: String): Result[Path] =
    Try(Paths.get(raw)).toEither.left.map { e =>
      val reason = e match
        case invalid: InvalidPathException => invalid.getReason
        case other                         => other.getClass.getSimpleName
      ConfigurationError(s"${settings.key("path")} is not a valid path: $reason", List(settings.key("path")))
    }

object SqliteCheckpointerBackend:

  /** The `llm4s.agent.checkpointer.kind` this backend serves. */
  val Kind: String = "sqlite"
