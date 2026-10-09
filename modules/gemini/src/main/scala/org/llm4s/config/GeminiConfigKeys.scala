package org.llm4s.config

import org.llm4s.annotation.Stable

/**
 * Environment-variable names recognised by `llm4s-gemini`.
 *
 * Google's GenAI SDKs read two variables for the Gemini API key and prefer `GOOGLE_API_KEY` when
 * both are set (`google-genai`'s `_get_env_api_key`: "Both GOOGLE_API_KEY and GEMINI_API_KEY are
 * set. Using GOOGLE_API_KEY."). `llm4s-gemini`'s `reference.conf` binds both to the shared
 * `llm4s.credentials.gemini.apiKey`, in that precedence.
 *
 * Vertex AI has no entry: it authenticates with OAuth2 - Application Default Credentials, or a
 * service-account file named by a section's `apiKey` - not with an API key.
 */
@Stable
object GeminiConfigKeys {

  /** Gemini API key; wins over [[GEMINI_API_KEY]] when both are set, as in Google's SDKs. */
  val GOOGLE_API_KEY = "GOOGLE_API_KEY"

  /** Gemini API key; used when [[GOOGLE_API_KEY]] is unset. */
  val GEMINI_API_KEY = "GEMINI_API_KEY"
}
