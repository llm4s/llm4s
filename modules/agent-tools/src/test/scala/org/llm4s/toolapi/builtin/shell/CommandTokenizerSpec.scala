package org.llm4s.toolapi.builtin.shell

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CommandTokenizerSpec extends AnyFlatSpec with Matchers {

  "CommandTokenizer" should "split on whitespace" in {
    CommandTokenizer.tokenize("echo hello world") shouldBe Right(Seq("echo", "hello", "world"))
  }

  it should "collapse runs of whitespace" in {
    CommandTokenizer.tokenize("  ls   -la   /tmp  ") shouldBe Right(Seq("ls", "-la", "/tmp"))
  }

  it should "return an empty sequence for empty input" in {
    CommandTokenizer.tokenize("") shouldBe Right(Seq.empty)
    CommandTokenizer.tokenize("    ") shouldBe Right(Seq.empty)
  }

  it should "preserve whitespace inside double quotes" in {
    CommandTokenizer.tokenize("""echo "hello world"""") shouldBe Right(Seq("echo", "hello world"))
  }

  it should "preserve whitespace inside single quotes" in {
    CommandTokenizer.tokenize("echo 'hello world'") shouldBe Right(Seq("echo", "hello world"))
  }

  it should "treat shell metacharacters inside quotes as literals" in {
    CommandTokenizer.tokenize("echo 'a && b | c; d'") shouldBe Right(Seq("echo", "a && b | c; d"))
    CommandTokenizer.tokenize("""echo "a && b"""") shouldBe Right(Seq("echo", "a && b"))
  }

  it should "honour backslash escapes outside quotes" in {
    CommandTokenizer.tokenize("""echo hello\ world""") shouldBe Right(Seq("echo", "hello world"))
    CommandTokenizer.tokenize("""echo a\&b""") shouldBe Right(Seq("echo", "a&b"))
  }

  it should "honour backslash escapes for quote and backslash inside double quotes" in {
    CommandTokenizer.tokenize("""echo "she said \"hi\""""") shouldBe Right(Seq("echo", """she said "hi""""))
    CommandTokenizer.tokenize("""echo "back\\slash"""") shouldBe Right(Seq("echo", """back\slash"""))
  }

  it should "leave non-escape backslashes inside double quotes literal" in {
    CommandTokenizer.tokenize("""echo "a\nb"""") shouldBe Right(Seq("echo", """a\nb"""))
  }

  it should "leave backslashes inside single quotes literal" in {
    CommandTokenizer.tokenize("""echo 'a\nb'""") shouldBe Right(Seq("echo", """a\nb"""))
  }

  it should "concatenate adjacent quoted and unquoted segments into one token" in {
    CommandTokenizer.tokenize("""echo a"b c"d""") shouldBe Right(Seq("echo", "ab cd"))
  }

  it should "produce an empty token for empty quotes" in {
    CommandTokenizer.tokenize("""echo """"""") shouldBe Right(Seq("echo", ""))
    CommandTokenizer.tokenize("echo ''") shouldBe Right(Seq("echo", ""))
  }

  it should "reject unclosed double quotes" in {
    CommandTokenizer.tokenize("""echo "missing close""").left.map(_.toLowerCase) shouldBe Left(
      "unclosed double quote in command"
    )
  }

  it should "reject unclosed single quotes" in {
    CommandTokenizer.tokenize("echo 'missing close").left.map(_.toLowerCase) shouldBe Left(
      "unclosed single quote in command"
    )
  }

  it should "tokenize the bypass payload safely" in {
    CommandTokenizer.tokenize("echo hi && rm /tmp/foo") shouldBe Right(
      Seq("echo", "hi", "&&", "rm", "/tmp/foo")
    )
  }
}
