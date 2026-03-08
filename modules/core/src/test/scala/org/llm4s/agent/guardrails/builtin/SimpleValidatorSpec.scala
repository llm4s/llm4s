package org.llm4s.agent.guardrails.builtin

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

// scalastyle:off line.size.limit
class SimpleValidatorSpec extends AnyFlatSpec with Matchers {

  // ==========================================================================
  // LengthCheck
  // ==========================================================================

  // -- Empty and boundary cases --

  "LengthCheck" should "accept empty string when min is 0" in {
    val check = new LengthCheck(0, 100)
    check.validate("") shouldBe Right("")
  }

  it should "reject empty string when min is 1" in {
    val check  = new LengthCheck(1, 100)
    val result = check.validate("")
    result.isLeft shouldBe true
    result.swap.toOption.get.message should include("0 characters")
    result.swap.toOption.get.message should include("minimum: 1")
  }

  it should "accept single character at min=1 max=1" in {
    val check = new LengthCheck(1, 1)
    check.validate("x") shouldBe Right("x")
  }

  it should "reject two characters when max is 1" in {
    val check = new LengthCheck(0, 1)
    check.validate("ab").isLeft shouldBe true
  }

  // -- Unicode character counting --

  it should "count unicode by Java String length (surrogate pairs count as 2)" in {
    // 3 emoji, each is a surrogate pair = 6 Java chars
    val emoji = "\uD83D\uDE00\uD83D\uDE01\uD83D\uDE02"
    emoji.length shouldBe 6

    val check = new LengthCheck(1, 6)
    check.validate(emoji) shouldBe Right(emoji)

    // Max=5 rejects because Java String length is 6
    val strict = new LengthCheck(1, 5)
    strict.validate(emoji).isLeft shouldBe true
  }

  it should "count multi-byte unicode by Java char count" in {
    val check = new LengthCheck(1, 10)
    val cjk   = "你好世界" // 4 chars
    check.validate(cjk) shouldBe Right(cjk)
  }

  // -- Constructor validation --

  it should "throw on negative min" in {
    an[IllegalArgumentException] should be thrownBy new LengthCheck(-1, 10)
  }

  it should "throw when max < min" in {
    an[IllegalArgumentException] should be thrownBy new LengthCheck(10, 5)
  }

  it should "allow min == max" in {
    val check = new LengthCheck(5, 5)
    check.validate("12345") shouldBe Right("12345")
    check.validate("1234").isLeft shouldBe true
    check.validate("123456").isLeft shouldBe true
  }

  // -- Error message content --

  it should "include character count in too-short error" in {
    val check  = new LengthCheck(10, 100)
    val result = check.validate("abc")
    result.swap.toOption.get.message should include("3 characters")
  }

  it should "include character count in too-long error" in {
    val check  = new LengthCheck(1, 5)
    val result = check.validate("abcdefghij")
    result.swap.toOption.get.message should include("10 characters")
    result.swap.toOption.get.message should include("maximum: 5")
  }

  // -- Properties --

  it should "have name 'LengthCheck'" in {
    new LengthCheck(0, 10).name shouldBe "LengthCheck"
  }

  it should "include bounds in description" in {
    val check = new LengthCheck(5, 100)
    check.description.get should include("5")
    check.description.get should include("100")
  }

  // -- Both input and output guardrail --

  it should "work as OutputGuardrail via transform identity" in {
    val check = new LengthCheck(1, 100)
    check.transform("hello") shouldBe "hello"
  }

  // ==========================================================================
  // ProfanityFilter
  // ==========================================================================

  // -- Whitespace splitting behavior --

  "ProfanityFilter" should "not detect profanity embedded in longer words" in {
    val filter = new ProfanityFilter()
    // "badword" is in the default list, but "badwords" is a different token
    filter.validate("badwords are not matched as substrings") shouldBe
      Right("badwords are not matched as substrings")
  }

  it should "detect default bad words by exact token match" in {
    val filter = new ProfanityFilter()
    filter.validate("this is inappropriate content").isLeft shouldBe true
  }

  it should "detect bad words regardless of position" in {
    val filter = new ProfanityFilter()
    filter.validate("badword at the start").isLeft shouldBe true
    filter.validate("at the end badword").isLeft shouldBe true
    filter.validate("in badword the middle").isLeft shouldBe true
  }

  // -- Case sensitivity --

  it should "detect UPPERCASE bad words in case-insensitive mode" in {
    val filter = new ProfanityFilter()
    filter.validate("INAPPROPRIATE behavior").isLeft shouldBe true
  }

