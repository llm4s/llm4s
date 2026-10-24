package org.llm4s.error

/**
 * Links from an error message to its entry on the First-Run Troubleshooting page, and the one-sentence next
 * action a runtime error states before its link (#2285).
 *
 * Only message text changes: no [[LLMError]] gains a field. Every link is built from [[PageUrl]], so a move of the
 * page is a change to that one line, and every anchor is an [[Entry]], so `TroubleshootingLinksSpec` can check
 * each one against the page's `{#id}` anchors: renaming a heading cannot break a link.
 */
private[llm4s] object TroubleshootingLinks:

  /** The published First-Run Troubleshooting page. */
  val PageUrl: String = "https://llm4s.org/getting-started/troubleshooting"

  /** The page's source in the repository, whose `{#id}` anchors the entries name. */
  val PageSource: String = "docs/getting-started/troubleshooting.md"

  /** An entry of the page, by its stable anchor: independent of the entry's number and heading text. */
  enum Entry(val id: String):
    case MissingApiKey         extends Entry("missing-api-key")
    case NoDefaultProvider     extends Entry("no-default-provider")
    case ProviderNotFound      extends Entry("provider-not-found")
    case ProviderNotRegistered extends Entry("provider-not-registered")
    case ApplicationConf       extends Entry("application-conf-not-loaded")
    case OllamaNotRunning      extends Entry("ollama-not-running")
    case OllamaModelMissing    extends Entry("ollama-model-missing")
    case AuthenticationFailed  extends Entry("authentication-failed")
    case RateLimited           extends Entry("rate-limited")
    case ModelNotFound         extends Entry("model-not-found")

  /** The doctor's command, as `docs/reference/doctor.md` gives it for an application's own configuration. */
  val DoctorCommand: String =
    """sbt "configPolicy/runMain org.llm4s.configpolicy.DoctorCli --config /path/to/application.conf""""

  /** The line a configuration error ends with. */
  val DoctorLine: String = s"Run the doctor to check your whole setup: $DoctorCommand"

  /** The entry's address on the published page. */
  def url(entry: Entry): String = s"$PageUrl#${entry.id}"

  /** `See <url>`: the link as a message shows it. */
  def forId(entry: Entry): String = s"See ${url(entry)}"

  /** A configuration error's message: `message`, then the entry's link and the doctor pointer, a line each. */
  def configuration(message: String, entry: Entry): String = s"$message\n${forId(entry)}\n$DoctorLine"

  /** A configuration error with no entry of its own: `message`, then the doctor pointer. */
  def withDoctor(message: String): String = s"$message\n$DoctorLine"

  /** `text` without the doctor pointer: for the doctor's own output, which would otherwise tell you to run it. */
  def withoutDoctorLine(text: String): String =
    text.linesIterator.filterNot(_ == DoctorLine).mkString("\n")

  /** `message` followed by `sentence` and the entry's link, as one paragraph. */
  def withAction(message: String, sentence: String, entry: Entry): String =
    val trimmed = message.trim
    val joiner  = if trimmed.isEmpty then "" else if trimmed.endsWith(".") then " " else ". "
    s"$trimmed$joiner$sentence ${forId(entry)}"

  /** The next action for a 401 or 403. */
  val AuthenticationAction: String =
    "Check that the key is this provider's, and that its project or organisation has access to the model."

  /** The next action for a 429. */
  val RateLimitAction: String =
    "Lower the request concurrency, or wrap the client in ReliableClient to retry and throttle."

  /** The next action for a 404. */
  val ModelNotFoundAction: String =
    "Check the section's model name and baseUrl; Llm4sConfig.listModels() lists the models the provider offers."

  /** The next action when nothing listens at a local Ollama's address. */
  val OllamaNotRunningAction: String =
    "Start the server with `ollama serve`, and check the section's baseUrl."

  /** The next action when a local Ollama does not have the model. */
  def ollamaModelMissingAction(model: String): String =
    s"Pull it with `ollama pull $model`, or set the section's model to one `ollama list` shows."

  /**
   * A provider's reply error with its next action and link: a 401 or 403 (an [[AuthenticationError]], or a
   * [[ServiceError]] with that status), an upstream [[RateLimitError]], and a 404 [[ServiceError]]. Any other
   * error, and one whose message already links the page, is returned unchanged.
   */
  def annotate(error: LLMError): LLMError =
    error match
      case e if e.message.contains(PageUrl) => e
      case e: AuthenticationError           => e.withHint(AuthenticationAction, Entry.AuthenticationFailed)
      case e: RateLimitError if e.origin == RateLimitOrigin.UpstreamProvider =>
        e.withHint(RateLimitAction, Entry.RateLimited)
      case e: ServiceError if e.httpStatus == 401 || e.httpStatus == 403 =>
        e.withHint(AuthenticationAction, Entry.AuthenticationFailed)
      case e: ServiceError if e.httpStatus == 404 => e.withHint(ModelNotFoundAction, Entry.ModelNotFound)
      case other                                  => other
