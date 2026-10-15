package org.llm4s.agent.harness

import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Stage 3 slice F5 (#1909, design §4.35): the restricted `SKILL.md` frontmatter parser. */
class SkillFrontmatterSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val path = VPath("/skills/pdf-tools/SKILL.md").value

  private def parse(lines: String*) = Skill.parse(path, lines.mkString("\n"))

  private def problem(lines: String*): SkillFormatError =
    parse(lines*).left.value match {
      case e: SkillFormatError => e
      case other               => fail(s"not a format error: $other")
    }

  "Skill.parse" should "read the standard's fields: scalars, quoted strings, a flat list and a one-level metadata map" in {
    val skill = parse(
      "---",
      "# a comment",
      "name: pdf-tools",
      "description: \"Extract text: tables and \\\"forms\\\" from PDFs\" # trailing",
      "license: 'Apache-2.0 (it''s fine)'",
      "",
      "allowed-tools:",
      "  - Read",
      "  - \"Bash(git:*)\"",
      "metadata:",
      "  author: example-org",
      "  version: \"1.0\"",
      "compatibility: anything # ignored",
      "---",
      "# Body",
      "not: [parsed"
    ).value
    skill.name shouldBe "pdf-tools"
    skill.description shouldBe "Extract text: tables and \"forms\" from PDFs"
    skill.license shouldBe Some("Apache-2.0 (it's fine)")
    skill.allowedTools shouldBe Vector("Read", "Bash(git:*)")
    skill.metadata.toVector shouldBe Vector("author" -> "example-org", "version" -> "1.0")
    skill.path shouldBe path
  }

  it should "take allowed-tools as the standard's space-separated string, or a flow list" in {
    parse(
      "---",
      "name: pdf-tools",
      "description: d",
      "allowed-tools: Bash(git:*)  Read",
      "---"
    ).value.allowedTools shouldBe
      Vector("Bash(git:*)", "Read")
    parse(
      "---",
      "name: pdf-tools",
      "description: d",
      "allowed-tools: [Read, 'Write', \"Glob\"]",
      "---"
    ).value.allowedTools shouldBe
      Vector("Read", "Write", "Glob")
    parse("---", "name: pdf-tools", "description: d", "allowed-tools: []", "---").value.allowedTools shouldBe empty
  }

  it should "drop a BOM and a trailing \\r, and read a frontmatter with no body" in {
    Skill.parse(path, "\uFEFF---\r\nname: pdf-tools\r\ndescription: d\r\n---\r\n").value.description shouldBe "d"
  }

  it should "refuse what the restricted format does not take, naming the line" in {
    val cases = Vector(
      Seq("---", "name: pdf-tools", "description: |", "  multi", "---")                       -> (3, "block scalars"),
      Seq("---", "name: pdf-tools", "description: >-", "---")                                 -> (3, "block scalars"),
      Seq("---", "name: pdf-tools", "description: d", "metadata:", "  a:", "    b: c", "---") -> (5, "nested maps"),
      Seq(
        "---",
        "name: pdf-tools",
        "description: d",
        "tags:",
        "  a: b",
        "---"
      ) -> (5, "only `metadata` may hold a map"),
      Seq("---", "name: pdf-tools", "description: d", "tags:", "  - - x", "---")  -> (5, "nested lists"),
      Seq("---", "name: pdf-tools", "description: d", "tags:", "  - a: b", "---") -> (5, "nested lists"),
      Seq("---", "name: pdf-tools", "description: d", "tags: [a, [b]]", "---")    -> (4, "nested lists"),
      Seq("---", "name: pdf-tools", "description: {a: b}", "---")                 -> (3, "flow maps"),
      Seq("---", "name: pdf-tools", "description: &x d", "---")                   -> (3, "anchors"),
      Seq("---", "name: pdf-tools", "description: *x", "---")                     -> (3, "aliases"),
      Seq("---", "name: pdf-tools", "description: !!str d", "---")                -> (3, "tags"),
      Seq("---", "name: pdf-tools", "\tdescription: d", "---")                    -> (3, "tabs"),
      Seq("---", "name: pdf-tools", "name: other", "description: d", "---")       -> (3, "more than once"),
      Seq("---", "name: pdf-tools", "description: d", "  continued", "---")       -> (4, "multi-line"),
      Seq("---", "name: pdf-tools", "description: \"open", "---")                 -> (3, "not closed"),
      Seq("---", "name: pdf-tools", "description: \"a\" b", "---")                -> (3, "after a closing quote"),
      Seq("---", "name: pdf-tools", "just text", "---")                           -> (3, "expected `key: value`"),
      Seq("---", "name: pdf-tools", "a key: v", "---")                            -> (3, "a key must be"),
      Seq("---", "name: pdf-tools", "description:d", "---")                       -> (3, "a space after"),
      Seq("---", "name: pdf-tools", "description: d", "- x", "---")               -> (4, "must follow a key"),
      Seq("name: pdf-tools", "description: d")                                    -> (1, "must start with"),
      Seq("---", "name: pdf-tools", "description: d")                             -> (1, "not closed"),
      Seq("---", "description: d", "---")                                         -> (1, "`name` is required"),
      Seq("---", "name: pdf-tools", "---")                                        -> (1, "`description` is required"),
      Seq("---", "name: pdf-tools", "description: \"  \"", "---")                 -> (3, "`description` is empty"),
      Seq("---", "name: [a]", "description: d", "---")                            -> (2, "`name` must be a string"),
      Seq("---", "name: other-name", "description: d", "---") -> (2, "equal the name of the skill's folder"),
      Seq("---", "name: PDF_Tools", "description: d", "---")  -> (2, "lowercase"),
      Seq("---", "name: pdf--tools", "description: d", "---") -> (2, "lowercase"),
      Seq("---", "name: pdf-tools", "description: d", "metadata: [a]", "---") -> (4, "`metadata` must be a map"),
      Seq("---", "name: pdf-tools", "description: d", "license: [a]", "---")  -> (4, "`license` must be a string")
    )
    cases.foreach { case (lines, (line, text)) =>
      withClue(lines.mkString("\\n")) {
        val e = problem(lines*)
        e.line shouldBe line
        e.problem should include(text)
        e.path shouldBe path
        e.message should startWith(s"${path.value}:$line: ")
      }
    }
  }

  it should "never quote the file in a problem" in {
    val e = problem("---", "name: pdf-tools", "description: |", "  SECRET", "---")
    (e.message should not).include("SECRET")
    val e2 = problem("---", "name: pdf-tools", "SECRET-key here", "---")
    (e2.message should not).include("SECRET")
  }

  it should "refuse a description over 1024 characters and a compatibility over 500, as the standard bounds them" in {
    parse("---", "name: pdf-tools", s"description: ${"d" * 1024}", "---").value.description.length shouldBe 1024
    problem("---", "name: pdf-tools", s"description: ${"d" * 1025}", "---").problem should include("longer than 1024")
    problem("---", "name: pdf-tools", "description: d", s"compatibility: ${"c" * 501}", "---").line shouldBe 4
    problem("---", "name: pdf-tools", "description: d", "compatibility:", "---").problem should include(
      "`compatibility` is empty"
    )
    problem("---", "name: pdf-tools", "description: d", "compatibility: \"\"", "---").line shouldBe 4
    problem("---", "name: pdf-tools", "description: d", "compatibility: [a]", "---").problem should include(
      "must be a string"
    )
  }

  it should "refuse an escape spelling a NUL or an unpaired surrogate, and take a valid pair" in {
    problem("---", "name: pdf-tools", "description: \"a\\uD800b\"", "---").problem should include("unpaired surrogate")
    problem("---", "name: pdf-tools", "description: \"\\uDC00\"", "---").line shouldBe 3
    problem("---", "name: pdf-tools", "description: \"a\\0\"", "---").problem should include("NUL")
    parse("---", "name: pdf-tools", "description: \"\\uD83D\\uDE00\"", "---").value.description shouldBe "\uD83D\uDE00"
  }

  it should "refuse a plain value YAML would read as a map, and a metadata, licence or allowed-tools without a value" in {
    problem("---", "name: pdf-tools", "description: summarize: quickly", "---").problem should include("quote it")
    problem("---", "name: pdf-tools", "description: note:", "---").line shouldBe 3
    problem("---", "name: pdf-tools", "description: d", "metadata:", "  a: b: c", "---").line shouldBe 5
    parse("---", "name: pdf-tools", "description: \"summarize: quickly\"", "---").value.description shouldBe
      "summarize: quickly"
    for (bad <- Seq("metadata:", "metadata: []", "metadata: \"\"", "license:", "license: ''", "allowed-tools:"))
      withClue(bad)(problem("---", "name: pdf-tools", "description: d", bad, "---").line shouldBe 4)
  }

  it should "hold every plain value, in a list or not, to one rule, and a block list to one indentation" in {
    problem("---", "name: pdf-tools", "description: d", "allowed-tools: [Read:]", "---").line shouldBe 4
    problem("---", "name: pdf-tools", "description: d", "allowed-tools: [a: b]", "---").line shouldBe 4
    problem(
      "---",
      "name: pdf-tools",
      "description: d",
      "allowed-tools:",
      "  - Read",
      "    - Write",
      "---"
    ).line shouldBe 6
    problem("---", "name: pdf-tools", "description: d", "allowed-tools:", "  - Read", "- Write", "---").line shouldBe 6
    problem("---", "name: pdf-tools", "description: - d", "---").line shouldBe 3
    problem("---", "name: pdf-tools", "description: ? d", "---").line shouldBe 3
    problem("---", "name: pdf-tools", "description: ], d", "---").problem should include("cannot start with")
    problem("---", "name: pdf-tools", "description: d", "metadata:", "  a: &x b", "---").line shouldBe 5
    parse(
      "---",
      "name: pdf-tools",
      "description: d",
      "allowed-tools:",
      "- Read",
      "- Write",
      "---"
    ).value.allowedTools shouldBe
      Vector("Read", "Write")
  }

  it should "refuse a plain value YAML reads as a null, boolean or number, and take it quoted" in {
    for (v <- Seq("true", "False", "null", "~", "1", "-2", "1.0", "1e3", ".5", "0x1F", "0o17", ".inf", ".NaN"))
      withClue(v)(
        problem("---", "name: pdf-tools", "description: d", "metadata:", s"  version: $v", "---").line shouldBe 5
      )
    problem("---", "name: pdf-tools", "description: true", "---").problem should include("quote it")
    problem("---", "name: pdf-tools", "description: d", "allowed-tools: [Read, 1]", "---").line shouldBe 4
    parse("---", "name: pdf-tools", "description: d", "metadata:", "  version: \"1.0\"", "---").value.metadata shouldBe
      Map("version" -> "1.0")
    parse("---", "name: pdf-tools", "description: v1.0 trueish", "---").value.description shouldBe "v1.0 trueish"
  }

  it should "check a quoted value with the spaces it quotes, and refuse a list item that is only a comment" in {
    problem("---", "name: \" pdf-tools \"", "description: d", "---").line shouldBe 2
    problem("---", "name: pdf-tools", s"description: \" ${"d" * 1024}\"", "---").problem should include(
      "longer than 1024"
    )
    parse("---", "name: pdf-tools", "description: \" spaced \"", "---").value.description shouldBe " spaced "
    problem("---", "name: pdf-tools", "description: d", "allowed-tools:", "  - # disabled", "---").line shouldBe 5
    problem("---", "name: pdf-tools", "description: d", "allowed-tools: [Read, #x]", "---").line shouldBe 4
  }

  it should "refuse a key YAML reads as a non-string, and a comment inside a flow list" in {
    problem("---", "name: pdf-tools", "description: d", "metadata:", "  1: one", "---").line shouldBe 5
    problem("---", "name: pdf-tools", "description: d", "metadata:", "  true: yes-ish", "---").line shouldBe 5
    problem("---", "name: pdf-tools", "description: d", "null: x", "---").line shouldBe 4
    problem("---", "name: pdf-tools", "description: d", "allowed-tools: [Read # disabled]", "---").line shouldBe 4
    parse(
      "---",
      "name: pdf-tools",
      "description: d",
      "allowed-tools: [Read, Write] # both",
      "---"
    ).value.allowedTools shouldBe
      Vector("Read", "Write")
  }

  it should "end a plain value at a comment after a tab as after a space" in {
    parse("---", "name: pdf-tools", "description: PDFs.\t# internal note", "---").value.description shouldBe "PDFs."
    parse("---", "name: pdf-tools", "description: a#b", "---").value.description shouldBe "a#b"
    problem("---", "name: pdf-tools", "description: d", "allowed-tools: [Read\t# off]", "---").line shouldBe 4
  }

  it should "refuse a character YAML does not allow, raw or escaped, and take tabs, line breaks and Unicode" in {
    problem("---", "name: pdf-tools", "description: \"a\u0001b\"", "---").line shouldBe 3
    problem("---", "name: pdf-tools", "description: a\u007fb", "---").line shouldBe 3
    problem("---", "name: pdf-tools", "description: d", "metadata:", "  k: \"\\u0002\"", "---").line shouldBe 4
    parse("---", "name: pdf-tools", "description: \"a\\tb\\nc \u00e9 \uD83D\uDE00\"", "---").value.description shouldBe
      "a\tb\nc \u00e9 \uD83D\uDE00"
  }

  it should "refuse a tab inside a plain value, which could separate an indicator, and take it quoted" in {
    for (v <- Seq("summarize:\tquickly", "-\tx", "?\tx", "a\tb"))
      withClue(v)(problem("---", "name: pdf-tools", s"description: $v", "---").line shouldBe 3)
    problem("---", "name: pdf-tools", "description: d", "allowed-tools: [a:\tb]", "---").line shouldBe 4
    parse("---", "name: pdf-tools", "description: \"a:\tb\"", "---").value.description shouldBe "a:\tb"
  }

  it should "take a comment after a closing quote or bracket only after whitespace" in {
    problem("---", "name: pdf-tools", "description: \"PDFs\"#internal", "---").line shouldBe 3
    problem("---", "name: pdf-tools", "description: d", "allowed-tools: [Read]#internal", "---").line shouldBe 4
    parse("---", "name: pdf-tools", "description: \"PDFs\" # internal", "---").value.description shouldBe "PDFs"
    parse(
      "---",
      "name: pdf-tools",
      "description: d",
      "allowed-tools: [Read]\t# internal",
      "---"
    ).value.allowedTools shouldBe
      Vector("Read")
  }

  it should "refuse a name over 64 characters" in {
    val long = "a" * 65
    val e    = Skill.parse(VPath(s"/s/$long/SKILL.md").value, s"---\nname: $long\ndescription: d\n---").left.value
    e.message should include("longer than 64")
  }
}
