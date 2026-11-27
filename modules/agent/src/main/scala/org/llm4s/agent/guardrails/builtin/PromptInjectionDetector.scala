package org.llm4s.agent.guardrails.builtin

import org.llm4s.annotation.Experimental
import org.llm4s.agent.guardrails.{ GuardrailAction, InputGuardrail }
import org.llm4s.error.ValidationError
import org.llm4s.security.RegexSafetyManager
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import scala.util.matching.Regex

/**
 * Detects prompt injection attempts in user input.
 *
 * Prompt injection attacks attempt to override system instructions,
 * manipulate the AI's behavior, or extract sensitive information.
 *
 * Detection categories:
 * - **Instruction Override**: "Ignore previous instructions", "forget your rules"
 * - **Role Manipulation**: "You are now DAN", "Act as a different AI"
 * - **System Prompt Extraction**: "What is your system prompt?", "Show your instructions"
 * - **Jailbreak Phrases**: Common patterns used in known jailbreaks
 * - **Code/SQL Injection**: Attempts to inject executable code
 *
 * Patterns are matched against a normalised copy of the input (the input itself is returned unchanged):
 * Unicode NFKD, so fullwidth and other compatibility forms become their plain letters; every combining mark
 * removed, so accented spellings lose their accents; every format character removed (zero-width space and
 * joiners, word joiner, byte-order mark, soft hyphen, bidirectional controls), so a phrase split by an
 * invisible character still matches; then lower-cased with `Locale.ROOT` (so the JVM's default locale has no
 * effect), with the Turkish dotless `ı` read as `i`. So `IGNORE`, `İGNORE`, `ıgnore`, `ＩＧＮＯＲＥ`, `ïgnöre`
 * and `ig<U+200B>nore` all match a pattern written as `ignore`. The normalisation does not map look-alike
 * letters from other scripts (such as Cyrillic `о` for Latin `o`), so it is a defence against common spelling
 * tricks, not against every evasion; a custom [[InjectionPattern]] should be written in lower-case ASCII to
 * benefit from it.
 *
 * '''Creative and role-play text.''' The default patterns are written for requests addressed to the model, and the
 * ones that ask about a prompt or its rules, or lift restrictions, match only when the text says whose they are
 * ("your instructions", "the system prompt", "your filters"), or - for the ones that ask for the text itself - where
 * it is or how to give it ("the instructions above", "the previous instructions", "verbatim"); `dan_jailbreak` needs the jailbreak's own wording
 * ("DAN mode", "do anything now", "you are now DAN"), not the name near a verb (#2923). Some defaults still match
 * ordinary sentences of a story, a game or a technical question, because the words alone are the signal:
 * `creative_writing` and `how_to_dangerous` (a story that mentions a weapon), `extract_data` ("steal the secrets"),
 * `developer_mode` ("debug mode"), `you_are_now`, `opposite_day` and `no_guardrails_mode`. An application whose users
 * write narrative text should start from the defaults and drop those it does not want, by name
 * ([[PromptInjectionDetector.defaultPatternNames]] lists them):
 * {{{
 * val forStories: Result[PromptInjectionDetector] =
 *   PromptInjectionDetector.defaults.without("creative_writing", "how_to_dangerous", "extract_data")
 * }}}
 *
 * Example usage:
 * {{{
 * // Default: Block on injection detection
 * val detector = PromptInjectionDetector()
 *
 * // Custom sensitivity (fewer false positives)
 * val relaxed = PromptInjectionDetector(
 *   sensitivity = InjectionSensitivity.Medium
 * )
 *
 * // Use as input guardrail: an injection ends the turn AgentStatus.Blocked
 * Agent.builder("assistant", client).withMiddleware(new GuardrailMiddleware(Seq(PromptInjectionDetector()), Nil)).build()
 * }}}
 *
 * @param patterns Custom injection patterns to detect (in addition to defaults)
 * @param sensitivity Detection sensitivity level
 * @param onFail Action to take when injection is detected (default: Block)
 */
