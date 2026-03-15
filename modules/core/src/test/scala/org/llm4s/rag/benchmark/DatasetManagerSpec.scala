package org.llm4s.rag.benchmark

import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.nio.file.Files

class DatasetManagerSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var tempDir: File           = _
  private val manager: DatasetManager = DatasetManager()

  override def beforeEach(): Unit = {
    super.beforeEach()
    tempDir = Files.createTempDirectory("dataset-manager-test").toFile
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
    Files.write(path.toPath, content.getBytes)
    path.getAbsolutePath
  }

  // =========================================================================
  // load — file not found
  // =========================================================================

  "DatasetManager.load" should "return Left for a non-existent file" in {
    val result = manager.load("/tmp/no-such-dataset-xyz-9999.json")
    result.isLeft shouldBe true
    result.left.toOption.get.message should include("not found")
  }

  // =========================================================================
  // load — format detection via filename
  // =========================================================================

  it should "detect RAGBench format for .jsonl files" in {
    val line = """{"question":"Q?","response":"A.","documents":["ctx"]}"""
    val path = writeFile("test.jsonl", line + "\n")
    val ds   = manager.load(path).toOption.get
    ds.metadata("format") shouldBe "ragbench"
  }

  it should "detect MultiHopRAG format for filenames containing 'multihop'" in {
    val json = """{"data":[{"question":"Q?","answer":"A."}]}"""
    val path = writeFile("multihop-eval.json", json)
    val ds   = manager.load(path).toOption.get
    ds.metadata("format") shouldBe "multihop-rag"
  }

  it should "detect MultiHopRAG format for filenames containing 'multi_hop'" in {
    val json = """{"data":[{"question":"Q?","answer":"A."}]}"""
    val path = writeFile("multi_hop_test.json", json)
    val ds   = manager.load(path).toOption.get
    ds.metadata("format") shouldBe "multihop-rag"
  }

  // =========================================================================
  // load — format detection via content
  // =========================================================================

  it should "detect TestDataset format by reading the 'samples' key in content" in {
    val json = """{"name":"my-ds","samples":[{"question":"Q?","answer":"A.","contexts":["ctx"]}]}"""
    val path = writeFile("generic.json", json)
    val ds   = manager.load(path).toOption.get
    ds.name shouldBe "my-ds"
  }

  // =========================================================================
  // load — subsetting
  // =========================================================================

  it should "return exactly subsetSize samples when requested" in {
    val lines = (1 to 10)
      .map(i => s"""{"question":"Q$i","response":"A$i","documents":["ctx$i"]}""")
      .mkString("\n")
    val path = writeFile("big.jsonl", lines)
    val ds   = manager.load(path, subsetSize = Some(4)).toOption.get
    ds.samples should have size 4
  }

  it should "return all samples when subsetSize exceeds the dataset size" in {
    val lines = (1 to 3)
      .map(i => s"""{"question":"Q$i","response":"A$i","documents":["ctx$i"]}""")
      .mkString("\n")
    val path = writeFile("small.jsonl", lines)
    val ds   = manager.load(path, subsetSize = Some(100)).toOption.get
    ds.samples should have size 3
  }

  it should "produce a deterministic subset for the same seed" in {
    val lines = (1 to 20)
      .map(i => s"""{"question":"Q$i","response":"A$i","documents":["ctx$i"]}""")
      .mkString("\n")
    val path = writeFile("seed.jsonl", lines)
    val ds1  = manager.load(path, subsetSize = Some(5), seed = 123L).toOption.get
    val ds2  = manager.load(path, subsetSize = Some(5), seed = 123L).toOption.get
    ds1.samples.map(_.question) shouldBe ds2.samples.map(_.question)
  }

  // =========================================================================
  // loadRAGBench
  // =========================================================================

  "DatasetManager.loadRAGBench" should "parse well-formed lines correctly" in {
    val line = """{"question":"What is X?","response":"X is Y.","documents":["ctx1","ctx2"],"answer":"GT answer"}"""
    val path = writeFile("ragbench.jsonl", line)
    val ds   = manager.loadRAGBench(path).toOption.get
    ds.samples should have size 1
    val s = ds.samples.head
    s.question shouldBe "What is X?"
    s.answer shouldBe "X is Y."
    s.contexts shouldBe Seq("ctx1", "ctx2")
    s.groundTruth shouldBe Some("GT answer")
  }

  it should "accept 'ground_truth' as an alias for 'answer'" in {
    val line = """{"question":"Q?","response":"A.","documents":["ctx"],"ground_truth":"GT"}"""
    val path = writeFile("ragbench-gt.jsonl", line)
    val s    = manager.loadRAGBench(path).toOption.get.samples.head
    s.groundTruth shouldBe Some("GT")
  }

  it should "skip lines that have no question" in {
    val lines = Seq(
      """{"response":"A.","documents":["ctx"]}""",
      """{"question":"Valid?","response":"A.","documents":["ctx"]}"""
    ).mkString("\n")
    val path = writeFile("missing-q.jsonl", lines)
    manager.loadRAGBench(path).toOption.get.samples should have size 1
  }

  it should "skip lines that have no documents" in {
    val lines = Seq(
      """{"question":"Q?","response":"A."}""",
      """{"question":"Q2?","response":"A2.","documents":["ctx"]}"""
    ).mkString("\n")
    val path = writeFile("missing-docs.jsonl", lines)
    manager.loadRAGBench(path).toOption.get.samples should have size 1
  }

  it should "skip malformed JSON lines without failing the whole load" in {
    val lines = Seq(
      "not valid json at all",
      """{"question":"Q?","response":"A.","documents":["ctx"]}"""
    ).mkString("\n")
    val path = writeFile("mixed.jsonl", lines)
    manager.loadRAGBench(path).toOption.get.samples should have size 1
  }

  it should "tag each sample with format and sourceIndex metadata" in {
    val line = """{"question":"Q?","response":"A.","documents":["ctx"]}"""
    val path = writeFile("meta.jsonl", line)
    val s    = manager.loadRAGBench(path).toOption.get.samples.head
    s.metadata("format") shouldBe "ragbench"
    s.metadata.contains("sourceIndex") shouldBe true
  }

  it should "include sourcePath and source in the dataset-level metadata" in {
    val path = writeFile("src.jsonl", """{"question":"Q","response":"A","documents":["c"]}""")
    val meta = manager.loadRAGBench(path).toOption.get.metadata
    meta("source") shouldBe "RAGBench"
    meta("sourcePath") shouldBe path
  }

  // =========================================================================
  // loadMultiHopRAG
  // =========================================================================

  "DatasetManager.loadMultiHopRAG" should "parse items inside a 'data' wrapper" in {
    val json =
      """{
        |  "data": [
        |    {"question": "Q1?", "answer": "A1.", "supporting_facts": ["fact1", "fact2"]}
        |  ]
        |}""".stripMargin
    val path = writeFile("mh.json", json)
    val ds   = manager.loadMultiHopRAG(path).toOption.get
    ds.samples should have size 1
    val s = ds.samples.head
    s.question shouldBe "Q1?"
    s.answer shouldBe "A1."
    s.contexts shouldBe Seq("fact1", "fact2")
  }

  it should "use the answer as the ground truth" in {
    val json = """{"data":[{"question":"Q?","answer":"The answer.","supporting_facts":["fact"]}]}"""
    val path = writeFile("gt.json", json)
    manager.loadMultiHopRAG(path).toOption.get.samples.head.groundTruth shouldBe Some("The answer.")
  }

  it should "fall back to 'context' when 'supporting_facts' is absent" in {
    val json = """{"data":[{"question":"Q?","answer":"A.","context":"single ctx"}]}"""
    val path = writeFile("ctx.json", json)
    manager.loadMultiHopRAG(path).toOption.get.samples.head.contexts shouldBe Seq("single ctx")
  }

  it should "skip items with an empty question" in {
    val json = """{"data":[{"question":"","answer":"A."},{"question":"Valid?","answer":"Yes."}]}"""
    val path = writeFile("empty-q.json", json)
    val ds   = manager.loadMultiHopRAG(path).toOption.get
    ds.samples should have size 1
    ds.samples.head.question shouldBe "Valid?"
  }

  it should "include source and sourcePath in dataset metadata" in {
    val path = writeFile(
      "mh2.json",
      """{"data":[{"question":"Q?","answer":"A."}]}"""
    )
    val meta = manager.loadMultiHopRAG(path).toOption.get.metadata
    meta("source") shouldBe "MultiHop-RAG"
    meta("sourcePath") shouldBe path
  }

  // =========================================================================
  // loadDocumentsFromDirectory
  // =========================================================================

  "DatasetManager.loadDocumentsFromDirectory" should "load .txt and .md files" in {
    Files.write(new File(tempDir, "doc1.txt").toPath, "Text content".getBytes)
    Files.write(new File(tempDir, "doc2.md").toPath, "# Markdown".getBytes)
    Files.write(new File(tempDir, "ignored.json").toPath, "{}".getBytes)
    val docs = manager.loadDocumentsFromDirectory(tempDir.getAbsolutePath).toOption.get
    docs should have size 2
    docs.map(_._1).forall(n => n.endsWith(".txt") || n.endsWith(".md")) shouldBe true
  }

  it should "honour a custom extension set" in {
    Files.write(new File(tempDir, "data.csv").toPath, "a,b".getBytes)
    Files.write(new File(tempDir, "readme.txt").toPath, "text".getBytes)
    val docs = manager
      .loadDocumentsFromDirectory(tempDir.getAbsolutePath, extensions = Set(".csv"))
      .toOption
      .get
    docs should have size 1
    docs.head._1 shouldBe "data.csv"
  }

  it should "return the file content correctly" in {
    val expected = "Hello, content world!"
    Files.write(new File(tempDir, "content.txt").toPath, expected.getBytes)
    val docs = manager.loadDocumentsFromDirectory(tempDir.getAbsolutePath).toOption.get
    docs.head._2 shouldBe expected
  }

  it should "return Left when given a file path instead of a directory" in {
    val filePath = writeFile("not-a-dir.txt", "text")
    manager.loadDocumentsFromDirectory(filePath).isLeft shouldBe true
  }

  it should "return Left for a non-existent path" in {
    manager.loadDocumentsFromDirectory("/tmp/no-such-dir-xyz-9999").isLeft shouldBe true
  }

  // =========================================================================
  // Companion object
  // =========================================================================

  "DatasetManager.apply" should "create a usable instance" in {
    DatasetManager() should not be null
  }

  "DatasetManager.checkDatasets" should "return a map with the three standard dataset keys" in {
    val checks = DatasetManager.checkDatasets()
    (checks.keySet should contain).allOf("RAGBench (test)", "RAGBench (train)", "MultiHop-RAG")
  }

  "DatasetManager.downloadInstructions" should "return non-empty instructions" in {
    DatasetManager.downloadInstructions.trim should not be empty
    DatasetManager.downloadInstructions should include("RAGBench")
    DatasetManager.downloadInstructions should include("MultiHop")
  }

  "DatasetManager.Paths" should "define well-formed standard paths" in {
    DatasetManager.Paths.dataRoot should not be empty
    DatasetManager.Paths.ragbenchTest should include("ragbench")
    DatasetManager.Paths.ragbenchTrain should include("ragbench")
    DatasetManager.Paths.multihopTest should include("multihop")
    DatasetManager.Paths.generatedDir should include("generated")
    DatasetManager.Paths.resultsDir should include("results")
  }
}
