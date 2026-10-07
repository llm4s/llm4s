package org.llm4s.jev

import org.llm4s.error.ValidationError
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class JevRequestSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def json(request: JevRequest, model: String = "jev-latest"): String = ujson.write(request.toJson(model))

  private def refused(request: JevRequest): ValidationError =
    request.validate.left.value match {
      case v: ValidationError => v
      case other              => fail(s"expected a ValidationError, got $other")
    }

  private val noul = JevQuestion.noul("Does this convey urgency?")

  // ---- the wire format, as https://docs.typesafe.ai/api.md documents it ----

  "A request" should "serialise the contract's Noul example exactly" in {
    val request = JevRequest("Help! My payouts have been failing for 3 days.", Map("is_urgent" -> noul))

    json(request) shouldBe
      """{"state":"Help! My payouts have been failing for 3 days.","model":"jev-latest","questions":{"is_urgent":{"type":"noul","instructions":"Does this convey urgency?"}}}"""
  }

  it should "send the criteria of a Noul only when something is said about yes or no" in {
    val both   = JevQuestion.noul("Does this convey urgency?", "Explicitly time-sensitive", "No urgency expressed")
    val onlyNo = JevQuestion.Noul(ujson.Str("q"), None, Some(ujson.Str("never")))

    json(JevRequest("s", Map("a" -> both))) should include(
      """"criteria":{"true":"Explicitly time-sensitive","false":"No urgency expressed"}"""
    )
    json(JevRequest("s", Map("a" -> onlyNo))) should include(""""criteria":{"false":"never"}""")
    (json(JevRequest("s", Map("a" -> noul))) should not).include("criteria")
  }

  it should "serialise a Choice with its options in order and a null for an option without description" in {
    val choice = JevQuestion.Choice(
      ujson.Str("Which team?"),
      Seq("billing" -> Some(ujson.Str("Payments")), "technical" -> None, "sales" -> Some(ujson.Str("Pricing")))
    )

    json(JevRequest("s", Map("department" -> choice))) should include(
      """"department":{"type":"choice","instructions":"Which team?","criteria":{"billing":"Payments","technical":null,"sales":"Pricing"}}"""
    )
  }

  it should "serialise a Score as an ordered array of levels" in {
    val score = JevQuestion.score("How frustrated?", "Calm", "Frustrated", "Very angry")

    json(JevRequest("s", Map("frustration" -> score))) should include(
      """"frustration":{"type":"score","instructions":"How frustrated?","criteria":["Calm","Frustrated","Very angry"]}"""
    )
  }

  it should "pass structured state and structured instructions through untouched" in {
    val state = ujson.Obj("ticket" -> ujson.Obj("id" -> 7, "tags" -> ujson.Arr("a", "b")))
    val question = JevQuestion.Noul(
      ujson.Obj("potential" -> ujson.Obj("name" -> "John"), "question" -> "Is it the same person as `potential`?")
    )

    val body = ujson.read(json(JevRequest(state, Map("same" -> question))))

    body("state") shouldBe state
    body("questions")("same")("instructions")("question").str should include("`potential`")
  }

  it should "use the model it names, else the one it is given" in {
    val request = JevRequest("s", Map("a" -> noul))

    ujson.read(json(request, "jev-latest"))("model").str shouldBe "jev-latest"
    ujson.read(json(request.withModel("jev-1.13.0"), "jev-latest"))("model").str shouldBe "jev-1.13.0"
    ujson
      .read(json(request.withModel("x").withModel(Option.empty[String]), "jev-latest"))("model")
      .str shouldBe "jev-latest"
  }

  it should "escape what the JSON needs escaped" in {
    val request = JevRequest("line one\n\"quoted\" \\ back ☃", Map("a" -> noul))

    ujson.read(json(request))("state").str shouldBe "line one\n\"quoted\" \\ back ☃"
  }

  // ---- validation: only what the API documents, plus structure ----

  it should "accept a well-formed request" in {
    JevRequest("s", Map("a" -> noul)).validate shouldBe Right(())
  }

  it should "refuse a state that is not a string, an object or an array" in {
    Seq(ujson.Null, ujson.Num(1), ujson.True).foreach { state =>
      refused(JevRequest(state, Map("a" -> noul))).field shouldBe "state"
    }
  }

  it should "refuse a request with no questions" in {
    refused(JevRequest("s", Map.empty)).field shouldBe "questions"
  }

  it should "refuse a blank question id and one with a control character" in {
    refused(JevRequest("s", Map(" " -> noul))).field shouldBe "questions"
    refused(JevRequest("s", Map("a\nb" -> noul))).field shouldBe "questions"
  }

  it should "refuse instructions that are blank or not text or structure, naming the question" in {
    refused(JevRequest("s", Map("q" -> JevQuestion.Noul(ujson.Str("  "))))).field shouldBe "questions.q.instructions"
    refused(JevRequest("s", Map("q" -> JevQuestion.Noul(ujson.Num(3))))).field shouldBe "questions.q.instructions"
    refused(JevRequest("s", Map("q" -> JevQuestion.Noul(ujson.Null)))).field shouldBe "questions.q.instructions"
  }

  it should "refuse a Noul criterion that is not text or structure" in {
    refused(JevRequest("s", Map("q" -> JevQuestion.Noul(ujson.Str("q"), Some(ujson.Num(1)), None)))).field shouldBe
      "questions.q.criteria.true"
    refused(JevRequest("s", Map("q" -> JevQuestion.Noul(ujson.Str("q"), None, Some(ujson.Str(" ")))))).field shouldBe
      "questions.q.criteria.false"
  }

  it should "limit a Choice to 255 options, with at least one, named and unique" in {
    def choice(options: Seq[(String, Option[ujson.Value])]) =
      JevRequest("s", Map("c" -> JevQuestion.Choice(ujson.Str("q"), options)))
    val many = (1 to 255).map(i => s"o$i" -> Option.empty[ujson.Value])

    choice(many).validate shouldBe Right(())
    refused(choice(many :+ ("o256" -> None))).message should include("at most 255")
    refused(choice(Seq.empty)).message should include("at least one")
    refused(choice(Seq(" " -> None))).message should include("blank")
    refused(choice(Seq("a" -> None, "a" -> None))).message should include("unique")
    refused(choice(Seq("a" -> Some(ujson.Num(1))))).field shouldBe "questions.c.criteria.a"
  }

  it should "take a Score of between 2 and 10 levels, each text or structure" in {
    def score(levels: Seq[ujson.Value]) = JevRequest("s", Map("s" -> JevQuestion.Score(ujson.Str("q"), levels)))
    def levels(n: Int)                  = (1 to n).map(i => ujson.Str(s"level $i"))

    score(levels(2)).validate shouldBe Right(())
    score(levels(10)).validate shouldBe Right(())
    refused(score(levels(1))).message should include("between 2 and 10")
    refused(score(levels(11))).message should include("between 2 and 10")
    refused(score(Seq(ujson.Str("a"), ujson.Null))).field shouldBe "questions.s.criteria[1]"
  }

  it should "refuse a blank model" in {
    refused(JevRequest("s", Map("a" -> noul)).withModel(" ")).field shouldBe "model"
  }

  it should "show only sizes and names when printed, never the state or a header value" in {
    val request = JevRequest("patient John Smith, card 4111 1111 1111 1111", Map("b" -> noul, "a" -> noul))
      .withHeader("X-Token", "header-secret")

    request.toString shouldBe
      "JevRequest(state=text of 44 characters, questions=[a, b], model=default, headers=[X-Token])"
    JevRequest(ujson.Obj("ssn" -> "123"), Map("a" -> noul)).toString should (include("an object")
      .and(not)
      .include("123"))
    JevRequest(ujson.Arr("x"), Map("a" -> noul)).withModel("jev-1.13.0").toString should
      (include("an array").and(include("jev-1.13.0")))
    JevRequest(ujson.Null, Map("a" -> noul)).toString should include("invalid")
  }

  // ---- extra headers ----

  it should "carry extra headers, a later one replacing an earlier one of the same name" in {
    val request =
      JevRequest("s", Map("a" -> noul)).withHeader("X-Trace", "1").withHeader("X-Trace", "2").withHeader("X-B", "b")

    request.headers shouldBe Map("X-Trace" -> "2", "X-B" -> "b")
    request.withHeaders(Map("X-C" -> "c")).headers shouldBe Map("X-C" -> "c")
    request.validate shouldBe Right(())
  }

  it should "treat header names as case-insensitive, replacing one whatever its case and refusing both cases" in {
    JevRequest("s", Map("a" -> noul)).withHeader("X-Trace", "1").withHeader("x-trace", "2").headers shouldBe
      Map("x-trace" -> "2")
    refused(JevRequest("s", Map("a" -> noul)).withHeaders(Map("X-Trace" -> "1", "x-trace" -> "2"))).message should
      include("same header")
  }

  it should "refuse a header whose name or value could inject another header" in {
    def headers(h: Map[String, String]) = JevRequest("s", Map("a" -> noul)).withHeaders(h)

    refused(headers(Map("X-A\r\nX-Evil" -> "1"))).field shouldBe "headers"
    refused(headers(Map("X-A" -> "1\r\nX-Evil: 1"))).field shouldBe "headers"
    refused(headers(Map("X-A" -> "1\nX-Evil: 1"))).field shouldBe "headers"
    refused(headers(Map("X A" -> "1"))).field shouldBe "headers"
    refused(headers(Map("" -> "1"))).field shouldBe "headers"
    refused(headers(Map("X-A" -> "café"))).field shouldBe "headers"
  }

  it should "refuse a header the client sets itself, whatever its case" in {
    Seq(
      "Authorization",
      "authorization",
      "CONTENT-TYPE",
      "Content-Length",
      "Host",
      "Transfer-Encoding",
      "Connection",
      "Accept"
    )
      .foreach { name =>
        refused(JevRequest("s", Map("a" -> noul)).withHeader(name, "x")).message should include("cannot be overridden")
      }
  }
}
