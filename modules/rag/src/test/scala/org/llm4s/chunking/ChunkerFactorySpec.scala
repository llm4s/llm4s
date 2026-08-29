package org.llm4s.chunking

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Unit tests for ChunkerFactory and related strategy types.
 *
 * Tests the factory for chunker creation including:
 * - Strategy creation and enumeration
 * - Strategy string parsing
 * - Factory method creation for each strategy
 * - Strategy names and properties
 * - Edge cases and invalid inputs
 */
class ChunkerFactorySpec extends AnyFlatSpec with Matchers {

  "ChunkerFactory.Strategy" should "have all expected strategies" in {
    val strategies = ChunkerFactory.Strategy.all

    strategies should contain(ChunkerFactory.Strategy.Simple)
    strategies should contain(ChunkerFactory.Strategy.Sentence)
    strategies should contain(ChunkerFactory.Strategy.Semantic)
    strategies should contain(ChunkerFactory.Strategy.Markdown)
    strategies should have size 4
  }

  it should "have proper names" in {
    ChunkerFactory.Strategy.Simple.name shouldBe "simple"
    ChunkerFactory.Strategy.Sentence.name shouldBe "sentence"
    ChunkerFactory.Strategy.Semantic.name shouldBe "semantic"
    ChunkerFactory.Strategy.Markdown.name shouldBe "markdown"
  }

  it should "parse string to strategy correctly - simple" in {
    ChunkerFactory.Strategy.fromString("simple") shouldBe Some(ChunkerFactory.Strategy.Simple)
    ChunkerFactory.Strategy.fromString("Simple") shouldBe Some(ChunkerFactory.Strategy.Simple)
    ChunkerFactory.Strategy.fromString("SIMPLE") shouldBe Some(ChunkerFactory.Strategy.Simple)
  }

  it should "parse string to strategy correctly - sentence" in {
    ChunkerFactory.Strategy.fromString("sentence") shouldBe Some(ChunkerFactory.Strategy.Sentence)
    ChunkerFactory.Strategy.fromString("Sentence") shouldBe Some(ChunkerFactory.Strategy.Sentence)
  }

  it should "parse string to strategy correctly - semantic" in {
    ChunkerFactory.Strategy.fromString("semantic") shouldBe Some(ChunkerFactory.Strategy.Semantic)
    ChunkerFactory.Strategy.fromString("Semantic") shouldBe Some(ChunkerFactory.Strategy.Semantic)
  }

  it should "parse string to strategy correctly - markdown" in {
    ChunkerFactory.Strategy.fromString("markdown") shouldBe Some(ChunkerFactory.Strategy.Markdown)
    ChunkerFactory.Strategy.fromString("Markdown") shouldBe Some(ChunkerFactory.Strategy.Markdown)
  }

  it should "return None for invalid strategy string" in {
    ChunkerFactory.Strategy.fromString("invalid") shouldBe None
    ChunkerFactory.Strategy.fromString("unknown") shouldBe None
    ChunkerFactory.Strategy.fromString("") shouldBe None
    ChunkerFactory.Strategy.fromString("   ") shouldBe None
  }

  it should "handle whitespace in strategy string parsing" in {
    ChunkerFactory.Strategy.fromString("  simple  ") shouldBe Some(ChunkerFactory.Strategy.Simple)
    ChunkerFactory.Strategy.fromString("\tsentence\n") shouldBe Some(ChunkerFactory.Strategy.Sentence)
  }

  "ChunkerFactory" should "create simple chunker" in {
    val chunker = ChunkerFactory.simple()

    chunker shouldBe a[SimpleChunker]
    chunker shouldBe a[DocumentChunker]
  }

  it should "create sentence chunker" in {
    val chunker = ChunkerFactory.sentence()

    chunker shouldBe a[SentenceChunker]
    chunker shouldBe a[DocumentChunker]
  }

  it should "create markdown chunker" in {
    val chunker = ChunkerFactory.markdown()

    chunker shouldBe a[MarkdownChunker]
    chunker shouldBe a[DocumentChunker]
  }

  it should "all created chunkers implement DocumentChunker interface" in {
    val chunkers = Seq(
      ChunkerFactory.simple(),
      ChunkerFactory.sentence(),
      ChunkerFactory.markdown()
    )

    chunkers.foreach(chunker => chunker shouldBe a[DocumentChunker])
  }

  it should "create independent instances" in {
    val chunker1 = ChunkerFactory.simple()
    val chunker2 = ChunkerFactory.simple()

    // Different instances (though may have same type)
    chunker1 should not be theSameInstanceAs(chunker2)
  }

  it should "simple chunker handle basic text" in {
    val chunker = ChunkerFactory.simple()
    val config  = ChunkingConfig(targetSize = 100, overlap = 0)

    val chunks = chunker.chunk("Hello world", config)

    chunks should not be empty
  }

