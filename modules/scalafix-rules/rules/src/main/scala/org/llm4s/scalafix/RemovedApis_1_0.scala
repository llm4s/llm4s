package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * Reports each use of a 0.5.1 API removed (or made internal) since then with no mechanical replacement, with a link
 * to its migration note: the `org.llm4s.types.typeclass` package and `CompletionChunk`/`ChunkDelta` (#2345),
 * `OpenAIToolHelper` (#2360), `AnsiColors` (#2358), the embeddings sample's settings on `Llm4sConfig` (#2265), and
 * `APIError`, `SystemError` and the `LLMError.processingFailed` / `invalidImageInput` / `apiCallFailed` constructors
 * (#2819), and `ErrorMapper`, `DefaultErrorMapper`, the `AsyncResult` object and `Result.combine` / `fromBoolean` /
 * `fromBooleanWithValue` / `fromFuture` (#2821), and `TraceEvent.AgentInitialized` (#2800).
 */
class RemovedApis_1_0 extends SemanticRule("RemovedApis_1_0") {

  override def description: String =
    "Reports uses of llm4s 0.5.1 APIs removed since without a mechanical replacement"

  override def fix(implicit doc: SemanticDocument): Patch =
    doc.tree.collect {
      case n: Name if !inImport(n) =>
        Migration.symbolOf(n).flatMap(s => RemovedApis_1_0.removal(s.value)).fold(Patch.empty) { case (id, message) =>
          Patch.lint(Diagnostic(id, message, n.pos))
        }
    }.asPatch

  /** An import names a removed type without using it; the uses are reported. */
  private def inImport(tree: Tree): Boolean =
    tree.parent.exists(p => p.is[Import] || inImport(p))
}

object RemovedApis_1_0 {

  private val typeclass = """org/llm4s/types/typeclass/[^/#.]+[#.]""".r

  private val chunk = """org/llm4s/llmconnect/model/(CompletionChunk|ChunkDelta)[#.]""".r

  private val errorType = """org/llm4s/error/(APIError|SystemError)[#.]""".r

  private val errorShim = """org/llm4s/error/LLMError\.(processingFailed|invalidImageInput|apiCallFailed)\(\)\.""".r

  private val errorMapper = """org/llm4s/core/safety/(ErrorMapper|DefaultErrorMapper)[#.]""".r

  private val resultHelper =
    """org/llm4s/(AsyncResult\.|Result\.(combine|fromBoolean|fromBooleanWithValue|fromFuture)\((\+\d+)?\)\.)""".r

  private val agentInitialized = """org/llm4s/trace/TraceEvent\.AgentInitialized[#.]""".r

  private val config = "org/llm4s/config/Llm4sConfig."

  private val sampleSettings: Set[String] =
    Set(
      "embeddingsChunking().",
      "loadEmbeddingsChunking().",
      "EmbeddingsChunkingSettings#",
      "EmbeddingsChunkingSettings.",
      "embeddingsInputs().",
      "loadEmbeddingsInputs().",
      "EmbeddingsInputSettings#",
      "EmbeddingsInputSettings.",
      "embeddingsUi().",
      "loadEmbeddingsUiSettings().",
      "EmbeddingsUiSettings#",
      "EmbeddingsUiSettings.",
      "experimentalStubsEnabled().",
      "localEmbeddingModels()."
    ).map(config + _)

  /** The lint id and message for a removed symbol, if it is one. */
  def removal(symbol: String): Option[(String, String)] =
    if (typeclass.matches(symbol))
      Some(
        "typeclassRemoved" ->
          ("org.llm4s.types.typeclass (Encoder, LLMCapable) is removed; encode with upickle directly, and build a " +
            "Conversation with Conversation.create: " + Migration.guide10("unused-speculative-core-types-removed-2345"))
      )
    else if (chunk.matches(symbol))
      Some(
        "completionChunkRemoved" ->
          ("CompletionChunk and ChunkDelta are removed; streaming delivers StreamedChunk to streamComplete's onChunk: " +
            Migration.guide10("unused-speculative-core-types-removed-2345"))
      )
    else if (symbol == "org/llm4s/toolapi/OpenAIToolHelper.")
      Some(
        "openAIToolHelperInternal" ->
          ("OpenAIToolHelper is internal; take the tool definitions as JSON with ToolFunction.toolDefinitions(tools, strict = true): " +
            Migration.guide10("openaitoolhelper-is-internal-2360"))
      )
    else if (symbol == "org/llm4s/trace/AnsiColors.")
      Some(
        "ansiColorsInternal" ->
          ("AnsiColors is internal; define the escape codes you need, or use a library such as fansi: " +
            Migration.guide10("tier-hygiene-before-the-baseline-2358"))
      )
    else if (errorType.matches(symbol))
      Some(
        "errorTypeRemoved" ->
          ("APIError and SystemError are removed; a provider's error response is a ServiceError(httpStatus, " +
            "provider, details), and an error of your own extends LLMError with RecoverableError: " +
            Migration.guide10("error-hierarchy-cleanup-2819"))
      )
    else if (errorShim.matches(symbol))
      Some(
        "errorShimRemoved" ->
          ("LLMError.processingFailed, invalidImageInput and apiCallFailed are removed; call ProcessingError(...), " +
            "InvalidInputError(...) and ServiceError(httpStatus, provider, details): " +
            Migration.guide10("error-hierarchy-cleanup-2819"))
      )
    else if (errorMapper.matches(symbol))
      Some(
        "errorMapperRemoved" ->
          ("ErrorMapper and DefaultErrorMapper are removed; Safety.fromThrowable(t) is the default mapping, which no " +
            "longer reads a 401 or 429 out of a message: " + Migration.guide10(
              "result-helpers-and-conversions-move-and-errormapper-is-removed-2821"
            ))
      )
    else if (resultHelper.matches(symbol))
      Some(
        "resultHelperRemoved" ->
          ("Result.combine, fromBoolean, fromBooleanWithValue, fromFuture and the AsyncResult object are removed; use a " +
            "for comprehension, Either.cond, or future.toResult (import org.llm4s.syntax.toResult): " +
            Migration.guide10("result-helpers-and-conversions-move-and-errormapper-is-removed-2821"))
      )
    else if (agentInitialized.matches(symbol))
      Some(
        "agentInitializedRemoved" ->
          ("TraceEvent.AgentInitialized is removed: nothing in the library emitted it; trace your own with " +
            "TraceEvent.CustomEvent(name, data): " +
            Migration.guide10("traceevent-carries-an-error-as-it-is-and-agentinitialized-is-removed-2800"))
      )
    else if (sampleSettings.contains(symbol))
      Some(
        "embeddingsSampleSettingsRemoved" ->
          ("the embeddings sample's settings left Llm4sConfig; define your own, or build a LocalEmbeddingModels: " +
            Migration.guide10("the-embeddings-samples-settings-left-llm4sconfig-2265"))
      )
    else None
}
