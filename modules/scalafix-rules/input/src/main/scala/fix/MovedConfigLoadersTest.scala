/*
rule = MovedConfigLoaders
*/
package fix

import org.llm4s.config.Llm4sConfig

object MovedConfigLoadersTest {
  val brave = Llm4sConfig.loadBraveSearchTool()

  val duckDuckGo = Llm4sConfig.loadDuckDuckGoSearchTool()

  val exa = Llm4sConfig.loadExaSearchTool()

  val metrics = Llm4sConfig.metrics()

  val searchIndex = Llm4sConfig.pgSearchIndex()

  val tracing = Llm4sConfig.tracing()
}
