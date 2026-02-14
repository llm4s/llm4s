package org.llm4s.samples.config

import org.llm4s.types.Result
import org.llm4s.error.ConfigurationError
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }

/**
 * Configuration for the Hallucination Detector sample.
 *
 * This demonstrates the "config at the edge" pattern:
 * - Configuration loading happens at the application boundary
 * - Business logic receives pre-loaded config objects
 * - Keeps code pure and testable without direct config dependencies
 *
 * @param text The text to analyze for hallucinations
 */
final case class HallucinationDetectorConfig(
  text: String
)

/**
 * Configuration loader for samples.
 *
 * This object demonstrates the recommended pattern for loading typed configuration
 * in your own applications. Follow this approach:
 *
 * 1. Define case classes for your config
 * 2. Create a loader object with PureConfig readers
 * 3. Load config at the application edge (in main)
 * 4. Pass config objects to business logic
 *
 * This keeps configuration concerns separate from business logic and makes code testable.
 */
object SamplesConfigLoader {

  implicit private val hallucinationDetectorReader: PureConfigReader[HallucinationDetectorConfig] =
    PureConfigReader.forProduct1("text")(HallucinationDetectorConfig.apply)

  /**
   * Load Hallucination Detector configuration from `llm4s.samples.cookbook.hallucinationDetector`.
   *
   * Example application.conf:
   * {{{
   * llm4s.samples.cookbook.hallucinationDetector {
   *   text = "Your text to analyze here..."
   * }
   * }}}
   *
   * @param source The configuration source
   * @return HallucinationDetectorConfig or error
   */
  def loadHallucinationDetector(source: ConfigSource): Result[HallucinationDetectorConfig] =
    source
      .at("llm4s.samples.cookbook.hallucinationDetector")
      .load[HallucinationDetectorConfig]
      .left
      .map { failures =>
        val msg = failures.toList.map(_.description).mkString("; ")
        ConfigurationError(s"Failed to load hallucination detector config: $msg")
      }
      .flatMap { config =>
        // Validate that text is non-empty
        if (config.text.trim.isEmpty) {
          Left(ConfigurationError("Hallucination detector text cannot be empty", List("text")))
        } else {
          Right(config)
        }
      }
}
