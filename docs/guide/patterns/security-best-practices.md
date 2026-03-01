---
layout: page
title: Security Best Practices
parent: Real-World Application Patterns
nav_order: 6
---

# Security Best Practices

> **Note:** Code examples in this guide are illustrative pseudocode showing recommended patterns. For working examples using the actual LLM4S API, see [modules/samples](../../../modules/samples/).

Comprehensive security patterns for protecting sensitive data, managing credentials, and maintaining audit trails in production LLM systems.

## API Key Management

> **Note:** Code examples in this guide are illustrative pseudocode showing recommended patterns. For working examples using the actual LLM4S API, see [modules/samples](../../../modules/samples/).

### Configuration-Based Access

```text
object SecureKeyManagement {
  
  import org.llm4s.config.Llm4sConfig
  
  def loadApiKey(provider: String): Result[String] = {
    Llm4sConfig.provider(provider).apiKey match {
      case Some(key) if key.nonEmpty => Result.success(key)
      case _ => Result.failure(
        s"API key for provider '$provider' not configured. " +
        s"Set it in your configuration file or environment."
      )
    }
  }
  
  // Safe logging (don't log keys!)
  def logApiUsage(key: String, provider: String): Unit = {
    val maskedKey = key.take(5) + "..." + key.takeRight(5)
    println(s"Using provider: $provider with key: $maskedKey")
  }
}
```

### Key Vault Integration

```text
object KeyVault {
  
  import com.azure.security.keyvault.secrets._
  
  class SecureKeyManager(vaultUri: String) {
    private val client = new SecretClientBuilder()
      .vaultUrl(vaultUri)
      .buildClient()
    
    def getKey(keyName: String): Result[String] = {
      try {
        val secret = client.getSecret(keyName)
        Result.success(secret.getValue)
      } catch {
        case e: Exception =>
          Result.failure(s"Failed to retrieve key from vault: ${e.getMessage}")
      }
    }
  }
  
  // Usage
  def getOpenAIKey(): Result[String] = {
    val vaultManager = new SecureKeyManager("https://myvault.vault.azure.net/")
    vaultManager.getKey("openai-api-key")
  }
}
```

### Key Rotation

```text
object KeyRotation {
  
  case class KeyMetadata(
    key: String,
    provider: String,
    createdAt: Long,
    rotatedAt: Long,
    expiresAt: Long
  ) {
    def isExpired: Boolean = System.currentTimeMillis() > expiresAt
    def daysSinceRotation: Long = 
      (System.currentTimeMillis() - rotatedAt) / (1000 * 60 * 60 * 24)
  }
  
  class KeyRotationManager {
    private var activeKey: KeyMetadata = _
    private var rotationScheduler: Option[java.util.Timer] = None
    
    def rotateKey(newKey: String, provider: String): Unit = {
      val now = System.currentTimeMillis()
      val thirtyDaysFromNow = now + (30 * 24 * 60 * 60 * 1000)
      
      activeKey = KeyMetadata(
        key = newKey,
        provider = provider,
        createdAt = now,
        rotatedAt = now,
        expiresAt = thirtyDaysFromNow
      )
      
      println(s"Key rotated for $provider. Valid until ${new java.util.Date(thirtyDaysFromNow)}")
    }
    
    def scheduleRotation(intervalDays: Int = 30): Unit = {
      val timer = new java.util.Timer()
      val delayMs = intervalDays * 24 * 60 * 60 * 1000L
      
      timer.scheduleAtFixedRate(
        new java.util.TimerTask {
          def run(): Unit = {
            println("Time to rotate API keys!")
            // In production: fetch new key from vault and rotate
          }
        },
        delayMs,
        delayMs
      )
      
      rotationScheduler = Some(timer)
    }
  }
}
```

---

## Input Validation

### Query Validation

```text
object InputValidation {
  
  case class ValidationRule(
    name: String,
    validate: String => Boolean,
    errorMessage: String
  )
  
  class InputValidator {
    private val rules = scala.collection.mutable.ListBuffer[ValidationRule]()
    
    def addRule(rule: ValidationRule): Unit = {
      rules += rule
    }
    
    def validate(input: String): Result[String] = {
      rules.find(!_.validate(input)) match {
        case Some(failedRule) => 
          Result.failure(failedRule.errorMessage)
        case None => 
          Result.success(input)
      }
    }
  }
  
  // Common validation rules
  def setupCommonValidations(): InputValidator = {
    val validator = new InputValidator()
    
    // Length check
    validator.addRule(ValidationRule(
      name = "length",
      validate = _.length <= 10000,
      errorMessage = "Input exceeds maximum length of 10000 characters"
    ))
    
    // No injection patterns
    validator.addRule(ValidationRule(
      name = "no_injection",
      validate = !containsSQLInjectionPattern(_),
      errorMessage = "Input contains potentially harmful patterns"
    ))
    
    // No excessive special characters
    validator.addRule(ValidationRule(
      name = "no_spam",
      validate = countSpecialChars(_) < 0.5,
      errorMessage = "Input contains too many special characters"
    ))
    
    validator
  }
  
  private def containsSQLInjectionPattern(input: String): Boolean = {
    val sqlPatterns = List("';", "--", "/*", "*/", "xp_", "sp_")
    sqlPatterns.exists(input.toLowerCase.contains(_))
  }
  
  private def countSpecialChars(input: String): Double = {
    val specialCount = input.count(!_.isLetterOrDigit)
    specialCount.toDouble / input.length
  }
}
```

