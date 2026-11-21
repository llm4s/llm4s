package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * The `Llm4sConfig` loaders that moved to their module's own loader in 0.5.0.
 *
 * `Llm4sConfig.loadBraveSearchTool()` becomes `ToolsConfigLoader.loadBraveSearchTool()` (`llm4s-agent-tools`, as do
 * the DuckDuckGo and Exa loaders), `Llm4sConfig.metrics()` becomes `MetricsConfigLoader.default()`
 * (`llm4s-observability-prometheus`) and `Llm4sConfig.pgSearchIndex()` becomes `PgSearchIndexConfigLoader.default()`
 * (`llm4s-rag`). Every one of them is in `org.llm4s.config`, as `Llm4sConfig` is.
 */
class MovedConfigLoaders extends SemanticRule("MovedConfigLoaders") {

  override def description: String =
    "Rewrites the Llm4sConfig loaders that moved to ToolsConfigLoader, MetricsConfigLoader and PgSearchIndexConfigLoader"

  override def fix(implicit doc: SemanticDocument): Patch =
    doc.tree.collect { case s @ Term.Select(_, name) =>
      Migration.symbolOf(name).flatMap(sym => MovedConfigLoaders.moves.get(sym.value)).fold(Patch.empty) {
        case (loader, method) =>
          Patch.replaceTree(s, s"$loader.$method") + Migration.importOnce("org.llm4s.config", loader)
      }
    }.asPatch
}

object MovedConfigLoaders {

  /** The 0.4.1 method, and the object and method that replace it. */
  val moves: Map[String, (String, String)] = Map(
    "org/llm4s/config/Llm4sConfig.loadBraveSearchTool()."      -> ("ToolsConfigLoader"   -> "loadBraveSearchTool"),
    "org/llm4s/config/Llm4sConfig.loadDuckDuckGoSearchTool()." -> ("ToolsConfigLoader"   -> "loadDuckDuckGoSearchTool"),
    "org/llm4s/config/Llm4sConfig.loadExaSearchTool()."        -> ("ToolsConfigLoader"   -> "loadExaSearchTool"),
    "org/llm4s/config/Llm4sConfig.metrics()."                  -> ("MetricsConfigLoader" -> "default"),
    "org/llm4s/config/Llm4sConfig.pgSearchIndex()." -> ("PgSearchIndexConfigLoader" -> "default")
  )
}