@Experimental
class PromptInjectionDetector(
  val patterns: Seq[InjectionPattern] = InjectionPattern.default,
  val sensitivity: InjectionSensitivity = InjectionSensitivity.High,
  val onFail: GuardrailAction = GuardrailAction.Block
) extends InputGuardrail {

  private val logger = LoggerFactory.getLogger(getClass)

  /** The names of this detector's patterns, in order: what [[without]] takes. */
  def patternNames: Seq[String] = patterns.map(_.name)

  /**
   * This detector without the patterns named, its sensitivity and action kept (#2923): start from
   * [[PromptInjectionDetector.defaults]] and drop the ones that do not suit the application's text, rather than
   * copying the list. `Left(ValidationError)` for a name that is none of this detector's patterns - a misspelt name
   * would otherwise leave its pattern on - listing the names it has.
   */
  def without(names: String*): Result[PromptInjectionDetector] = {
    val known   = patternNames.toSet
    val unknown = names.filterNot(known).distinct
    if (unknown.isEmpty)
      Right(new PromptInjectionDetector(patterns.filterNot(p => names.contains(p.name)), sensitivity, onFail))
    else
      Left(
        ValidationError.invalid(
          "names",
          s"no injection pattern named ${unknown.map(n => s"'$n'").mkString(", ")}; " +
            s"this detector's patterns are: ${patternNames.mkString(", ")}"
        )
      )
  }

  /** This detector with `value` as its sensitivity. */
  def withSensitivity(value: InjectionSensitivity): PromptInjectionDetector =
    new PromptInjectionDetector(patterns, value, onFail)

  /** This detector with `value` as the action it takes on a match. */
  def withOnFail(value: GuardrailAction): PromptInjectionDetector =
    new PromptInjectionDetector(patterns, sensitivity, value)

  def validate(value: String): Result[String] = {
    val normalizedInput = MatchText.folded(value)

    // Find all matching patterns. Each match is step-bounded (#3233): a pattern whose match exhausts its budget, a
    // custom pattern that backtracks, counts as a match, so the input is never let through unscanned.
    val budget = PromptInjectionDetector.stepBudget(normalizedInput.length)
    val matches = patterns.flatMap { pattern =>
      if (pattern.severity < sensitivity.threshold) None
      else
        RegexSafetyManager.boundedFind(pattern.regex.pattern, normalizedInput, budget) match {
          case Right(false) => None
          case Right(true)  => Some(InjectionMatch(pattern.name, pattern.category, pattern.severity))
          case Left(_) =>
            logger.warn(s"$name: pattern ${pattern.name} exceeded its matching budget; treating the input as a match")
            Some(InjectionMatch(pattern.name, pattern.category, pattern.severity))
        }
    }

    if (matches.isEmpty) {
      Right(value)
    } else {
      onFail match {
        case GuardrailAction.Block =>
          val categories = matches.map(_.category.name).distinct.mkString(", ")
          val details    = matches.map(m => s"${m.name} (${m.category.name})").mkString("; ")
          Left(
            ValidationError.invalid(
              "input",
              s"Potential prompt injection detected. Categories: [$categories]. " +
                s"Details: $details. This request has been blocked for security."
            )
          )

        case GuardrailAction.Fix =>
          // For injection, there's no safe "fix" - we can only block or warn
          // Fall back to blocking
          val categories = matches.map(_.category.name).distinct.mkString(", ")
          Left(
            ValidationError.invalid(
              "input",
              s"Potential prompt injection detected: [$categories]. " +
                "Unable to sanitize - request blocked."
            )
          )

        case GuardrailAction.Warn =>
          // Allow processing, but say what fired: the categories and how many patterns, never the input
          val categories = matches.map(_.category.name).distinct.mkString(", ")
          logger.warn(
            s"$name matched ${matches.size} injection pattern(s) in categories [$categories] " +
              s"(sensitivity: ${sensitivity.name}) - passing through in warn mode"
          )
          Right(value)
      }
    }
  }

  val name: String = "PromptInjectionDetector"

  override val description: Option[String] = Some(
    s"Detects prompt injection attempts (sensitivity: ${sensitivity.name})"
  )
}

/**
 * Sensitivity levels for injection detection.
 *
 * Higher sensitivity catches more attacks but may have more false positives.
 */
