package org.llm4s.config

/**
 * Where a named chat section's API key comes from.
 *
 * A section that sets no `apiKey` of its own uses its vendor's shared key,
 * `llm4s.credentials.<providerId>.apiKey`. That is the convenient default - set
 * `OPENAI_API_KEY` and every OpenAI section works - and also the way a section meant for a
 * second account, missing its key, silently bills the first. [[Llm4sConfig.apiKeySources]]
 * reports which sections do which, so a policy can insist on explicit keys in production.
 */
enum ApiKeySource:

  /**
   * The section sets its own `apiKey`.
   *
   * @param path where, e.g. `llm4s.providers.openai-main.apiKey`
   */
  case Section(path: String)

  /**
   * The section sets no `apiKey`, so it uses the vendor's shared key - whether or not that key
   * is set in the environment the check runs in.
   *
   * @param path the shared key's path, e.g. `llm4s.credentials.openai.apiKey`
   */
  case Credentials(path: String)

  /** The config path the key is, or would be, read from. */
  def path: String