  it should "sentence chunker handle basic text" in {
    val chunker = ChunkerFactory.sentence()
    val config  = ChunkingConfig(targetSize = 100, overlap = 0)

    val chunks = chunker.chunk("Hello world. This is a sentence.", config)

    chunks should not be empty
  }

  it should "markdown chunker handle basic text" in {
    val chunker = ChunkerFactory.markdown()
    val config  = ChunkingConfig(targetSize = 100, overlap = 0)

    val chunks = chunker.chunk("# Header\n\nParagraph text", config)

    chunks should not be empty
  }

  "ChunkerFactory.Strategy all variants" should "be enumerable" in {
    val all = ChunkerFactory.Strategy.all
    all should have size 4

    (all.map(_.name) should contain).allOf("simple", "sentence", "semantic", "markdown")
  }

  it should "roundtrip through string conversion" in {
    ChunkerFactory.Strategy.all.foreach { strategy =>
      ChunkerFactory.Strategy.fromString(strategy.name) shouldBe Some(strategy)
    }
  }

  "ChunkerFactory.create(String)" should "create simple chunker from string" in {
    val result = ChunkerFactory.create("simple")

    result shouldBe defined
    result.get shouldBe a[SimpleChunker]
    result.get shouldBe a[DocumentChunker]
  }

  it should "create sentence chunker from string" in {
    val result = ChunkerFactory.create("sentence")

    result shouldBe defined
    result.get shouldBe a[SentenceChunker]
    result.get shouldBe a[DocumentChunker]
  }

  it should "create markdown chunker from string" in {
    val result = ChunkerFactory.create("markdown")

    result shouldBe defined
    result.get shouldBe a[MarkdownChunker]
    result.get shouldBe a[DocumentChunker]
  }

  it should "return None for unknown strategy string" in {
    ChunkerFactory.create("unknown") shouldBe None
  }

  it should "return None for invalid strategy string" in {
    ChunkerFactory.create("invalid") shouldBe None
    ChunkerFactory.create("") shouldBe None
    ChunkerFactory.create("   ") shouldBe None
  }

  it should "be case-insensitive for strategy string" in {
    ChunkerFactory.create("SIMPLE") shouldBe defined
    ChunkerFactory.create("Simple") shouldBe defined
    ChunkerFactory.create("SiMpLe") shouldBe defined
  }

  it should "handle strategy string with whitespace" in {
    ChunkerFactory.create("  simple  ") shouldBe defined
    ChunkerFactory.create("\tsentence\n") shouldBe defined
  }

  it should "semantic strategy returns sentence chunker as fallback from string" in {
    // Semantic chunking requires embeddings which aren't available via the factory create method.
    // Instead of failing, we gracefully fall back to SentenceChunker for predictable behavior.
    // This allows applications to request "semantic" strategy without requiring embedding setup
    // at construction time, while still getting a usable chunker result.
    val result = ChunkerFactory.create("semantic")

    result shouldBe defined
    result.get shouldBe a[SentenceChunker]
  }

  "ChunkerFactory.create(Strategy)" should "create simple chunker from Strategy.Simple" in {
    val chunker = ChunkerFactory.create(ChunkerFactory.Strategy.Simple)

    chunker shouldBe a[SimpleChunker]
    chunker shouldBe a[DocumentChunker]
  }

  it should "create sentence chunker from Strategy.Sentence" in {
    val chunker = ChunkerFactory.create(ChunkerFactory.Strategy.Sentence)

    chunker shouldBe a[SentenceChunker]
    chunker shouldBe a[DocumentChunker]
  }

  it should "create markdown chunker from Strategy.Markdown" in {
    val chunker = ChunkerFactory.create(ChunkerFactory.Strategy.Markdown)

    chunker shouldBe a[MarkdownChunker]
    chunker shouldBe a[DocumentChunker]
  }

  it should "return sentence chunker as fallback from Strategy.Semantic" in {
    // Strategy.Semantic is designed to always return a usable chunker even without embedding client.
    // This fallback ensures type safety and predictable behavior: create(Strategy) never fails,
    // making it suitable for code paths that must always produce a DocumentChunker.
    // For true semantic chunking, callers should use semantic(embeddingClient, modelConfig) directly.
    val chunker = ChunkerFactory.create(ChunkerFactory.Strategy.Semantic)

    chunker shouldBe a[SentenceChunker]
    chunker shouldBe a[DocumentChunker]
  }

  it should "create independent instances for each call" in {
    val chunker1 = ChunkerFactory.create(ChunkerFactory.Strategy.Simple)
    val chunker2 = ChunkerFactory.create(ChunkerFactory.Strategy.Simple)

    chunker1 should not be theSameInstanceAs(chunker2)
  }

  it should "all strategy enum variants produce DocumentChunker" in {
    ChunkerFactory.Strategy.all.foreach { strategy =>
      val chunker = ChunkerFactory.create(strategy)
      chunker shouldBe a[DocumentChunker]
    }
  }

