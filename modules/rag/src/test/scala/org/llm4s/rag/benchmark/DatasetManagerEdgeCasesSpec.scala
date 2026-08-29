package org.llm4s.rag.benchmark

import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.nio.file.Files

class DatasetManagerEdgeCasesSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var tempDir: File           = _
  private val manager: DatasetManager = DatasetManager()

  override def beforeEach(): Unit = {
    super.beforeEach()
    tempDir = Files.createTempDirectory("dataset-edge-test").toFile
  }

  override def afterEach(): Unit = {
    deleteRecursively(tempDir)
    super.afterEach()
  }

  private def deleteRecursively(f: File): Unit = {
    if (f.isDirectory) Option(f.listFiles()).foreach(_.foreach(deleteRecursively))
    f.delete()
    ()
  }

  private def writeFile(name: String, content: String): String = {
    val path = new File(tempDir, name)
    Option(path.getParentFile).foreach(_.mkdirs())
    Files.write(path.toPath, content.getBytes)
    path.getAbsolutePath
  }

  // =========================================================================
  // Format detection edge cases
  // =========================================================================

  "DatasetManager.load" should "detect TestDataset format from full JSON content" in {
    val json =
      """{
        |  "name": "my-ds",
        |  "samples": [
        |    {"question": "Q", "answer": "A", "contexts": ["C"]}
        |  ]
        |}""".stripMargin
    val path = writeFile("plain.json", json)
    val ds   = manager.load(path).toOption.get
    ds.name shouldBe "my-ds"
  }

  it should "return Left for unknown format" in {
    val path   = writeFile("unknown.json", """{"random": "data"}""")
    val result = manager.load(path)
    result.isLeft shouldBe true
  }

  it should "return Left for top-level array without data wrapper or multihop filename" in {
    // Top-level arrays without "data" key or multihop filename are treated as unknown format
    val json   = """[{"question": "Q?", "answer": "A."}]"""
    val path   = writeFile("array.json", json)
    val result = manager.load(path)
    result.isLeft shouldBe true
  }

  it should "not apply subset when subsetSize equals dataset size" in {
    val lines = (1 to 5)
      .map(i => s"""{"question":"Q$i","response":"A$i","documents":["ctx$i"]}""")
      .mkString("\n")
    val path = writeFile("exact.jsonl", lines)
    val ds   = manager.load(path, subsetSize = Some(5)).toOption.get
    ds.samples should have size 5
  }

  it should "not apply subset when subsetSize is None" in {
    val lines = (1 to 3)
      .map(i => s"""{"question":"Q$i","response":"A$i","documents":["ctx$i"]}""")
      .mkString("\n")
    val path = writeFile("full.jsonl", lines)
    val ds   = manager.load(path, subsetSize = None).toOption.get
    ds.samples should have size 3
  }

  // =========================================================================
  // RAGBench parsing edge cases
  // =========================================================================

  "DatasetManager.loadRAGBench" should "handle empty lines" in {
    val lines = Seq(
      """{"question":"Q1","response":"A1","documents":["ctx1"]}""",
      "",
      """{"question":"Q2","response":"A2","documents":["ctx2"]}"""
    ).mkString("\n")
    val path = writeFile("empty-lines.jsonl", lines)
    val ds   = manager.loadRAGBench(path).toOption.get
    ds.samples should have size 2
  }

  it should "handle lines with empty question gracefully" in {
    val line = """{"question":"","response":"A","documents":["ctx"]}"""
    val path = writeFile("empty-q.jsonl", line)
    val ds   = manager.loadRAGBench(path).toOption.get
    ds.samples shouldBe empty
  }

  it should "handle lines with empty documents array" in {
    val line = """{"question":"Q?","response":"A","documents":[]}"""
    val path = writeFile("empty-docs.jsonl", line)
    val ds   = manager.loadRAGBench(path).toOption.get
    ds.samples shouldBe empty
  }

  it should "skip lines with missing response field (empty answer fails EvalSample validation)" in {
    val lines = Seq(
      """{"question":"Q?","documents":["ctx"]}""",
      """{"question":"Q2?","response":"Valid answer","documents":["ctx2"]}"""
    ).mkString("\n")
    val path = writeFile("no-response.jsonl", lines)
    val ds   = manager.loadRAGBench(path).toOption.get
    ds.samples should have size 1
    ds.samples.head.question shouldBe "Q2?"
  }

  it should "prefer 'answer' over 'ground_truth' for groundTruth" in {
    val line = """{"question":"Q?","response":"A","documents":["c"],"answer":"GT1","ground_truth":"GT2"}"""
    val path = writeFile("both-gt.jsonl", line)
    val ds   = manager.loadRAGBench(path).toOption.get
    ds.samples.head.groundTruth shouldBe Some("GT1")
  }

  it should "set correct sourceIndex metadata for multiple lines" in {
    val lines = (0 to 2)
      .map(i => s"""{"question":"Q$i","response":"A$i","documents":["c$i"]}""")
      .mkString("\n")
    val path = writeFile("indexed.jsonl", lines)
    val ds   = manager.loadRAGBench(path).toOption.get
    ds.samples.map(_.metadata("sourceIndex")) shouldBe Seq("0", "1", "2")
  }

  // =========================================================================
  // MultiHopRAG parsing edge cases
  // =========================================================================

  "DatasetManager.loadMultiHopRAG" should "handle data wrapper with supporting facts" in {
    val json = """{"data":[{"question":"Q?","answer":"A.","supporting_facts":["f1","f2"]}]}"""
    val path = writeFile("mh-facts.json", json)
    val ds   = manager.loadMultiHopRAG(path).toOption.get
    ds.samples should have size 1
    ds.samples.head.question shouldBe "Q?"
    ds.samples.head.contexts shouldBe Seq("f1", "f2")
  }

  it should "handle empty data array" in {
    val json = """{"data":[]}"""
    val path = writeFile("empty-data.json", json)
    val ds   = manager.loadMultiHopRAG(path).toOption.get
    ds.samples shouldBe empty
  }

  it should "handle items without supporting_facts or context" in {
    val json = """{"data":[{"question":"Q?","answer":"A."}]}"""
    val path = writeFile("no-context.json", json)
    val ds   = manager.loadMultiHopRAG(path).toOption.get
    ds.samples should have size 1
    ds.samples.head.contexts shouldBe empty
  }

  it should "set sourceIndex metadata" in {
    val json =
      """{"data":[
        |  {"question":"Q0","answer":"A0"},
        |  {"question":"Q1","answer":"A1"}
        |]}""".stripMargin
    val path = writeFile("indexed-mh.json", json)
    val ds   = manager.loadMultiHopRAG(path).toOption.get
    ds.samples.map(_.metadata("sourceIndex")) shouldBe Seq("0", "1")
  }

  // =========================================================================
  // loadDocumentsFromDirectory edge cases
  // =========================================================================

  "DatasetManager.loadDocumentsFromDirectory" should "handle empty directory" in {
    val docs = manager.loadDocumentsFromDirectory(tempDir.getAbsolutePath).toOption.get
    docs shouldBe empty
  }

  it should "handle subdirectories" in {
    val subDir = new File(tempDir, "sub")
    subDir.mkdirs()
    Files.write(new File(subDir, "nested.txt").toPath, "nested content".getBytes)
    Files.write(new File(tempDir, "top.txt").toPath, "top content".getBytes)

    val docs = manager.loadDocumentsFromDirectory(tempDir.getAbsolutePath).toOption.get
    docs should have size 2
  }

  it should "match extensions case-insensitively" in {
    Files.write(new File(tempDir, "upper.TXT").toPath, "upper".getBytes)
    Files.write(new File(tempDir, "lower.txt").toPath, "lower".getBytes)

    val docs = manager.loadDocumentsFromDirectory(tempDir.getAbsolutePath).toOption.get
    docs should have size 2
  }

  it should "use relative paths for filenames" in {
    Files.write(new File(tempDir, "doc.txt").toPath, "content".getBytes)
    val docs = manager.loadDocumentsFromDirectory(tempDir.getAbsolutePath).toOption.get
    docs.head._1 shouldBe "doc.txt"
  }
}
