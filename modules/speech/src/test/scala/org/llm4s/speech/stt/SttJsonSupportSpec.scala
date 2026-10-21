package org.llm4s.speech.stt

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ujson.Value

/**
 * The tolerant JSON accessors and the word helpers shared by the Whisper and Vosk transcribers.
 */
class SttJsonSupportSpec extends AnyFlatSpec with Matchers {

  private def json(text: String): Value = ujson.read(text)

  private def word(text: String, confidence: Option[Double] = None): WordTimestamp =
    WordTimestamp(text, 0.0, 1.0, None, confidence)

  "SttJsonSupport.field" should "return the value of a present key" in {
    SttJsonSupport.field(json("""{"text": "hello"}"""), "text") shouldBe Some(ujson.Str("hello"))
  }

  it should "return None for a missing key" in {
    SttJsonSupport.field(json("""{"text": "hello"}"""), "other") shouldBe None
  }

  it should "return None, not throw, when the value is not an object" in {
    SttJsonSupport.field(json("\"just a string\""), "text") shouldBe None
    SttJsonSupport.field(json("42"), "text") shouldBe None
    SttJsonSupport.field(json("[1, 2, 3]"), "text") shouldBe None
  }

  "SttJsonSupport.stringField" should "return a string value" in {
    SttJsonSupport.stringField(json("""{"text": "hello"}"""), "text") shouldBe Some("hello")
  }

  it should "return None for a number, an object or null" in {
    SttJsonSupport.stringField(json("""{"text": 1}"""), "text") shouldBe None
    SttJsonSupport.stringField(json("""{"text": {"a": "b"}}"""), "text") shouldBe None
    SttJsonSupport.stringField(json("""{"text": null}"""), "text") shouldBe None
  }

  it should "return None for a missing key" in {
    SttJsonSupport.stringField(json("{}"), "text") shouldBe None
  }

  "SttJsonSupport.doubleField" should "return a number value" in {
    SttJsonSupport.doubleField(json("""{"conf": 0.75}"""), "conf") shouldBe Some(0.75)
  }

  it should "return None for a string that looks like a number" in {
    SttJsonSupport.doubleField(json("""{"conf": "1.5"}"""), "conf") shouldBe None
  }

  it should "return None for null and for a missing key" in {
    SttJsonSupport.doubleField(json("""{"conf": null}"""), "conf") shouldBe None
    SttJsonSupport.doubleField(json("{}"), "conf") shouldBe None
  }

  "SttJsonSupport.arrayField" should "return the elements in order" in {
    val elements = SttJsonSupport.arrayField(json("""{"words": [3, 1, 2]}"""), "words")

    elements.map(_.num) shouldBe Seq(3.0, 1.0, 2.0)
  }

  it should "return an empty Seq for a missing key" in {
    SttJsonSupport.arrayField(json("{}"), "words") shouldBe empty
  }

  it should "return an empty Seq for a value that is not an array" in {
    SttJsonSupport.arrayField(json("""{"words": "none"}"""), "words") shouldBe empty
    SttJsonSupport.arrayField(json("""{"words": {"a": 1}}"""), "words") shouldBe empty
    SttJsonSupport.arrayField(json("""{"words": null}"""), "words") shouldBe empty
  }

  "SttJsonSupport.renderWords" should "join the words with single spaces" in {
    SttJsonSupport.renderWords(Seq(word("hello"), word("big"), word("world"))) shouldBe "hello big world"
  }

  it should "trim words and drop blank ones" in {
    val words = Seq(word("  hello "), word("   "), word("world  "), word(" \t "))

    SttJsonSupport.renderWords(words) shouldBe "hello world"
  }

  it should "return the empty string for an empty list" in {
    SttJsonSupport.renderWords(Seq.empty) shouldBe ""
  }

  it should "return the empty string when every word is blank" in {
    SttJsonSupport.renderWords(Seq(word(" "), word("  "))) shouldBe ""
  }

  "SttJsonSupport.averageConfidence" should "return None when no word has a confidence" in {
    SttJsonSupport.averageConfidence(Seq(word("a"), word("b"))) shouldBe None
    SttJsonSupport.averageConfidence(Seq.empty) shouldBe None
  }

  it should "average only the defined confidences" in {
    val words = Seq(word("a", Some(0.5)), word("b"), word("c", Some(1.0)))

    SttJsonSupport.averageConfidence(words) shouldBe Some(0.75)
  }

  it should "return a single confidence unchanged" in {
    SttJsonSupport.averageConfidence(Seq(word("a"), word("b", Some(0.25)))) shouldBe Some(0.25)
  }
}