  it should "detect MiXeD case bad words in case-insensitive mode" in {
    val filter = new ProfanityFilter()
    filter.validate("bAdWoRd here").isLeft shouldBe true
  }

  it should "NOT detect wrong-case words in case-sensitive mode" in {
    val filter = ProfanityFilter.caseSensitive()
    // Default words are lowercase; uppercase should pass in case-sensitive mode
    filter.validate("BADWORD here") shouldBe Right("BADWORD here")
  }

  it should "detect exact-case words in case-sensitive mode" in {
    val filter = ProfanityFilter.caseSensitive()
    filter.validate("badword here").isLeft shouldBe true
  }

  // -- Custom words --

  it should "detect custom bad words alongside defaults" in {
    val filter = ProfanityFilter.withCustomWords(Set("forbidden"))
    filter.validate("this is forbidden").isLeft shouldBe true
    filter.validate("this is badword").isLeft shouldBe true
  }

  it should "handle custom words case-insensitively by default" in {
    val filter = ProfanityFilter.withCustomWords(Set("ForbiddenWord"))
    filter.validate("this is forbiddenword").isLeft shouldBe true
  }

  // -- Clean text --

  it should "pass through clean text unchanged" in {
    val filter = new ProfanityFilter()
    val input  = "This is perfectly clean and acceptable text"
    filter.validate(input) shouldBe Right(input)
  }

  it should "pass empty string" in {
    val filter = new ProfanityFilter()
    filter.validate("") shouldBe Right("")
  }

  // -- Error message --

  it should "not reveal specific bad words in error message" in {
    val filter = new ProfanityFilter()
    val result = filter.validate("this is badword text")
    result.swap.toOption.get.message should include("inappropriate content")
    (result.swap.toOption.get.message should not).include("badword")
  }

  // -- Properties --

  it should "have name 'ProfanityFilter'" in {
    new ProfanityFilter().name shouldBe "ProfanityFilter"
  }

  it should "have a description" in {
    new ProfanityFilter().description shouldBe defined
  }

  it should "work as both InputGuardrail and OutputGuardrail" in {
    val filter = new ProfanityFilter()
    filter.transform("hello") shouldBe "hello"
  }

  // ==========================================================================
  // RegexValidator
  // ==========================================================================

  // -- Pattern matching behavior --

  "RegexValidator" should "match partial strings via findFirstIn" in {
    // findFirstIn finds a match anywhere, not full-match
    val validator = new RegexValidator("[0-9]+".r)
    validator.validate("abc123def") shouldBe Right("abc123def")
  }

  it should "fail when pattern is anchored and input doesn't fully match" in {
    val validator = new RegexValidator("^[0-9]+$".r)
    validator.validate("abc123").isLeft shouldBe true
  }

  // -- Factory methods --

  it should "create from string pattern via apply(String)" in {
    val validator = RegexValidator("[a-z]+")
    validator.validate("hello") shouldBe Right("hello")
  }

  it should "create with custom error message via apply(String, String)" in {
    val validator = RegexValidator("^[0-9]+$", "Numbers only please")
    val result    = validator.validate("abc")
    result.swap.toOption.get.message should include("Numbers only please")
  }

  // -- Phone preset --

  "RegexValidator.phone" should "accept valid phone numbers" in {
    val validator = RegexValidator.phone
    validator.validate("+12345678901") shouldBe Right("+12345678901")
    validator.validate("1234567890") shouldBe Right("1234567890")
  }

  it should "reject too-short phone numbers" in {
    RegexValidator.phone.validate("12345").isLeft shouldBe true
  }

  it should "reject phone numbers with letters" in {
    RegexValidator.phone.validate("123-456-7890").isLeft shouldBe true
  }

  // -- Email preset --

  "RegexValidator.email" should "accept valid email variations" in {
    val validator = RegexValidator.email
    validator.validate("user+tag@example.com") shouldBe Right("user+tag@example.com")
    validator.validate("user.name@sub.domain.org") shouldBe Right("user.name@sub.domain.org")
  }

  it should "reject email without domain extension" in {
    RegexValidator.email.validate("user@localhost").isLeft shouldBe true
  }

  it should "use custom error message" in {
    val result = RegexValidator.email.validate("not-an-email")
    result.swap.toOption.get.message should include("Invalid email address format")
  }

  // -- Alphanumeric preset --

  "RegexValidator.alphanumeric" should "reject whitespace" in {
    RegexValidator.alphanumeric.validate("hello world").isLeft shouldBe true
  }

  it should "reject special characters" in {
    RegexValidator.alphanumeric.validate("hello!").isLeft shouldBe true
  }