@Experimental
sealed trait InjectionSensitivity {
  def name: String
  def threshold: Int // Minimum severity to trigger (1-3)
}

@Experimental
object InjectionSensitivity {

  /**
   * High sensitivity: Detects most injection patterns.
   * May have more false positives with legitimate requests.
   * Use in high-security environments.
   */
  case object High extends InjectionSensitivity {
    val name      = "high"
    val threshold = 1 // All severities
  }

  /**
   * Medium sensitivity: Balanced detection.
   * Good default for most applications.
   */
  case object Medium extends InjectionSensitivity {
    val name      = "medium"
    val threshold = 2 // Medium and high severity only
  }

  /**
   * Low sensitivity: Only detects obvious injection attempts.
   * Fewer false positives but may miss subtle attacks.
   */
  case object Low extends InjectionSensitivity {
    val name      = "low"
    val threshold = 3 // High severity only
  }
}

/**
 * Categories of prompt injection attacks.
 */
@Experimental
sealed trait InjectionCategory {
  def name: String
}

@Experimental
object InjectionCategory {
  case object InstructionOverride extends InjectionCategory { val name = "Instruction Override" }
  case object RoleManipulation    extends InjectionCategory { val name = "Role Manipulation"    }
  case object SystemPromptExtraction extends InjectionCategory {
    val name = "System Prompt Extraction"
  }
  case object Jailbreak        extends InjectionCategory { val name = "Jailbreak"         }
  case object CodeInjection    extends InjectionCategory { val name = "Code Injection"    }
  case object DataExfiltration extends InjectionCategory { val name = "Data Exfiltration" }
}

/**
 * Pattern for detecting a specific type of injection.
 *
 * @param name Human-readable name for the pattern
 * @param regex Regular expression to match
 * @param category Type of injection attack
 * @param severity Severity level (1=low, 2=medium, 3=high)
 */
@Experimental
final case class InjectionPattern private (
  name: String,
  regex: Regex,
  category: InjectionCategory,
  severity: Int
) {
  def withName(value: String): InjectionPattern                = copy(name = value)
  def withRegex(value: Regex): InjectionPattern                = copy(regex = value)
  def withCategory(value: InjectionCategory): InjectionPattern = copy(category = value)
  def withSeverity(value: Int): InjectionPattern               = copy(severity = value)
}

/**
 * Match result from injection detection.
 */
final private[builtin] case class InjectionMatch(
  name: String,
  category: InjectionCategory,
  severity: Int
)

@Experimental
object InjectionPattern {

  def apply(name: String, regex: Regex, category: InjectionCategory, severity: Int): InjectionPattern =
    new InjectionPattern(name, regex, category, severity)

  import InjectionCategory._

  // What makes a prompt, an instruction or a rule the model's own (#2923): "your instructions", "your system
  // prompt", "the system prompt", "the initial instructions". "the rules" and "the instructions" alone are anyone's -
  // a game's, a manual's - and do not match.
  private val Own =
    """your\s+(?:(?:own|system|initial|original|hidden|secret|internal|exact|full|complete|entire)\s+)*"""
  private val Systems =
    """the\s+(?:(?:exact|full|complete|entire)\s+)?(?:system|initial|original|hidden|secret|developer)\s+"""
  private val ModelsOwn =
    raw"""(?:$Own(?:prompts?|instructions?|rules|guidelines|directives?)|$Systems(?:prompts?|instructions?|messages?|rules|guidelines|directives?))\b"""

  // The limits an attack asks the model to drop.
  // A prompt or instructions named by where they are or how to give them, which is the conversation's own text:
  // "the instructions above", "the previous instructions", "the prompt so far", "the instructions verbatim". For the
  // patterns that ask for the text itself (reveal, repeat); "the instructions" alone still names nobody's.
  private val Positional =
    """the\s+(?:(?:above|previous|prior|preceding)\s+(?:prompts?|instructions?|rules|guidelines|messages?|text)""" +
      """|(?:prompts?|instructions?|rules|guidelines)\s+""" +
      """(?:(?:given\s+|written\s+|stated\s+)?(?:above|before\s+this|so\s+far)|verbatim|word\s+for\s+word))\b"""