### Prompt Injection Protection

```text
object PromptInjectionProtection {
  
  def detectPromptInjection(userInput: String): Boolean = {
    val injectionIndicators = List(
      "ignore all previous instructions",
      "forget everything",
      "system prompt",
      "you are now",
      "instead please",
      "role play as",
      "pretend you are"
    )
    
    val lowerInput = userInput.toLowerCase
    injectionIndicators.exists(lowerInput.contains(_))
  }
  
  def sanitizePrompt(userInput: String): Result[String] = {
    if (detectPromptInjection(userInput)) {
      Result.failure(
        "Your input was flagged as potentially malicious. " +
        "Please rephrase your request without system instructions."
      )
    } else {
      Result.success(userInput)
    }
  }
  
  // Use prompt templating to separate user input
  def safePromptTemplate(
    userQuery: String,
    systemPrompt: String
  ): String = {
    s"""$systemPrompt

User Query:
[BEGIN USER INPUT]
$userQuery
[END USER INPUT]

Please answer based only on the user's question above."""
  }
}
```

---

## Output Sanitization

### PII Redaction

```text
object PIIRedaction {
  
  import scala.util.matching.Regex
  
  object PatternDetectors {
    val emailPattern = new Regex(
      """[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}"""
    )
    
    val phonePattern = new Regex(
      """(\d{3}[-.\s]?){2}\d{4}"""
    )
    
    val ssn Pattern = new Regex(
      """\b\d{3}-\d{2}-\d{4}\b"""
    )
    
    val creditCardPattern = new Regex(
      """(?:\d[ -]*?){13,16}"""
    )
  }
  
  class PIIRedactor {
    def redact(text: String): String = {
      var result = text
      
      // Redact emails
      result = PatternDetectors.emailPattern.replaceAllIn(
        result,
        "[EMAIL]"
      )
      
      // Redact phone numbers
      result = PatternDetectors.phonePattern.replaceAllIn(
        result,
        "[PHONE]"
      )
      
      // Redact SSNs
      result = PatternDetectors.ssnPattern.replaceAllIn(
        result,
        "[SSN]"
      )
      
      // Redact credit cards
      result = PatternDetectors.creditCardPattern.replaceAllIn(
        result,
        "[CREDIT_CARD]"
      )
      
      result
    }
  }
  
  // Usage
  def answerWithSanitization(
    query: String,
    agent: Agent
  ): Result[String] = {
    agent.run(query).map { response =>
      val redactor = new PIIRedactor()
      redactor.redact(response.message)
    }
  }
}
```

---

## Audit Logging

### Comprehensive Audit Trail

```text
object AuditLogging {
  
  import org.slf4j.LoggerFactory
  import java.time.Instant
  
  val auditLogger = LoggerFactory.getLogger("AUDIT")
  
  case class AuditEvent(
    timestamp: Instant,
    userId: String,
    action: String,
    resource: String,
    details: Map[String, String],
    success: Boolean,
    errorMessage: Option[String] = None
  )
  
  def logAPICall(
    userId: String,
    provider: String,
    model: String,
    inputTokens: Int,
    outputTokens: Int
  ): Unit = {
    val event = AuditEvent(
      timestamp = Instant.now(),
      userId = userId,
      action = "API_CALL",
      resource = s"$provider/$model",
      details = Map(
        "input_tokens" -> inputTokens.toString,
        "output_tokens" -> outputTokens.toString
      ),
      success = true
    )
    
    auditLogger.info(
      s"${event.timestamp} | User: ${event.userId} | " +
      s"Action: ${event.action} | Resource: ${event.resource}"
    )
  }
  
  def logAccessToSensitiveData(
    userId: String,
    dataType: String,
    recordCount: Int,
    accessReason: String
  ): Unit = {
    auditLogger.info(
      s"${Instant.now()} | User: $userId | " +
      s"Accessed: $dataType ($recordCount records) | " +
      s"Reason: $accessReason"
    )
  }
  
  def logAuthenticationAttempt(
    userId: String,
    success: Boolean,
    method: String
  ): Unit = {
    auditLogger.info(
      s"${Instant.now()} | User: $userId | " +
      s"Authentication: ${if (success) "SUCCESS" else "FAILED"} via $method"
    )
  }
}
```