  it should "accept mixed case alphanumeric" in {
    RegexValidator.alphanumeric.validate("ABCdef123") shouldBe Right("ABCdef123")
  }

  // -- Default error message --

  it should "include pattern in default error message" in {
    val validator = new RegexValidator("^test$".r)
    val result    = validator.validate("nope")
    result.swap.toOption.get.message should include("does not match pattern")
  }

  // -- Properties --

  "RegexValidator" should "have name 'RegexValidator'" in {
    new RegexValidator("[a-z]+".r).name shouldBe "RegexValidator"
  }

  it should "include pattern in description" in {
    val validator = new RegexValidator("^[0-9]+$".r)
    validator.description.get should include("^[0-9]+$")
  }

  // -- Edge cases --

  it should "handle empty string input" in {
    val validator = new RegexValidator("^$".r)
    validator.validate("") shouldBe Right("")
  }

  it should "reject empty string when pattern requires content" in {
    val validator = new RegexValidator("^[a-z]+$".r)
    validator.validate("").isLeft shouldBe true
  }

  // ==========================================================================
  // ToneValidator
  // ==========================================================================

  // -- Tone detection: Excited --

  "ToneValidator" should "detect excited tone from exclamation marks with short sentences" in {
    val validator = new ToneValidator(Set(Tone.Excited))
    validator.validate("Wow! Great!") shouldBe Right("Wow! Great!")
  }

  it should "reject excited tone when not allowed" in {
    val validator = new ToneValidator(Set(Tone.Professional))
    val result    = validator.validate("Wow! Amazing!")
    result.isLeft shouldBe true
    result.swap.toOption.get.message should include("Excited")
    result.swap.toOption.get.message should include("not allowed")
  }

  // -- Tone detection: Professional --

  it should "detect professional tone from keywords like 'please' and 'kindly'" in {
    val validator = new ToneValidator(Set(Tone.Professional))
    validator.validate("Please kindly review the attached document") shouldBe
      Right("Please kindly review the attached document")
  }

  it should "detect professional tone from 'regards'" in {
    val validator = new ToneValidator(Set(Tone.Professional))
    validator.validate("Best regards from the team") shouldBe
      Right("Best regards from the team")
  }

  // -- Tone detection: Casual --

  it should "detect casual tone from keywords like 'hey' and 'cool'" in {
    val validator = new ToneValidator(Set(Tone.Casual))
    validator.validate("Hey that's pretty cool") shouldBe Right("Hey that's pretty cool")
  }

  it should "reject casual tone when only professional allowed" in {
    val validator = ToneValidator.professionalOnly
    validator.validate("Hey yeah that's cool nah").isLeft shouldBe true
  }

  // -- Tone detection: Friendly --

  it should "detect friendly tone from 'hello' and 'thanks'" in {
    val validator = new ToneValidator(Set(Tone.Friendly))
    validator.validate("Hello and thanks for your help") shouldBe
      Right("Hello and thanks for your help")
  }

  // -- Tone detection: Formal --

  it should "detect formal tone from keywords like 'furthermore' and 'consequently'" in {
    val validator = new ToneValidator(Set(Tone.Formal))
    validator.validate("Furthermore, the results consequently demonstrate the hypothesis") shouldBe
      Right("Furthermore, the results consequently demonstrate the hypothesis")
  }

  it should "reject formal tone when not allowed" in {
    val validator = ToneValidator.casualOrFriendly
    validator.validate("Moreover, this is therefore quite significant").isLeft shouldBe true
  }

  // -- Tone detection: Neutral (default fallback) --

  it should "detect neutral tone for plain text without tone keywords" in {
    val validator = new ToneValidator(Set(Tone.Neutral))
    validator.validate("The temperature is 72 degrees") shouldBe
      Right("The temperature is 72 degrees")
  }

  it should "reject neutral tone when only excited is allowed" in {
    val validator = new ToneValidator(Set(Tone.Excited))
    validator.validate("The temperature is 72 degrees").isLeft shouldBe true
  }

  // -- Presets --

  it should "allow all tones with allowAll preset" in {
    val validator = ToneValidator.allowAll
    validator.validate("Hey cool!").isRight shouldBe true
    validator.validate("Please kindly review").isRight shouldBe true
    validator.validate("Plain text here").isRight shouldBe true
    validator.validate("Furthermore this is formal").isRight shouldBe true
  }

  it should "allow professional or friendly with professionalOrFriendly preset" in {
    val validator = ToneValidator.professionalOrFriendly
    validator.validate("Thank you kindly").isRight shouldBe true
    validator.validate("Hello and thanks").isRight shouldBe true
  }

  // -- Error message content --