  private val Limits = """(?:restrictions?|limitations?|filters?|guardrails?|rules|guidelines|safeguards?|censorship)"""

  // Instruction Override patterns (attempts to make AI ignore its instructions)
  private val instructionOverridePatterns = Seq(
    InjectionPattern(
      "ignore_instructions",
      """ignore\s++(all\s++)?(previous|prior|above|your)?\s*+(instructions?|rules?|guidelines?|prompts?)""".r,
      InstructionOverride,
      3
    ),
    InjectionPattern(
      "forget_instructions",
      """forget\s++(all\s++)?(previous|prior|your)?\s*+(instructions?|rules?|training)""".r,
      InstructionOverride,
      3
    ),
    InjectionPattern(
      "disregard",
      """disregard\s++(all\s++)?(previous|prior|your|the)?\s*+(instructions?|rules?)""".r,
      InstructionOverride,
      3
    ),
    InjectionPattern(
      "new_instructions",
      """new\s+instructions?:\s*|your\s+new\s+(task|instructions?|role)""".r,
      InstructionOverride,
      2
    ),
    InjectionPattern(
      "override_system",
      """override\s+(system|your)\s*(prompt|instructions?)""".r,
      InstructionOverride,
      3
    )
  )

  // Role Manipulation patterns (attempts to make AI assume different personas)
  private val roleManipulationPatterns = Seq(
    InjectionPattern(
      "dan_jailbreak",
      // the jailbreak's own wording, never the name near a verb: "ask Dan to act as lookout" is a sentence (#2923)
      ("""\bdan\s+(mode|jailbreak)\b|\b(enable|activate)\s+dan\b|\bdo\s+anything\s+now\b""" +
        """|\byou\s+are\s+(now|going\s+to\s+(be|act\s+as|pretend\s+to\s+be))\s+dan\b""" +
        """|\bpretend\s+(that\s+)?you\s*('re|are)\s+dan\b""").r,
      RoleManipulation,
      3
    ),
    InjectionPattern(
      "roleplay_evil",
      // an evil model, not an evil character: "pretend to be an evil cultist" is a disguise in a story (#2923)
      ("""(roleplay|pretend|act)\s+(as|like|to\s+be)\s+(an?\s+)?(""" +
        """(evil|malicious|unethical)\s+(ai|assistant|chatbot|bot|model|llm|language\s+model|version\s+of\s+yourself)""" +
        """|(uncensored|unfiltered|unrestricted))\b""").r,
      RoleManipulation,
      3
    ),
    InjectionPattern(
      "no_restrictions",
      // the assistant's own limits: "your filters", "safety guidelines", "you have no restrictions", "respond
      // without restrictions". "remove filters from the pump" names nobody's (#2923)
      (raw"""\b(without|remove|disable|bypass|drop|lift)\s+(any\s+of\s+|all\s+of\s+|any\s+|all\s+)?your\s+(\w+\s+)?$Limits\b""" +
        raw"""|\b(without|no|remove|disable|bypass)\s+(any\s+|all\s+)?(safety|content|ethical|moral)\s+$Limits\b""" +
        raw"""|\byou\s+(now\s+)?(have|are\s+under|are\s+bound\s+by)\s+no\s+$Limits\b""" +
        raw"""|\b(answer|respond|reply|operate|act|behave)\s+without\s+(any\s+)?$Limits\b""" +
        raw"""|\b(ai|assistant|chatbot|model)\s+(with|that\s+has)\s+no\s+$Limits\b""" +
        // a mode is the assistant's: "no restrictions mode"
        raw"""|\bno\s+$Limits\s+mode\b""").r,
      RoleManipulation,
      3
    ),
    InjectionPattern(
      "no_guardrails_mode",
      """\bno\s+guardrails?\b""".r,
      RoleManipulation,
      3
    ),
    InjectionPattern(
      "you_are_now",
      """you\s+are\s+now\s+(a|an)\s+\w+\s+(that|who|which)\s+(can|will|must)""".r,
      RoleManipulation,
      2
    ),
    InjectionPattern(
      "developer_mode",
      """(developer|admin|root|sudo|debug)\s+mode""".r,
      RoleManipulation,
      2
    )
  )

