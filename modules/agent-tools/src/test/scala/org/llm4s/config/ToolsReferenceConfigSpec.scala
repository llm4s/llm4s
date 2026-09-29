package org.llm4s.config

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import org.llm4s.testutil.ReferenceConfig
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * The `llm4s.tools` block moved from `llm4s-core`'s `reference.conf` to this module's with the
 * search tools (#1242). These prove it still binds the same variables to the same keys, with the
 * same defaults, and that `ToolsConfigLoader`'s no-argument loaders - which replace the removed
 * `Llm4sConfig.load*SearchTool()` methods - read the current environment.
 */
class ToolsReferenceConfigSpec extends AnyWordSpec with Matchers with EitherValues {

  "llm4s-agent-tools' reference.conf" should {

    "bind the BRAVE_SEARCH_* variables under llm4s.tools.brave" in {
      val source = ReferenceConfig.withEnv(
        "",
        Map(
          ToolsConfigKeys.BRAVE_SEARCH_API_KEY -> "brave-env-key",
          "BRAVE_SEARCH_COUNT"                 -> "7",
          "BRAVE_SEARCH_API_URL"               -> "https://brave.example/res/v1",
          "BRAVE_SAFE_SEARCH"                  -> "strict"
        )
      )

      ToolsConfigLoader.loadBraveSearchTool(source).value shouldBe
        BraveSearchToolConfig("brave-env-key", "https://brave.example/res/v1", 7, "strict")
    }

    "bind the EXA_* variables under llm4s.tools.exa" in {
      val source = ReferenceConfig.withEnv(
        "",
        Map(
          ToolsConfigKeys.EXA_API_KEY -> "exa-env-key",
          "EXA_API_URL"               -> "https://exa.example",
          "EXA_NUM_RESULTS"           -> "3",
          "EXA_SEARCH_TYPE"           -> "neural",
          "EXA_MAX_CHARACTERS"        -> "900"
        )
      )

      ToolsConfigLoader.loadExaSearchTool(source).value shouldBe
        ExaSearchToolConfig("exa-env-key", "https://exa.example", 3, "neural", 900)
    }

    "bind DUCK_DUCK_GO_SEARCH_API_URL under llm4s.tools.duckduckgo" in {
      val source = ReferenceConfig.withEnv("", Map("DUCK_DUCK_GO_SEARCH_API_URL" -> "https://ddg.example/"))

      ToolsConfigLoader.loadDuckDuckGoSearchTool(source).value shouldBe DuckDuckGoSearchToolConfig(
        "https://ddg.example/"
      )
    }

    "keep the defaults it had in core when only the API keys are set" in {
      val source = ReferenceConfig.withEnv(
        "",
        Map(ToolsConfigKeys.BRAVE_SEARCH_API_KEY -> "b", ToolsConfigKeys.EXA_API_KEY -> "e")
      )

      ToolsConfigLoader.loadBraveSearchTool(source).value shouldBe
        BraveSearchToolConfig("b", "https://api.search.brave.com/res/v1", 5, "moderate")
      ToolsConfigLoader.loadExaSearchTool(source).value shouldBe
        ExaSearchToolConfig("e", "https://api.exa.ai", 10, "auto", 500)
      ToolsConfigLoader.loadDuckDuckGoSearchTool(source).value shouldBe
        DuckDuckGoSearchToolConfig("https://api.duckduckgo.com/")
    }

    "fail to load a keyed tool whose API key is not set" in {
      val source = ReferenceConfig.withEnv("", Map.empty)

      ToolsConfigLoader.loadBraveSearchTool(source).isLeft shouldBe true
      ToolsConfigLoader.loadExaSearchTool(source).isLeft shouldBe true
    }
  }

  // The no-argument loaders replaced Llm4sConfig.loadBraveSearchTool() and friends; these cases
  // were in core's Llm4sConfigFacadeSpec.
  "ToolsConfigLoader's no-argument loaders" should {

    "load Brave search config from the current environment" in {
      withProps(
        Map(
          "llm4s.tools.brave.apiKey"     -> "brave-test-key",
          "llm4s.tools.brave.apiUrl"     -> "https://api.search.brave.com/res/v1",
          "llm4s.tools.brave.count"      -> "10",
          "llm4s.tools.brave.safeSearch" -> "moderate"
        )
      ) {
        val cfg = ToolsConfigLoader.loadBraveSearchTool().value
        cfg.apiKey shouldBe "brave-test-key"
        cfg.count shouldBe 10
        cfg.safeSearch shouldBe "moderate"
      }
    }

    "load DuckDuckGo search config from the current environment" in {
      withProps(Map("llm4s.tools.duckduckgo.apiUrl" -> "https://api.duckduckgo.com")) {
        ToolsConfigLoader.loadDuckDuckGoSearchTool().value.apiUrl shouldBe "https://api.duckduckgo.com"
      }
    }

    "load Exa search config from the current environment" in {
      withProps(
        Map(
          "llm4s.tools.exa.apiKey"        -> "exa-test-key",
          "llm4s.tools.exa.apiUrl"        -> "https://api.exa.ai",
          "llm4s.tools.exa.numResults"    -> "10",
          "llm4s.tools.exa.searchType"    -> "auto",
          "llm4s.tools.exa.maxCharacters" -> "3000"
        )
      ) {
        ToolsConfigLoader.loadExaSearchTool().value shouldBe
          ExaSearchToolConfig("exa-test-key", "https://api.exa.ai", 10, "auto", 3000)
      }
    }
  }

  private def withProps(props: Map[String, String])(f: => Unit): Unit = {
    val originals = props.keySet.map(k => k -> Option(System.getProperty(k))).toMap
    // scalafix:off DisableSyntax.NoTryCatch
    try {
      props.foreach { case (k, v) => System.setProperty(k, v) }
      ConfigFactory.invalidateCaches()
      f
    } finally {
      originals.foreach {
        case (k, Some(v)) => System.setProperty(k, v)
        case (k, None)    => System.clearProperty(k)
      }
      ConfigFactory.invalidateCaches()
    }
    // scalafix:on DisableSyntax.NoTryCatch
  }
}