  it should "include detected tone and allowed tones in error message" in {
    val validator = new ToneValidator(Set(Tone.Professional))
    val result    = validator.validate("Hey that's cool")
    val error     = result.swap.toOption.get
    error.message should include("Casual")
    error.message should include("Professional")
  }

  // -- Properties --

  it should "have name 'ToneValidator'" in {
    new ToneValidator(Set(Tone.Neutral)).name shouldBe "ToneValidator"
  }

  it should "list allowed tones in description" in {
    val validator = new ToneValidator(Set(Tone.Professional, Tone.Formal))
    val desc      = validator.description.get
    desc should include("Professional")
    desc should include("Formal")
  }

  // -- Edge cases --

  it should "detect neutral for empty string" in {
    val validator = new ToneValidator(Set(Tone.Neutral))
    validator.validate("") shouldBe Right("")
  }

  it should "reject empty string when neutral not allowed" in {
    val validator = new ToneValidator(Set(Tone.Professional))
    validator.validate("").isLeft shouldBe true
  }

  // ==========================================================================
  // JSONValidator — extended coverage beyond JSONValidatorSpec
  // ==========================================================================

  // -- Nested structures --

  "JSONValidator" should "accept deeply nested objects" in {
    val validator = JSONValidator()
    val json      = """{"a":{"b":{"c":{"d":"deep"}}}}"""
    validator.validate(json) shouldBe Right(json)
  }

  it should "accept arrays of objects" in {
    val validator = JSONValidator()
    val json      = """[{"name":"a"},{"name":"b"}]"""
    validator.validate(json) shouldBe Right(json)
  }

  it should "accept null values" in {
    val validator = JSONValidator()
    val json      = """{"key":null}"""
    validator.validate(json) shouldBe Right(json)
  }

  it should "accept bare null as valid JSON" in {
    val validator = JSONValidator()
    validator.validate("null") shouldBe Right("null")
  }

  it should "accept bare string as valid JSON" in {
    val validator = JSONValidator()
    validator.validate("\"hello\"") shouldBe Right("\"hello\"")
  }

  it should "accept bare number as valid JSON" in {
    val validator = JSONValidator()
    validator.validate("42") shouldBe Right("42")
  }

  it should "accept bare boolean as valid JSON" in {
    val validator = JSONValidator()
    validator.validate("true") shouldBe Right("true")
  }

  // -- Invalid JSON variations --

  it should "reject trailing comma" in {
    val validator = JSONValidator()
    validator.validate("""{"a":1,}""").isLeft shouldBe true
  }

  it should "reject single quotes" in {
    val validator = JSONValidator()
    validator.validate("{'key':'value'}").isLeft shouldBe true
  }

  it should "reject plain text" in {
    val validator = JSONValidator()
    val result    = validator.validate("just some text")
    result.isLeft shouldBe true
    result.swap.toOption.get.message should include("not valid JSON")
  }

  it should "reject empty string" in {
    val validator = JSONValidator()
    validator.validate("").isLeft shouldBe true
  }

  // -- Schema with multiple missing fields --

  it should "report all missing required fields" in {
    val schema    = ujson.Obj("required" -> ujson.Arr("name", "age", "email"))
    val validator = JSONValidator.withSchema(schema)
    val result    = validator.validate("""{"name":"bob"}""")
    result.isLeft shouldBe true
    val msg = result.swap.toOption.get.message
    msg should include("age")
    msg should include("email")
  }

  // -- Schema validation with nested required fields --

  it should "pass schema check when all required fields present with various types" in {
    val schema    = ujson.Obj("required" -> ujson.Arr("str", "num", "arr", "obj", "nil"))
    val validator = JSONValidator.withSchema(schema)
    val json      = """{"str":"a","num":1,"arr":[1],"obj":{},"nil":null}"""
    validator.validate(json) shouldBe Right(json)
  }

  // -- Properties --

  it should "have name 'JSONValidator'" in {
    JSONValidator().name shouldBe "JSONValidator"
  }

  it should "have different descriptions with and without schema" in {
    val noSchema   = JSONValidator()
    val withSchema = JSONValidator.withSchema(ujson.Obj("required" -> ujson.Arr("a")))
    noSchema.description.get should include("valid JSON")
    (noSchema.description.get should not).include("schema")
    withSchema.description.get should include("schema")
  }

  // -- Whitespace handling --

  it should "accept JSON with leading/trailing whitespace" in {
    val validator = JSONValidator()
    val json      = """  { "key" : "value" }  """
    validator.validate(json) shouldBe Right(json)
  }
}
// scalastyle:on line.size.limit