### Compliance Logging

```text
object ComplianceLogging {
  
  case class DataProcessingLog(
    timestamp: Long,
    userId: String,
    dataCategory: String,
    operation: String, // PROCESS, STORE, SHARE, DELETE
    retentionDays: Int,
    userConsent: Boolean
  )
  
  class ComplianceLogger {
    private val logs = scala.collection.mutable.ListBuffer[DataProcessingLog]()
    
    def logDataProcessing(
      userId: String,
      dataCategory: String,
      operation: String,
      retentionDays: Int
    ): Unit = {
      logs += DataProcessingLog(
        timestamp = System.currentTimeMillis(),
        userId = userId,
        dataCategory = dataCategory,
        operation = operation,
        retentionDays = retentionDays,
        userConsent = true
      )
    }
    
    def getComplianceReport(days: Int = 30): String = {
      val cutoff = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
      val recent = logs.filter(_.timestamp > cutoff)
      
      val operationCounts = recent.groupBy(_.operation).mapValues(_.length)
      val dataCategoryCounts = recent.groupBy(_.dataCategory).mapValues(_.length)
      
      s"""Compliance Report (last $days days):
        |Operations: $operationCounts
        |Data Categories: $dataCategoryCounts
        |Total Records Processed: ${recent.length}""".stripMargin
    }
  }
}
```

---

## Data Retention

### Automatic Data Expiration

```text
object DataRetention {
  
  case class DataRecord(
    id: String,
    data: String,
    createdAt: Long,
    ttlDays: Int
  ) {
    def isExpired: Boolean = {
      val now = System.currentTimeMillis()
      val expirationTime = createdAt + (ttlDays * 24 * 60 * 60 * 1000L)
      now > expirationTime
    }
  }
  
  class RetentionPolicy {
    def getRetentionDays(dataType: String): Int = dataType match {
      case "user_query" => 30
      case "api_key" => 365
      case "error_log" => 90
      case "audit_log" => 365 * 7 // 7 years
      case _ => 30
    }
    
    def scheduleCleanup(): Unit = {
      val timer = new java.util.Timer()
      val dailyMs = 24 * 60 * 60 * 1000L
      
      timer.scheduleAtFixedRate(
        new java.util.TimerTask {
          def run(): Unit = {
            println("Running data retention cleanup...")
            // Delete expired records from database
          }
        },
        dailyMs,
        dailyMs
      )
    }
  }
}
```

---

## Best Practices

### ✅ Do's

- **Never log API keys**: Even in debug mode
- **Rotate keys regularly**: At least every 30 days
- **Use key vaults**: Don't hardcode secrets
- **Validate all input**: Both user queries and system inputs
- **Sanitize all output**: Remove PII before returning
- **Keep audit trails**: For compliance and debugging
- **Use HTTPS only**: For all API communication
- **Implement rate limiting**: To prevent abuse
- **Monitor suspicious patterns**: Unusual access or usage
- **Use least privilege**: Grant minimal required permissions

### ❌ Don'ts

- **Don't store keys in code**: Use environment variables or vaults
- **Don't trust user input**: Always validate and sanitize
- **Don't output sensitive data**: Check for PII before returning
- **Don't log full queries**: Log only hashed or redacted version
- **Don't skip authentication**: Verify user identity
- **Don't use weak passwords**: Enforce strong credential policies
- **Don't ignore security updates**: Keep dependencies patched
- **Don't expose error details**: Log full errors, show generic messages to users
- **Don't test in production**: Use staging for security tests
- **Don't assume perimeter security**: Defense in depth required

---

## Security Checklist

### ✅ Before Production

- [ ] API keys loaded from secure vault, not code
- [ ] HTTPS/TLS enforced for all API calls
- [ ] Input validation on all user-facing endpoints
- [ ] Output sanitization removes PII
- [ ] Audit logging enabled for sensitive operations
- [ ] Rate limiting configured
- [ ] Authentication required for all endpoints
- [ ] Data retention policy defined and enforced
- [ ] Error messages don't expose system details
- [ ] Secrets not logged anywhere
- [ ] Security tests included in test suite
- [ ] Penetration testing scheduled
- [ ] Incident response plan documented

---

## See Also

- [Production Monitoring](./production-monitoring.md) - Monitor security events
- [Multi-Agent Orchestration](./multi-agent-orchestration.md) - Secure agent communication
- [OWASP Top 10](https://owasp.org/www-project-top-ten/) - Web application security

---

**Last Updated:** February 2026  
**Status:** Production Ready
