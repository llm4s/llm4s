package org.llm4s.toolapi.builtin

import org.llm4s.toolapi.{ ToolFunction, ToolHintSource, ToolHints, ToolRegistry, ToolCallError, ToolCallRequest }
import org.llm4s.toolapi.builtin.http.HttpConfig
import org.llm4s.toolapi.builtin.filesystem.WriteConfig

import java.util.Locale

/**
 * The [[ToolHints]] of the built-in tools (#3273). Each factory registers the instance it builds with the hints its
 * configuration gives it - `http_request` is read-only only when it allows no method but `GET`, `HEAD` and
 * `OPTIONS`, `write_file` destructive only when it may overwrite - and [[BuiltinTools.registry]] reports them for
 * exactly those instances, so a tool of your own that shares a name gets none.
 *
 * Instances are held weakly: a tool nothing else refers to is forgotten.
 */
private[builtin] object BuiltinToolHints {

  private val hintsOf =
    java.util.Collections.synchronizedMap(new java.util.WeakHashMap[ToolFunction[_], ToolHints]())

  /** `tool`, with `hints` recorded for it. */
  def register[R](tool: ToolFunction[R], hints: ToolHints): ToolFunction[R] = {
    hintsOf.put(tool, hints)
    tool
  }

  /** The hints recorded for `tool`, when a built-in factory built it. */
  def of(tool: ToolFunction[_]): Option[ToolHints] = Option(hintsOf.get(tool))

  /** Reads or computes, touching nothing outside the process: the core utilities. */
  val Pure: ToolHints = ToolHints(readOnly = true, destructive = false, idempotent = true, openWorld = false)

  /** Reads the local file system: `read_file`, `list_directory`, `file_info`. */
  val ReadOnlyLocal: ToolHints = ToolHints(readOnly = true, destructive = false, idempotent = true, openWorld = false)

  /** Queries a search service on the network. */
  val ReadOnlyNetwork: ToolHints = ToolHints(readOnly = true, destructive = false, idempotent = true, openWorld = true)

  /** Runs a program: anything may happen, so the defaults. */
  val Shell: ToolHints = ToolHints.default

  /** `write_file`: writes local files, destructive when it may overwrite one. */
  def write(config: WriteConfig): ToolHints =
    ToolHints(readOnly = false, destructive = config.allowOverwrite, idempotent = false, openWorld = false)

  /** `http_request`: read-only when every method it allows is safe (RFC 9110). */
  def http(config: HttpConfig): ToolHints = {
    val methods = config.allowedMethods.map(_.trim.toUpperCase(Locale.ROOT)).toSet
    if (methods.subsetOf(Set("GET", "HEAD", "OPTIONS")))
      ToolHints(readOnly = true, destructive = false, idempotent = true, openWorld = true)
    else
      ToolHints(readOnly = false, destructive = methods.contains("DELETE") || methods.contains("PUT"), openWorld = true)
  }

  /** A registry of `tools` that reports each built-in tool's hints ([[ToolHintSource]]). */
  final class HintedRegistry(underlying: ToolRegistry) extends ToolRegistry with ToolHintSource {
    def tools: Seq[ToolFunction[_]]                                           = underlying.tools
    def execute(request: ToolCallRequest): Either[ToolCallError, ujson.Value] = underlying.execute(request)
    def toolHints(name: String): Option[ToolHints]                            = underlying.tool(name).flatMap(of)
  }
}
