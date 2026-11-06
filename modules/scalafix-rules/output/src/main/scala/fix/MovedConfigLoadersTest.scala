package fix

import org.llm4s.config.Llm4sConfig
import org.llm4s.config.{ MetricsConfigLoader, PgSearchIndexConfigLoader, ToolsConfigLoader }

object MovedConfigLoadersTest {
  val brave = ToolsConfigLoader.loadBraveSearchTool()

  val duckDuckGo = ToolsConfigLoader.loadDuckDuckGoSearchTool()

  val exa = ToolsConfigLoader.loadExaSearchTool()

  val metrics = MetricsConfigLoader.default()

  val searchIndex = PgSearchIndexConfigLoader.default()

  val tracing = Llm4sConfig.tracing()
}

