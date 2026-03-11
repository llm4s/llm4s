package org.llm4s.config

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import org.llm4s.metrics.MetricsCollector
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Tests for zero-coverage Llm4sConfig facade methods: metrics, pgSearchIndex,
 * embeddingsUi, loadBraveSearchTool, loadDuckDuckGoSearchTool, loadExaSearchTool,
 * and experimentalStubsEnabled.
 */
class Llm4sConfigFacadeSpec extends AnyWordSpec with Matchers {

  private def withProps(props: Map[String, String], clearKeys: Set[String] = Set.empty)(
    f: => Unit
  ): Unit = {
    val allKeys   = props.keySet ++ clearKeys
    val originals = allKeys.map(k => k -> Option(System.getProperty(k))).toMap
    // scalafix:off DisableSyntax.NoTryCatch
    try {
      clearKeys.foreach(System.clearProperty)
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

  // --------------------------------------------------------------------------
  // Metrics
  // --------------------------------------------------------------------------

  "Llm4sConfig.metrics" should {

    "load disabled metrics by default" in {
      // No metrics config set → defaults to disabled
      val metricsKeys = Set(
        "llm4s.metrics.enabled",
        "llm4s.metrics.prometheus.enabled",
        "llm4s.metrics.prometheus.port"
      )
      withProps(Map.empty, metricsKeys) {
        val result = Llm4sConfig.metrics()
        result.isRight shouldBe true
        val (collector, endpoint) = result.getOrElse(fail("expected Right"))
        collector shouldBe MetricsCollector.noop
        endpoint shouldBe None
      }
    }
  }

  // --------------------------------------------------------------------------
  // PgSearchIndex
  // --------------------------------------------------------------------------

  "Llm4sConfig.pgSearchIndex" should {

    "load default pg config from reference.conf" in {
      val pgKeys = Set(
        "llm4s.rag.permissions.pg.host",
        "llm4s.rag.permissions.pg.port",
        "llm4s.rag.permissions.pg.database",
        "llm4s.rag.permissions.pg.user",
        "llm4s.rag.permissions.pg.password",
        "llm4s.rag.permissions.pg.vectorTableName",
        "llm4s.rag.permissions.pg.keywordTableName",
        "llm4s.rag.permissions.pg.maxPoolSize"
      )
      withProps(Map.empty, pgKeys) {
        val result = Llm4sConfig.pgSearchIndex()
        result.isRight shouldBe true
        val pg = result.getOrElse(fail("expected Right"))
        pg.host shouldBe "localhost"
        pg.port shouldBe 5432
        pg.database shouldBe "postgres"
        pg.vectorTableName shouldBe "vectors"
        pg.maxPoolSize shouldBe 10
      }
    }

    "load valid pg config with overrides" in {
      val props = Map(
        "llm4s.rag.permissions.pg.host"             -> "localhost",
        "llm4s.rag.permissions.pg.port"             -> "5432",
        "llm4s.rag.permissions.pg.database"         -> "testdb",
        "llm4s.rag.permissions.pg.user"             -> "testuser",
        "llm4s.rag.permissions.pg.password"         -> "testpass",
        "llm4s.rag.permissions.pg.vectorTableName"  -> "test_vectors",
        "llm4s.rag.permissions.pg.keywordTableName" -> "test_keywords",
        "llm4s.rag.permissions.pg.maxPoolSize"      -> "5"
      )

      withProps(props) {
        val result = Llm4sConfig.pgSearchIndex()
        result.isRight shouldBe true
        val pg = result.getOrElse(fail("expected Right"))
        pg.host shouldBe "localhost"
        pg.port shouldBe 5432
        pg.database shouldBe "testdb"
        pg.user shouldBe "testuser"
        pg.password shouldBe "testpass"
        pg.vectorTableName shouldBe "test_vectors"
        pg.keywordTableName shouldBe "test_keywords"
        pg.maxPoolSize shouldBe 5
      }
    }
  }

  // --------------------------------------------------------------------------
  // EmbeddingsUi
  // --------------------------------------------------------------------------

  "Llm4sConfig.embeddingsUi" should {

    "fall back to defaults when no config" in {
      val uiKeys = Set(
        "llm4s.embeddings.ui.maxRowsPerFile",
        "llm4s.embeddings.ui.topDimsPerRow",
        "llm4s.embeddings.ui.globalTopK",
        "llm4s.embeddings.ui.showGlobalTop",
        "llm4s.embeddings.ui.colorEnabled",
        "llm4s.embeddings.ui.tableWidth"
      )
      withProps(Map.empty, uiKeys) {
        val result = Llm4sConfig.embeddingsUi()
        result.isRight shouldBe true
        val ui = result.getOrElse(fail("expected Right"))
        ui.maxRowsPerFile shouldBe 200
        ui.topDimsPerRow shouldBe 6
        ui.globalTopK shouldBe 10
        ui.showGlobalTop shouldBe false
        ui.colorEnabled shouldBe true
        ui.tableWidth shouldBe 120
      }
    }
  }

  // --------------------------------------------------------------------------
  // Brave Search Tool
  // --------------------------------------------------------------------------

  "Llm4sConfig.loadBraveSearchTool" should {

    "load Brave search config" in {
      val props = Map(
        "llm4s.tools.brave.apiKey"     -> "brave-test-key",
        "llm4s.tools.brave.apiUrl"     -> "https://api.search.brave.com/res/v1",
        "llm4s.tools.brave.count"      -> "10",
        "llm4s.tools.brave.safeSearch" -> "moderate"
      )

      withProps(props) {
        val result = Llm4sConfig.loadBraveSearchTool()
        result.isRight shouldBe true
        val cfg = result.getOrElse(fail("expected Right"))
        cfg.apiKey shouldBe "brave-test-key"
        cfg.count shouldBe 10
        cfg.safeSearch shouldBe "moderate"
      }
    }
  }

  // --------------------------------------------------------------------------
  // DuckDuckGo Search Tool
  // --------------------------------------------------------------------------

  "Llm4sConfig.loadDuckDuckGoSearchTool" should {

    "load DuckDuckGo search config" in {
      val props = Map(
        "llm4s.tools.duckduckgo.apiUrl" -> "https://api.duckduckgo.com"
      )

      withProps(props) {
        val result = Llm4sConfig.loadDuckDuckGoSearchTool()
        result.isRight shouldBe true
        val cfg = result.getOrElse(fail("expected Right"))
        cfg.apiUrl shouldBe "https://api.duckduckgo.com"
      }
    }
  }

  // --------------------------------------------------------------------------
  // Exa Search Tool
  // --------------------------------------------------------------------------

  "Llm4sConfig.loadExaSearchTool" should {

    "load Exa search config" in {
      val props = Map(
        "llm4s.tools.exa.apiKey"        -> "exa-test-key",
        "llm4s.tools.exa.apiUrl"        -> "https://api.exa.ai",
        "llm4s.tools.exa.numResults"    -> "10",
        "llm4s.tools.exa.searchType"    -> "auto",
        "llm4s.tools.exa.maxCharacters" -> "3000"
      )

      withProps(props) {
        val result = Llm4sConfig.loadExaSearchTool()
        result.isRight shouldBe true
        val cfg = result.getOrElse(fail("expected Right"))
        cfg.apiKey shouldBe "exa-test-key"
        cfg.apiUrl shouldBe "https://api.exa.ai"
        cfg.numResults shouldBe 10
        cfg.searchType shouldBe "auto"
        cfg.maxCharacters shouldBe 3000
      }
    }
  }

  // --------------------------------------------------------------------------
  // Experimental Stubs
  // --------------------------------------------------------------------------

  "Llm4sConfig.experimentalStubsEnabled" should {

    "default to false when not configured" in {
      withProps(Map.empty, Set("llm4s.embeddings.experimentalStubs")) {
        Llm4sConfig.experimentalStubsEnabled shouldBe false
      }
    }

    "return true when configured" in {
      val props = Map(
        "llm4s.embeddings.experimentalStubs" -> "true"
      )

      withProps(props) {
        Llm4sConfig.experimentalStubsEnabled shouldBe true
      }
    }
  }
}
