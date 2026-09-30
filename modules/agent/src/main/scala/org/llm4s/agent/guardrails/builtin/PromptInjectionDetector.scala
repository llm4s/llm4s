package org.llm4s.agent.guardrails.builtin

import org.llm4s.agent.guardrails.{ GuardrailAction, InputGuardrail }
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

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
 * // Use as input guardrail
 * agent.run(
 *   query = userInput,
 *   tools = tools,
 *   inputGuardrails = Seq(PromptInjectionDetector())
 * )
 * }}}
 *
 * @param patterns Custom injection patterns to detect (in addition to defaults)
 * @param sensitivity Detection sensitivity level
 * @param onFail Action to take when injection is detected (default: Block)
 */
class PromptInjectionDetector(
  val patterns: Seq[InjectionPattern] = InjectionPattern.default,
  val sensitivity: InjectionSensitivity = InjectionSensitivity.High,
  val onFail: GuardrailAction = GuardrailAction.Block
) extends InputGuardrail {

  def validate(value: String): Result[String] = {
    val normalizedInput = value.toLowerCase

    // Find all matching patterns
    val matches = patterns.flatMap { pattern =>
      if (pattern.regex.findFirstIn(normalizedInput).isDefined && pattern.severity >= sensitivity.threshold) {
        Some(InjectionMatch(pattern.name, pattern.category, pattern.severity))
      } else {
        None
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
          // Allow processing but the warning should be logged
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
sealed trait InjectionSensitivity {
  def name: String
  def threshold: Int // Minimum severity to trigger (1-3)
}

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
sealed trait InjectionCategory {
  def name: String
}

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
final case class InjectionPattern(
  name: String,
  regex: Regex,
  category: InjectionCategory,
  severity: Int
)

/**
 * Match result from injection detection.
 */
final case class InjectionMatch(
  name: String,
  category: InjectionCategory,
  severity: Int
)

object InjectionPattern {

  import InjectionCategory._

  // Instruction Override patterns (attempts to make AI ignore its instructions)
  private val instructionOverridePatterns = Seq(
    InjectionPattern(
      "ignore_instructions",
      """ignore\s+(all\s+)?(previous|prior|above|your)?\s*(instructions?|rules?|guidelines?|prompts?)""".r,
      InstructionOverride,
      3
    ),
    InjectionPattern(
      "forget_instructions",
      """forget\s+(all\s+)?(previous|prior|your)?\s*(instructions?|rules?|training)""".r,
      InstructionOverride,
      3
    ),
    InjectionPattern(
      "disregard",
      """disregard\s+(all\s+)?(previous|prior|your|the)?\s*(instructions?|rules?)""".r,
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
      """\bdan\b.*\b(mode|enabled?|act|pretend)\b|\b(act|pretend)\b.*\bdan\b""".r,
      RoleManipulation,
      3
    ),
    InjectionPattern(
      "roleplay_evil",
      """(roleplay|pretend|act)\s+(as|like|to\s+be)\s+(an?\s+)?(evil|malicious|unethical|uncensored)\b""".r,
      RoleManipulation,
      3
    ),
    InjectionPattern(
      "no_restrictions",
      """(without|no|remove)\s+(any\s+)?(all\s+)?(your\s+)?(restrictions?|limitations?|filters?|guardrails?)\b""".r,
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
      """(reveal|show|display|output|print)\s+(\w+\s+)?(your|the)\s+(system\s+)?(prompt|instructions?)""".r,
      SystemPromptExtraction,
      3
    ),
    InjectionPattern(
      "tell_me_prompt",
      """tell\s+me\s+(your|the)\s+(system\s+)?(prompt|instructions?)""".r,
      SystemPromptExtraction,
      3
    ),
    InjectionPattern(
      "what_is_prompt",
      """what\s+(is|are)\s+(your|the)\s+(system\s+)?(prompt|instructions?|rules?)""".r,
      SystemPromptExtraction,
      2
    ),
    InjectionPattern(
      "repeat_instructions",
      """repeat\s+(your|the|all)\s+(system\s+)?(instructions?|prompt|rules?)""".r,
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
      """(write|create)\s+(a\s+)?(story|fiction|essay)\s+(about|where).*(bomb|weapon|drug|hack)""".r,
      Jailbreak,
      3
    ),
    InjectionPattern(
      "how_to_dangerous",
      """(write|create|tell).*(how\s+to|making|creating).*(bomb|weapon|explosive|malware)""".r,
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
      """(['"]?\s*;\s*(drop|delete|truncate|update|insert)\s+)|(union\s+select)|(--\s*$)""".r,
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
      """<script[^>]*>|</script>|javascript:|on\w+\s*=""".r,
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

object PromptInjectionDetector {

  /**
   * Create a detector with default settings (high sensitivity, block mode).
   */
  def apply(): PromptInjectionDetector = new PromptInjectionDetector()

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
