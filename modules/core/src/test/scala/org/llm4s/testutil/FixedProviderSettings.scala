package org.llm4s.testutil

import org.llm4s.http.HttpNetworkSettings
import org.llm4s.llmconnect.auth.ApiKeyProvider
import org.llm4s.llmconnect.config.{ ProviderConfig, ProviderTimeouts }
import org.llm4s.types.Result

/**
 * For a test's own [[org.llm4s.llmconnect.config.ProviderConfig]] that takes none of the settings the loader
 * applies: no timeouts, no proxy or TLS settings and no `ApiKeyProvider`, as an in-process provider's config.
 * `ProviderConfig` declares all six members abstract (#2805), so a config says what it does with each; this
 * says "nothing" once, for the specs that are about something else.
 */
trait FixedProviderSettings extends ProviderConfig:
  def timeouts: ProviderTimeouts                                    = ProviderTimeouts.default
  def withTimeouts(timeouts: ProviderTimeouts): ProviderConfig      = this
  def network: HttpNetworkSettings                                  = HttpNetworkSettings.default
  def withNetwork(network: HttpNetworkSettings): ProviderConfig     = this
  def apiKeyProvider: Option[ApiKeyProvider]                        = None
  def withApiKeyProvider(provider: ApiKeyProvider): Result[Nothing] = ProviderConfig.noApiKeyProvider(providerId)