  // System Prompt Extraction patterns
  private val systemPromptPatterns = Seq(
    InjectionPattern(
      "reveal_prompt",
      raw"""\b(reveal|show|display|output|print)\s+(\w+\s+)?(?:$ModelsOwn|$Positional)""".r,
      SystemPromptExtraction,
      3
    ),
    InjectionPattern(
      "tell_me_prompt",
      raw"""\btell\s+me\s+$ModelsOwn""".r,
      SystemPromptExtraction,
      3
    ),
    InjectionPattern(
      "what_is_prompt",
      raw"""\bwhat\s+(is|are|was|were)\s+$ModelsOwn""".r,
      SystemPromptExtraction,
      2
    ),
    InjectionPattern(
      "repeat_instructions",
      raw"""\brepeat\s+(all\s+(of\s+)?)?(?:$ModelsOwn|$Positional)""".r,
      SystemPromptExtraction,
      3
    ),
    InjectionPattern(
      "beginning_text",
      """(repeat|output|print)\s+(everything|all\s+text)\s+(from|since)\s+(the\s+)?beginning""".r,
      SystemPromptExtraction,
      2
    )
  )

  // Common Jailbreak patterns
  private val jailbreakPatterns = Seq(
    InjectionPattern(
      "hypothetical_scenario",
      """(hypothetical|fictional|imaginary)\s+scenario\s+where\s+(you|there)\s+(are|is)\s+no\s+(rules?|restrictions?)""".r,
      Jailbreak,
      2
    ),
    InjectionPattern(
      "creative_writing",
      ("""(?m)^(?>.*?(?:write|create)\s++(?:a\s++)?(?:story|fiction|essay)\s++(?:about|where))""" +
        """.*?(?:bomb|weapon|drug|hack)""").r,
      Jailbreak,
      3
    ),
    InjectionPattern(
      "how_to_dangerous",
      ("""(?m)^(?>.*?(?:write|create|tell))(?>.*?(?:how\s++to|making|creating))""" +
        """.*?(?:bomb|weapon|explosive|malware)""").r,
      Jailbreak,
      3
    ),
    InjectionPattern(
      "opposite_day",
      """(pretend|imagine|today)\s+(it'?s|is)\s+opposite\s+day""".r,
      Jailbreak,
      2
    ),
    InjectionPattern(
      "base64_encoded",
      """base64\s*:\s*[A-Za-z0-9+/=]{20,}""".r,
      Jailbreak,
      1
    )
  )

  // Code/SQL Injection patterns
  private val codeInjectionPatterns = Seq(
    InjectionPattern(
      "sql_injection",
      """;\s*+(?:drop|delete|truncate|update|insert)\s|union\s++select|--\s*+$""".r,
      CodeInjection,
      3
    ),
    InjectionPattern(
      "shell_injection",
      """[;&|]\s*(rm\s+-rf|curl|wget|bash|sh|eval|exec)\s""".r,
      CodeInjection,
      3
    ),
    InjectionPattern(
      "script_tags",
      """\A(?>(?s:.*?)<script)[^>]*+>|</script>|javascript:|\bon\w++\s*+=""".r,
      CodeInjection,
      2
    )
  )

  // Data Exfiltration patterns
  private val dataExfiltrationPatterns = Seq(
    InjectionPattern(
      "extract_data",
      """(extract|steal|copy|export)\s+(all\s+)?(user\s+)?(data|information|secrets?|credentials?)""".r,
      DataExfiltration,
      2
    ),
    InjectionPattern(
      "send_to_url",
      """send\s+(the\s+)?(data|response|output)\s+to\s+(https?://|my\s+server)""".r,
      DataExfiltration,
      2
    )
  )

  /**
   * Default set of injection patterns (all categories).
   */
  val default: Seq[InjectionPattern] =
    instructionOverridePatterns ++
      roleManipulationPatterns ++
      systemPromptPatterns ++
      jailbreakPatterns ++
      codeInjectionPatterns ++
      dataExfiltrationPatterns

