package org.llm4s.samples.util

import org.llm4s.config.Llm4sConfig
import org.llm4s.types.Result

/**
 * Utility for validating environment configuration in examples.
 *
 * Uses the core PureConfig-based adapter so validation logic and error messages
 * stay aligned with the main library.
 */
object ConfigValidator {

  /**
   * Validates that provider configuration is present and well-formed.
   *
   * Delegates to Llm4sConfig.provider() and discards the
   * successful ProviderConfig value, returning only success/failure.
   */
  def validateEnvironment(): Result[Unit] =
    Llm4sConfig.provider().map(_ => ())
}
