package org.llm4s.config

import org.scalatest.funsuite.AnyFunSuite
import pureconfig.ConfigSource
import com.typesafe.config.ConfigFactory

class ToolsConfigLoaderSpec extends AnyFunSuite {

  test("loadExaSearchTool loads valid configuration with different values") {
    val testCases = List(
      ("test-api-key-12345", "https://api.exa.ai", 10, "auto", 500),
      ("custom-key", "https://custom.exa.ai", 5, "neural", 1000)
    )

    testCases.foreach { case (apiKey, apiUrl, numResults, searchType, maxCharacters) =>
      val config = ConfigFactory.parseString(s"""
        llm4s.tools.exa {
          apiKey = "$apiKey"
          apiUrl = "$apiUrl"
          numResults = $numResults
          searchType = "$searchType"
          maxCharacters = $maxCharacters
        }
      """)

      val result = ToolsConfigLoader.loadExaSearchTool(ConfigSource.fromConfig(config))

      assert(result.isRight)
      result.foreach { exaConfig =>
        assert(exaConfig.apiKey == apiKey)
        assert(exaConfig.apiUrl == apiUrl)
        assert(exaConfig.numResults == numResults)
        assert(exaConfig.searchType == searchType)
        assert(exaConfig.maxCharacters == maxCharacters)
      }
    }
  }

  test("loadExaSearchTool accepts all valid searchType values") {
    val validTypes = List("auto", "neural", "fast", "deep")

    validTypes.foreach { searchType =>
      val config = ConfigFactory.parseString(s"""
        llm4s.tools.exa {
          apiKey = "test-key"
          apiUrl = "https://api.exa.ai"
          numResults = 10
          searchType = "$searchType"
          maxCharacters = 500
        }
      """)

      val result = ToolsConfigLoader.loadExaSearchTool(ConfigSource.fromConfig(config))

      assert(result.isRight, s"searchType '$searchType' should be valid")
      result.foreach(cfg => assert(cfg.searchType == searchType.toLowerCase))
    }
  }

  test("loadExaSearchTool rejects invalid searchType values") {
    val invalidTypes = List("invalid", "autoo", "neurall", "keyword", "semantic", "search", "")

    invalidTypes.foreach { searchType =>
      val config = ConfigFactory.parseString(s"""
        llm4s.tools.exa {
          apiKey = "test-key"
          apiUrl = "https://api.exa.ai"
          numResults = 10
          searchType = "$searchType"
          maxCharacters = 500
        }
      """)

      val result = ToolsConfigLoader.loadExaSearchTool(ConfigSource.fromConfig(config))

      assert(result.isLeft, s"searchType '$searchType' should be invalid")
      result.left.foreach(error =>
        assert(error.message.contains("searchType"), s"Error message should mention searchType: ${error.message}")
      )
    }
  }

  test("loadExaSearchTool accepts non-lowercase searchType values by normalizing them") {
    val mixedCaseTypes = List(
      ("AUTO", "auto"),
      ("NEURAL", "neural"),
      ("Neural", "neural"),
      ("Auto", "auto"),
      ("AuTo", "auto"),
      ("nEuRaL", "neural")
    )

    mixedCaseTypes.foreach { case (input, expected) =>
      val config = ConfigFactory.parseString(s"""
        llm4s.tools.exa {
          apiKey = "test-key"
          apiUrl = "https://api.exa.ai"
          numResults = 10
          searchType = "$input"
          maxCharacters = 500
        }
      """)

      val result = ToolsConfigLoader.loadExaSearchTool(ConfigSource.fromConfig(config))

      assert(result.isRight, s"searchType '$input' should be accepted and normalized")
      result.foreach(cfg => assert(cfg.searchType == expected, s"'$input' should be normalized to '$expected'"))
    }
  }

  test("loadExaSearchTool handles whitespace in searchType") {
    val testCases = List(
      ("  neural  ", "neural"),
      ("  auto", "auto"),
      ("fast  ", "fast"),
      (" deep ", "deep")
    )

    testCases.foreach { case (input, expected) =>
      val config = ConfigFactory.parseString(s"""
        llm4s.tools.exa {
          apiKey = "test-key"
          apiUrl = "https://api.exa.ai"
          numResults = 10
          searchType = "$input"
          maxCharacters = 500
        }
      """)

      val result = ToolsConfigLoader.loadExaSearchTool(ConfigSource.fromConfig(config))

      assert(result.isRight, s"searchType with whitespace should be valid")
      result.foreach(cfg => assert(cfg.searchType == expected, s"'$input' should be normalized to '$expected'"))
    }
  }

  test("loadExaSearchTool fails when required fields are missing") {
    val missingFieldConfigs = List(
      (
        """llm4s.tools.exa {
            apiUrl = "https://api.exa.ai"
            numResults = 10
            searchType = "auto"
            maxCharacters = 500
          }""",
        "apiKey"
      ),
      (
        """llm4s.tools.exa {
            apiKey = "test-key"
            numResults = 10
            searchType = "auto"
            maxCharacters = 500
          }""",
        "apiUrl"
      ),
      (
        """llm4s.tools.exa {
            apiKey = "test-key"
            apiUrl = "https://api.exa.ai"
            numResults = 10
            maxCharacters = 500
          }""",
        "searchType"
      )
    )

    missingFieldConfigs.foreach { case (configStr, fieldName) =>
      val config = ConfigFactory.parseString(configStr)
      val result = ToolsConfigLoader.loadExaSearchTool(ConfigSource.fromConfig(config))

      assert(result.isLeft, s"Should fail when $fieldName is missing")
    }
  }

  test("loadExaSearchTool fails when entire config section is missing or path is wrong") {
    val emptyConfig = ConfigFactory.empty()
    val resultEmpty = ToolsConfigLoader.loadExaSearchTool(ConfigSource.fromConfig(emptyConfig))
    assert(resultEmpty.isLeft)

    val wrongPathConfig = ConfigFactory.parseString("""
      llm4s.tools.wrong {
        apiKey = "test-key"
        apiUrl = "https://api.exa.ai"
        numResults = 10
        searchType = "auto"
        maxCharacters = 500
      }
    """)
    val resultWrong = ToolsConfigLoader.loadExaSearchTool(ConfigSource.fromConfig(wrongPathConfig))
    assert(resultWrong.isLeft)
  }

  test("loadExaSearchTool fails when field types are invalid") {
    val invalidTypeConfigs = List(
      (
        """llm4s.tools.exa {
            apiKey = "test-key"
            apiUrl = "https://api.exa.ai"
            numResults = "not-a-number"
            searchType = "auto"
            maxCharacters = 500
          }""",
        "numResults"
      ),
      (
        """llm4s.tools.exa {
            apiKey = "test-key"
            apiUrl = "https://api.exa.ai"
            numResults = 10
            searchType = "auto"
            maxCharacters = "five-hundred"
          }""",
        "maxCharacters"
      ),
      (
        """llm4s.tools.exa {
            apiKey = "test-key"
            apiUrl = "https://api.exa.ai"
            numResults = 10
            searchType = 123
            maxCharacters = 500
          }""",
        "searchType"
      )
    )

    invalidTypeConfigs.foreach { case (configStr, fieldName) =>
      val config = ConfigFactory.parseString(configStr)
      val result = ToolsConfigLoader.loadExaSearchTool(ConfigSource.fromConfig(config))

      assert(result.isLeft, s"Should fail when $fieldName has invalid type")
    }
  }
}