  /** The names of the [[default]] patterns, in order: what `PromptInjectionDetector.without` takes. */
  val defaultNames: Seq[String] = default.map(_.name)

  /**
   * Strict patterns (high severity only).
   */
  val strict: Seq[InjectionPattern] = default.filter(_.severity == 3)

  /**
   * Instruction override patterns only.
   */
  val instructionOverride: Seq[InjectionPattern] = instructionOverridePatterns

  /**
   * Jailbreak patterns only.
   */
  val jailbreak: Seq[InjectionPattern] = jailbreakPatterns ++ roleManipulationPatterns
}

@Experimental
object PromptInjectionDetector {

  /**
   * The character reads one pattern's match may take on an input of `length` characters (#3233): ten million, plus
   * 100 per character. The default patterns are linear and read each character a few times; a pattern that backtracks
   * exhausts the budget on a long input, and the detector then counts it as a match rather than hang admission.
   */
  private[builtin] def stepBudget(length: Int): Long = 10000000L + 100L * length

  /**
   * Create a detector with default settings (high sensitivity, block mode).
   */
  def apply(): PromptInjectionDetector = new PromptInjectionDetector()

  /**
   * The detector with every default pattern, high sensitivity and block mode, as the start of a chain: drop the
   * patterns that do not suit the application's text with [[PromptInjectionDetector.without]], then set the rest.
   * {{{
   * PromptInjectionDetector.defaults.without("creative_writing").map(_.withSensitivity(InjectionSensitivity.Medium))
   * }}}
   */
  def defaults: PromptInjectionDetector = new PromptInjectionDetector()

  /** The names of the default patterns, in order (`InjectionPattern.defaultNames`). */
  def defaultPatternNames: Seq[String] = InjectionPattern.defaultNames

  /**
   * Create a detector with custom sensitivity.
   *
   * @param sensitivity Detection sensitivity level
   */
  def apply(sensitivity: InjectionSensitivity): PromptInjectionDetector =
    new PromptInjectionDetector(sensitivity = sensitivity)

  /**
   * Create a detector with custom action.
   *
   * @param onFail Action to take when injection is detected
   */
  def apply(onFail: GuardrailAction): PromptInjectionDetector =
    new PromptInjectionDetector(onFail = onFail)

  /**
   * Create a detector with custom patterns.
   *
   * @param patterns Injection patterns to detect
   */
  def apply(patterns: Seq[InjectionPattern]): PromptInjectionDetector =
    new PromptInjectionDetector(patterns = patterns)

  /**
   * Create a fully customized detector.
   */
  def apply(
    patterns: Seq[InjectionPattern],
    sensitivity: InjectionSensitivity,
    onFail: GuardrailAction
  ): PromptInjectionDetector =
    new PromptInjectionDetector(patterns, sensitivity, onFail)

  /**
   * Preset: Strict mode with high sensitivity.
   * Blocks on any detected injection pattern.
   */
  def strict: PromptInjectionDetector =
    new PromptInjectionDetector(sensitivity = InjectionSensitivity.High)

  /**
   * Preset: Balanced mode with medium sensitivity.
   * Good default for production use.
   */
  def balanced: PromptInjectionDetector =
    new PromptInjectionDetector(sensitivity = InjectionSensitivity.Medium)

  /**
   * Preset: Monitoring mode that warns but doesn't block.
   * Use for testing or low-risk environments.
   */
  def monitoring: PromptInjectionDetector =
    new PromptInjectionDetector(onFail = GuardrailAction.Warn)

  /**
   * Preset: Instruction override detection only.
   * Focuses on attempts to override system instructions.
   */
  def instructionOverrideOnly: PromptInjectionDetector =
    new PromptInjectionDetector(patterns = InjectionPattern.instructionOverride)

  /**
   * Preset: Jailbreak detection only.
   * Focuses on known jailbreak patterns.
   */
  def jailbreakOnly: PromptInjectionDetector =
    new PromptInjectionDetector(patterns = InjectionPattern.jailbreak)
}
