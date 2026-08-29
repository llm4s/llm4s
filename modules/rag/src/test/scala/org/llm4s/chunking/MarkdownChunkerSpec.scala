package org.llm4s.chunking

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MarkdownChunkerSpec extends AnyFlatSpec with Matchers {

  val chunker: DocumentChunker = MarkdownChunker()

  "MarkdownChunker" should "handle empty text" in {
    val chunks = chunker.chunk("")

    chunks shouldBe empty
  }

  it should "return single chunk for short text" in {
    val text   = "# Heading\n\nShort paragraph."
    val chunks = chunker.chunk(text, ChunkingConfig(targetSize = 500, overlap = 0))

    chunks should have size 1
    chunks.head.content should include("# Heading")
    chunks.head.content should include("Short paragraph")
  }

  it should "detect heading levels" in {
    val text = """# Level 1
                 |
                 |Content under level 1.
                 |
                 |## Level 2
                 |
                 |Content under level 2.
                 |
                 |### Level 3
                 |
                 |Content under level 3.""".stripMargin

    val chunks = chunker.chunk(text, ChunkingConfig(targetSize = 100, maxSize = 200, overlap = 0))

    chunks.nonEmpty shouldBe true
    // Should track heading hierarchy
    val allContent = chunks.map(_.content).mkString("\n")
    allContent should include("# Level 1")
    allContent should include("## Level 2")
    allContent should include("### Level 3")
  }

  it should "track heading hierarchy in metadata" in {
    val text = """# Main Title
                 |
                 |Introduction text.
                 |
                 |## Section 1
                 |
                 |Section 1 content.""".stripMargin

    val chunks = chunker.chunk(text, ChunkingConfig(targetSize = 200, maxSize = 400, overlap = 0))

    chunks.nonEmpty shouldBe true
    // Later chunks should have headings in metadata
    chunks.exists(_.metadata.headings.nonEmpty) shouldBe true
  }

  it should "keep code blocks intact when possible" in {
    val text = """# Code Example
                 |
                 |Here is some code:
                 |
                 |```scala
                 |def hello(): Unit = {
                 |  println("Hello, World!")
                 |}
                 |```
                 |
                 |More text after code.""".stripMargin

    val chunks =
      chunker.chunk(text, ChunkingConfig(targetSize = 300, maxSize = 500, overlap = 0, preserveCodeBlocks = true))

    chunks.nonEmpty shouldBe true
    // Code block should be intact
    val codeChunk = chunks.find(_.content.contains("def hello()"))
    codeChunk shouldBe defined
    codeChunk.get.content should include("println")
    codeChunk.get.content should include("}")
  }

  it should "mark code blocks in metadata" in {
    val text = """```python
                 |print("Hello")
                 |```""".stripMargin

    val chunks =
      chunker.chunk(text, ChunkingConfig(targetSize = 200, maxSize = 400, overlap = 0, preserveCodeBlocks = true))

    chunks should have size 1
    chunks.head.metadata.isCodeBlock shouldBe true
    chunks.head.metadata.language shouldBe Some("python")
  }

  it should "detect code block language" in {
    val text = """```javascript
                 |console.log("test");
                 |```""".stripMargin

    val chunks =
      chunker.chunk(text, ChunkingConfig(targetSize = 200, maxSize = 400, overlap = 0, preserveCodeBlocks = true))

    chunks should have size 1
    chunks.head.metadata.language shouldBe Some("javascript")
  }

  it should "handle code blocks without language" in {
    val text = """```
                 |plain code
                 |```""".stripMargin

    val chunks =
      chunker.chunk(text, ChunkingConfig(targetSize = 200, maxSize = 400, overlap = 0, preserveCodeBlocks = true))

    chunks should have size 1
    chunks.head.metadata.isCodeBlock shouldBe true
    // Language can be None or Some("") for code blocks without language specification
    chunks.head.metadata.language should (equal(None).or(equal(Some(""))))
  }

  it should "split large code blocks when necessary" in {
    val longCode = "x = 1\n" * 100
    val text     = s"```python\n$longCode```"

    val chunks =
      chunker.chunk(text, ChunkingConfig(targetSize = 100, maxSize = 200, overlap = 0, preserveCodeBlocks = true))

    // Should split because it exceeds maxSize
    chunks.size should be > 1
  }

  it should "handle nested heading levels" in {
    val text = """# Top
                 |## Sub 1
                 |Content 1
                 |## Sub 2
                 |Content 2
                 |# New Top
                 |Content under new top.""".stripMargin

    val chunks = chunker.chunk(text, ChunkingConfig(targetSize = 50, maxSize = 100, overlap = 0))

    chunks.nonEmpty shouldBe true
    // Heading hierarchy should reset when going back to higher level
    // The last chunk should have "New Top" in its hierarchy
    val allContent = chunks.map(_.content).mkString("\n")
    allContent should include("# New Top")
  }

  it should "handle unclosed code blocks" in {
    val text = """```scala
                 |val x = 1
                 |// missing closing fence"""

    val chunks = chunker.chunk(text)

    chunks.nonEmpty shouldBe true
    // Should handle gracefully without crashing
    chunks.head.content should include("val x = 1")
  }

  it should "preserve list structure" in {
    val text = """# Shopping List
                 |
                 |- Apples
                 |- Bananas
                 |- Oranges""".stripMargin

    val chunks = chunker.chunk(text, ChunkingConfig(targetSize = 200, maxSize = 400, overlap = 0))

    chunks should have size 1
    val content = chunks.head.content
    content should include("- Apples")
    content should include("- Bananas")
    content should include("- Oranges")
  }

  it should "handle mixed content" in {
    val text = """# Title
                 |
                 |Paragraph text.
                 |
                 |```python
                 |code()
                 |```
                 |
                 |More text.
                 |
                 |- List item 1
                 |- List item 2""".stripMargin

    val chunks = chunker.chunk(text, ChunkingConfig(targetSize = 100, maxSize = 200, overlap = 0))

    chunks.nonEmpty shouldBe true
    val allContent = chunks.map(_.content).mkString("\n")
    allContent should include("# Title")
    allContent should include("code()")
    allContent should include("- List item")
  }

  it should "assign sequential indices" in {
    val text = """# Section 1
                 |Content 1
                 |
                 |# Section 2
                 |Content 2
                 |
                 |# Section 3
                 |Content 3""".stripMargin

    val chunks = chunker.chunk(text, ChunkingConfig(targetSize = 30, maxSize = 60, overlap = 0))

    chunks.zipWithIndex.foreach { case (chunk, expectedIdx) =>
      chunk.index shouldBe expectedIdx
    }
  }

  "ChunkerFactory" should "create markdown chunker" in {
    val chunker = ChunkerFactory.markdown()
    chunker shouldBe a[MarkdownChunker]
  }

  it should "create markdown chunker by name" in {
    val chunker = ChunkerFactory.create("markdown")
    chunker shouldBe defined
    chunker.get shouldBe a[MarkdownChunker]
  }

  it should "create markdown chunker by strategy enum" in {
    val chunker = ChunkerFactory.create(ChunkerFactory.Strategy.Markdown)
    chunker shouldBe a[MarkdownChunker]
  }

  it should "auto-detect markdown content and return MarkdownChunker" in {
    val markdownText = "# Heading\n\nSome text with ```code``` blocks."
    val chunker      = ChunkerFactory.auto(markdownText)

    chunker shouldBe a[MarkdownChunker]
  }
}
