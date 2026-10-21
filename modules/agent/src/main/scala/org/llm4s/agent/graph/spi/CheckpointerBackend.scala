package org.llm4s.agent.graph.spi

import org.llm4s.annotation.Experimental
import org.llm4s.types.Result

/**
 * A checkpoint store's contribution to `CheckpointerConfigLoader.open` (design 4.54): how a [[CheckpointerSettings]]
 * block becomes an open [[CheckpointStore]].
 *
 * `llm4s.agent.checkpointer.kind` names the store an application runs on, and the loader hands the backend registered
 * for that kind its own block, `llm4s.agent.checkpointer.<kind>`, flattened to strings. `llm4s-agent` builds the
 * `memory` store itself; every other store module registers its backend, so choosing a store is adding its dependency
 * and setting `kind`, with no code:
 *
 * | kind       | module                           |
 * |------------|----------------------------------|
 * | `memory`   | `llm4s-agent` (built in)         |
 * | `sqlite`   | `llm4s-agent-checkpoint-sqlite`  |
 * | `postgres` | `llm4s-agent-checkpoint-postgres`|
 *
 * Implementations must be a plain `class` with a public no-arg constructor, '''not''' a Scala `object`:
 * `java.util.ServiceLoader` instantiates the named class. Declare it in
 * `META-INF/services/org.llm4s.agent.graph.spi.CheckpointerBackend`, and ship the block's defaults and its `${?VAR}`
 * bindings in the module's own `reference.conf`. A backend can also be registered explicitly with
 * [[CheckpointerBackends.of]] or [[CheckpointerBackends.withBackend]], which is the answer for a shaded jar whose
 * services files did not survive.
 *
 * {{{
 * final class MyStoreBackend extends CheckpointerBackend:
 *   val kind = "mystore"
 *   def open(settings: CheckpointerSettings): Result[CheckpointStore] =
 *     for
 *       url     <- settings.string("url")
 *       timeout <- settings.duration("callTimeout", 5.seconds)
 *       store   <- MyStore.open(url, timeout)
 *     yield CheckpointStore(kind, store, () => store.close())
 * }}}
 */
@Experimental
trait CheckpointerBackend:

  /** The `llm4s.agent.checkpointer.kind` this backend serves; matched case-insensitively. */
  def kind: String

  /**
   * Opens the store `settings` describes.
   *
   * Return a `Left` rather than throwing - a missing setting, a value the store refuses, a database that cannot be
   * reached - naming the key at fault (`CheckpointerSettings.key`). Never put a setting's value in an error or a log
   * line: the block may hold a password. A thrown exception is caught and reported, but loses what went wrong.
   */
  def open(settings: CheckpointerSettings): Result[CheckpointStore]
