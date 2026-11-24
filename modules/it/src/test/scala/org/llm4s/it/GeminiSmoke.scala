package org.llm4s.it

/**
 * What every `@Cloud` Gemini suite shares, so they cannot disagree on the model or the key (#1309).
 *
 * The key is `GOOGLE_API_KEY`, else `GEMINI_API_KEY`: the order `llm4s-gemini`'s `reference.conf` binds
 * `llm4s.credentials.gemini.apiKey` in.
 */
object GeminiSmoke {

  /** The model the docs use for Gemini; it has an entry in the bundled model metadata. */
  val Model: String = "gemini-3.8-flash"

  /** How a skip or a strict-mode failure names the missing key. */
  val KeyEnvVars: String = "GOOGLE_API_KEY (or GEMINI_API_KEY)"

  def apiKey: Option[String] = env("GOOGLE_API_KEY").orElse(env("GEMINI_API_KEY"))

  private def env(name: String): Option[String] = Option(System.getenv(name)).filter(_.nonEmpty)
}