  "ChunkerFactory.auto()" should "return MarkdownChunker for markdown with heading" in {
    val text    = "# Heading\n\nText"
    val chunker = ChunkerFactory.auto(text)

    chunker shouldBe a[MarkdownChunker]
    chunker shouldBe a[DocumentChunker]
  }

  it should "return SentenceChunker for plain text without markdown" in {
    val text    = "Plain text without markdown."
    val chunker = ChunkerFactory.auto(text)

    chunker shouldBe a[SentenceChunker]
    chunker shouldBe a[DocumentChunker]
  }

  it should "detect markdown code blocks" in {
    val text    = "Some text\n```python\nprint('hello')\n```\nMore text"
    val chunker = ChunkerFactory.auto(text)

    chunker shouldBe a[MarkdownChunker]
  }

  it should "detect markdown headings at various levels" in {
    val text1 = "# H1\nText"
    ChunkerFactory.auto(text1) shouldBe a[MarkdownChunker]

    val text2 = "## H2\nText"
    ChunkerFactory.auto(text2) shouldBe a[MarkdownChunker]

    val text3 = "### H3\nText"
    ChunkerFactory.auto(text3) shouldBe a[MarkdownChunker]

    val text6 = "###### H6\nText"
    ChunkerFactory.auto(text6) shouldBe a[MarkdownChunker]
  }

  it should "detect markdown bullet lists" in {
    val text    = "Some text\n- Item 1\n- Item 2\nMore text"
    val chunker = ChunkerFactory.auto(text)

    chunker shouldBe a[MarkdownChunker]
  }

  it should "detect markdown with asterisk list markers" in {
    val text    = "Some text\n* Item 1\n* Item 2"
    val chunker = ChunkerFactory.auto(text)

    chunker shouldBe a[MarkdownChunker]
  }

  it should "detect markdown with plus list markers" in {
    val text    = "Some text\n+ Item 1\n+ Item 2"
    val chunker = ChunkerFactory.auto(text)

    chunker shouldBe a[MarkdownChunker]
  }

  it should "detect markdown with numbered lists" in {
    val text    = "Some text\n1. First\n2. Second\nMore text"
    val chunker = ChunkerFactory.auto(text)

    chunker shouldBe a[MarkdownChunker]
  }

  it should "detect markdown with links" in {
    val text    = "Check out [this link](https://example.com) for more info"
    val chunker = ChunkerFactory.auto(text)

    chunker shouldBe a[MarkdownChunker]
  }

  it should "detect markdown with images" in {
    val text    = "Here is an image: ![alt text](image.png)"
    val chunker = ChunkerFactory.auto(text)

    chunker shouldBe a[MarkdownChunker]
  }

  it should "return SentenceChunker for empty string" in {
    val text    = ""
    val chunker = ChunkerFactory.auto(text)

    chunker shouldBe a[SentenceChunker]
  }

  it should "return SentenceChunker for whitespace-only string" in {
    val text    = "   \n\t   "
    val chunker = ChunkerFactory.auto(text)

    chunker shouldBe a[SentenceChunker]
  }

  it should "return SentenceChunker for simple sentences" in {
    val text    = "This is the first sentence. This is the second sentence. And a third."
    val chunker = ChunkerFactory.auto(text)

    chunker shouldBe a[SentenceChunker]
  }

  it should "return SentenceChunker for text with URLs but no markdown syntax" in {
    val text    = "Visit https://example.com for more information"
    val chunker = ChunkerFactory.auto(text)

    chunker shouldBe a[SentenceChunker]
  }

  it should "return SentenceChunker for text with hash symbol but not as heading" in {
    val text    = "Use #hashtag in social media. # is also used in programming."
    val chunker = ChunkerFactory.auto(text)

    chunker shouldBe a[SentenceChunker]
  }

  it should "handle complex markdown document" in {
    val text =
      """# Main Title
        |
        |## Section 1
        |
        |This is a paragraph with some [link](https://example.com).
        |
        |```python
        |def hello():
        |    print("world")
        |```
        |
        |- Point 1
        |- Point 2
        |
        |## Section 2
        |
        |Final paragraph.
        |""".stripMargin

    val chunker = ChunkerFactory.auto(text)

    chunker shouldBe a[MarkdownChunker]
  }

  it should "consistently return same chunker type for same input" in {
    val text     = "# Heading\n\nMarkdown text"
    val chunker1 = ChunkerFactory.auto(text)
    val chunker2 = ChunkerFactory.auto(text)

    chunker1.getClass shouldBe chunker2.getClass
  }

  it should "return independent instances for each call" in {
    val text     = "# Heading\n\nText"
    val chunker1 = ChunkerFactory.auto(text)
    val chunker2 = ChunkerFactory.auto(text)

    chunker1 should not be theSameInstanceAs(chunker2)
  }
}
