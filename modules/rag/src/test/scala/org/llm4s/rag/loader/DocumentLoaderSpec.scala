package org.llm4s.rag.loader

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll

import java.io.File
import java.nio.file.{ Files, Path }

class DocumentLoaderSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  // Create a temporary directory structure for testing
  private var tempDir: Path          = _
  private var tempTextFile: File     = _
  private var tempMarkdownFile: File = _
  private var tempScalaFile: File    = _
  private var tempSubDir: File       = _
  private var tempSubDirFile: File   = _
  private var tempDeepDir: File      = _
  private var tempDeepFile: File     = _

  override def beforeAll(): Unit = {
    super.beforeAll()

    // Create temp directory structure
    tempDir = Files.createTempDirectory("document-loader-test")

    // Create text file
    tempTextFile = new File(tempDir.toFile, "test.txt")
    Files.write(tempTextFile.toPath, "Hello World".getBytes)

    // Create markdown file
    tempMarkdownFile = new File(tempDir.toFile, "readme.md")
    Files.write(tempMarkdownFile.toPath, "# Title\n\nSome markdown content".getBytes)

    // Create scala file
    tempScalaFile = new File(tempDir.toFile, "Example.scala")
    Files.write(tempScalaFile.toPath, "object Example { val x = 1 }".getBytes)

    // Create subdirectory with file
    tempSubDir = new File(tempDir.toFile, "subdir")
    tempSubDir.mkdir()
    tempSubDirFile = new File(tempSubDir, "nested.txt")
    Files.write(tempSubDirFile.toPath, "Nested content".getBytes)

    // Create deep nested directory
    tempDeepDir = new File(tempSubDir, "deep")
    tempDeepDir.mkdir()
    tempDeepFile = new File(tempDeepDir, "deep.txt")
    Files.write(tempDeepFile.toPath, "Deep content".getBytes)
  }

  override def afterAll(): Unit = {
    // Clean up temp files
    def deleteRecursively(file: File): Unit = {
      if (file.isDirectory) {
        Option(file.listFiles()).foreach(_.foreach(deleteRecursively))
      }
      file.delete()
    }

    if (tempDir != null) {
      deleteRecursively(tempDir.toFile)
    }

    super.afterAll()
  }

  // ========== Document Tests ==========

  "Document" should "create with auto-generated ID" in {
    val doc = Document.create("test content").ensureVersion
    doc.id should not be empty
    doc.content shouldBe "test content"
    doc.version shouldBe defined
  }

  it should "compute content hash for versioning" in {
    val doc1 = Document.create("same content").ensureVersion
    val doc2 = Document.create("same content").ensureVersion
    val doc3 = Document.create("different content").ensureVersion

    doc1.version.map(_.contentHash) shouldBe doc2.version.map(_.contentHash)
    doc1.version.map(_.contentHash) should not be doc3.version.map(_.contentHash)
  }

  it should "preserve metadata when adding more" in {
    val doc     = Document("id", "content", Map("key1" -> "value1"))
    val updated = doc.withMetadata(Map("key2" -> "value2"))

    updated.metadata should contain("key1" -> "value1")
    updated.metadata should contain("key2" -> "value2")
  }

  // ========== DocumentVersion Tests ==========

  "DocumentVersion" should "generate consistent hashes for same content" in {
    val v1 = DocumentVersion.fromContent("test content")
    val v2 = DocumentVersion.fromContent("test content")

    v1.contentHash shouldBe v2.contentHash
  }

  it should "generate different hashes for different content" in {
    val v1 = DocumentVersion.fromContent("content A")
    val v2 = DocumentVersion.fromContent("content B")

    v1.contentHash should not be v2.contentHash
  }

  // ========== DocumentHints Tests ==========

  "DocumentHints" should "provide markdown preset" in {
    val hints = DocumentHints.markdown
    hints.chunkingStrategy shouldBe defined
  }

  it should "provide code preset" in {
    val hints = DocumentHints.code
    hints.chunkingStrategy shouldBe defined
    hints.chunkingConfig shouldBe defined
  }

  it should "provide skip hint with reason" in {
    val hints = DocumentHints.skip("too large")
    hints.skipReason shouldBe Some("too large")
  }

  // ========== TextLoader Tests ==========

  "TextLoader" should "load documents from content" in {
    val loader  = TextLoader("content", "doc-1")
    val results = loader.load().toList

    (results should have).length(1)
    results.head match {
      case LoadResult.Success(doc) =>
        doc.id shouldBe "doc-1"
        doc.content shouldBe "content"
      case _ => fail("Expected success")
    }
  }

  it should "create multiple documents from pairs" in {
    val loader = TextLoader.fromPairs(
      "id1" -> "content1",
      "id2" -> "content2"
    )

    loader.estimatedCount shouldBe Some(2)
    (loader.load().toList should have).length(2)
  }

  it should "build documents fluently" in {
    val loader = TextLoader
      .builder()
      .add("doc1", "content1")
      .add("doc2", "content2", Map("key" -> "value"))
      .build()

    loader.estimatedCount shouldBe Some(2)
  }

  // ========== FileLoader Tests ==========

  "FileLoader" should "fail for non-existent files" in {
    val loader  = FileLoader("/nonexistent/path/file.txt")
    val results = loader.load().toList

    (results should have).length(1)
    results.head shouldBe a[LoadResult.Failure]
    results.head.asInstanceOf[LoadResult.Failure].error.message should include("not found")
  }

  it should "fail when path is a directory" in {
    val loader  = FileLoader(tempDir.toString)
    val results = loader.load().toList

    (results should have).length(1)
    results.head shouldBe a[LoadResult.Failure]
    results.head.asInstanceOf[LoadResult.Failure].error.message should include("Not a file")
  }

  it should "successfully load a text file" in {
    val loader  = FileLoader(tempTextFile.toPath)
    val results = loader.load().toList

    (results should have).length(1)
    results.head shouldBe a[LoadResult.Success]

    val doc = results.head.asInstanceOf[LoadResult.Success].document
    doc.content shouldBe "Hello World"
    (doc.metadata should contain).key("source")
    (doc.metadata should contain).key("path")
    (doc.metadata should contain).key("extension")
    (doc.metadata should contain).key("lastModified")
    (doc.metadata should contain).key("size")
    doc.metadata("extension") shouldBe "txt"
  }

  it should "detect prose hints for text files" in {
    val loader  = FileLoader(tempTextFile.toPath)
    val results = loader.load().toList

    val doc = results.head.asInstanceOf[LoadResult.Success].document
    doc.hints shouldBe defined
    doc.hints.get shouldBe DocumentHints.prose
  }

  it should "detect markdown hints for .md files" in {
    val loader  = FileLoader(tempMarkdownFile.toPath)
    val results = loader.load().toList

    val doc = results.head.asInstanceOf[LoadResult.Success].document
    doc.hints shouldBe defined
    doc.hints.get shouldBe DocumentHints.markdown
  }

  it should "detect code hints for .scala files" in {
    val loader  = FileLoader(tempScalaFile.toPath)
    val results = loader.load().toList

    val doc = results.head.asInstanceOf[LoadResult.Success].document
    doc.hints shouldBe defined
    doc.hints.get shouldBe DocumentHints.code
  }

  it should "include version information" in {
    val loader  = FileLoader(tempTextFile.toPath)
    val results = loader.load().toList

    val doc = results.head.asInstanceOf[LoadResult.Success].document
    doc.version shouldBe defined
    doc.version.get.contentHash should not be empty
    doc.version.get.timestamp shouldBe defined
  }

  it should "attach custom metadata" in {
    val loader  = FileLoader(tempTextFile.toPath, Map("custom" -> "value"))
    val results = loader.load().toList

    val doc = results.head.asInstanceOf[LoadResult.Success].document
    doc.metadata should contain("custom" -> "value")
  }

  it should "have estimatedCount of 1" in {
    val loader = FileLoader(tempTextFile.toPath)
    loader.estimatedCount shouldBe Some(1)
  }

  it should "provide description" in {
    val loader = FileLoader(tempTextFile.toPath)
    loader.description should include("FileLoader")
    loader.description should include(tempTextFile.getPath)
  }

  "FileLoader companion object" should "create from string path" in {
    val loader = FileLoader(tempTextFile.getAbsolutePath)
    loader.path shouldBe tempTextFile.toPath
  }

  it should "create from string path with metadata" in {
    val loader = FileLoader(tempTextFile.getAbsolutePath, Map("key" -> "value"))
    loader.metadata should contain("key" -> "value")
  }

  it should "create from File object" in {
    val loader = FileLoader(tempTextFile)
    loader.path shouldBe tempTextFile.toPath
  }

  // ========== DirectoryLoader Tests ==========

  "DirectoryLoader" should "fail for non-existent directories" in {
    val loader  = DirectoryLoader("/nonexistent/directory")
    val results = loader.load().toList

    (results should have).length(1)
    results.head shouldBe a[LoadResult.Failure]
    results.head.asInstanceOf[LoadResult.Failure].error.message should include("not found")
  }

  it should "fail when path is a file" in {
    val loader  = DirectoryLoader(tempTextFile.toPath)
    val results = loader.load().toList

    (results should have).length(1)
    results.head shouldBe a[LoadResult.Failure]
    results.head.asInstanceOf[LoadResult.Failure].error.message should include("Not a directory")
  }

  it should "load files from directory" in {
    val loader  = DirectoryLoader(tempDir).withExtensions(Set("txt", "md", "scala"))
    val results = loader.load().toList

    // Should have files from dir and subdirs (recursive by default)
    results.size should be >= 3
    results.forall(_.isSuccess) shouldBe true
  }

  it should "filter by extensions" in {
    val loader = DirectoryLoader(tempDir)
      .withExtensions(Set("txt"))

    val results = loader.load().toList.collect { case LoadResult.Success(d) => d }

    // Only .txt files
    results.forall(_.metadata.get("extension").contains("txt")) shouldBe true
  }

  it should "add extension with withExtension" in {
    val loader = DirectoryLoader(tempDir)
      .withExtensions(Set("txt"))
      .withExtension("md")

    loader.extensions should contain("txt")
    loader.extensions should contain("md")
  }

  it should "strip dot from extensions" in {
    val loader = DirectoryLoader(tempDir)
      .withExtensions(Set(".txt", ".md"))

    loader.extensions should contain("txt")
    loader.extensions should contain("md")
    loader.extensions should not contain ".txt"
    loader.extensions should not contain ".md"
  }

  it should "load non-recursively when recursive is false" in {
    val recursiveLoader    = DirectoryLoader(tempDir).withExtensions(Set("txt"))
    val nonRecursiveLoader = DirectoryLoader(tempDir).withExtensions(Set("txt")).withRecursive(false)

    val recursiveResults    = recursiveLoader.load().toList
    val nonRecursiveResults = nonRecursiveLoader.load().toList

    // Non-recursive should have fewer results
    nonRecursiveResults.size should be < recursiveResults.size
  }

  it should "respect maxDepth" in {
    val shallowLoader = DirectoryLoader(tempDir).withExtensions(Set("txt")).withMaxDepth(0)
    val deepLoader    = DirectoryLoader(tempDir).withExtensions(Set("txt")).withMaxDepth(10)

    val shallowResults = shallowLoader.load().toList
    val deepResults    = deepLoader.load().toList

    // Shallow should have fewer results
    shallowResults.size should be < deepResults.size
  }

  it should "attach metadata to all documents" in {
    val loader =
      DirectoryLoader(tempDir).withExtensions(Set("txt")).withMetadata(Map("custom" -> "value"))

    val results = loader.load().toList.collect { case LoadResult.Success(d) => d }

    results.foreach { doc =>
      doc.metadata should contain("custom" -> "value")
      (doc.metadata should contain).key("directory")
    }
  }

  it should "provide estimated count" in {
    val loader = DirectoryLoader(tempDir).withExtensions(Set("txt", "md", "scala"))
    loader.estimatedCount shouldBe defined
    loader.estimatedCount.get should be >= 1
  }

  it should "return None for estimated count on non-existent directory" in {
    val loader = DirectoryLoader("/nonexistent/directory")
    loader.estimatedCount shouldBe None
  }

  it should "provide description" in {
    val loader = DirectoryLoader(tempDir).withExtensions(Set("txt", "md"))
    loader.description should include("DirectoryLoader")
    loader.description should include("txt")
    loader.description should include("md")
  }

  "DirectoryLoader companion object" should "create from string path" in {
    val loader = DirectoryLoader(tempDir.toString)
    loader.path shouldBe tempDir
  }

  it should "create from string path with extensions" in {
    val loader = DirectoryLoader(tempDir.toString, Set("txt", "md"))
    loader.extensions shouldBe Set("txt", "md")
  }

  it should "create from File object" in {
    val loader = DirectoryLoader(tempDir.toFile)
    loader.path shouldBe tempDir
  }

  it should "have default extensions" in {
    DirectoryLoader.defaultExtensions should contain("txt")
    DirectoryLoader.defaultExtensions should contain("md")
    DirectoryLoader.defaultExtensions should contain("pdf")
    DirectoryLoader.defaultExtensions should contain("docx")
  }

  "DirectoryLoader.markdown" should "only load markdown files" in {
    val loader = DirectoryLoader.markdown(tempDir.toString)
    loader.extensions shouldBe Set("md", "markdown")
  }

  "DirectoryLoader.code" should "load code files" in {
    val loader = DirectoryLoader.code(tempDir.toString)
    loader.extensions should contain("scala")
    loader.extensions should contain("java")
    loader.extensions should contain("py")
    loader.extensions should contain("js")
    loader.extensions should contain("ts")
  }

  "DirectoryLoader.text" should "only load text files" in {
    val loader = DirectoryLoader.text(tempDir.toString)
    loader.extensions shouldBe Set("txt")
  }

  "DirectoryLoader.pdf" should "only load PDF files" in {
    val loader = DirectoryLoader.pdf(tempDir.toString)
    loader.extensions shouldBe Set("pdf")
  }

  "DirectoryLoader.docx" should "only load Word documents" in {
    val loader = DirectoryLoader.docx(tempDir.toString)
    loader.extensions shouldBe Set("docx")
  }

  // ========== DocumentLoaders Combinators Tests ==========

  "DocumentLoaders.combine" should "combine multiple loaders" in {
    val loader1 = TextLoader("content1", "id1")
    val loader2 = TextLoader("content2", "id2")

    val combined = DocumentLoaders.combine(Seq(loader1, loader2))
    val results  = combined.load().toList

    (results should have).length(2)
  }

  "DocumentLoaders.filter" should "filter documents by predicate" in {
    val loader = TextLoader.fromPairs(
      "id1" -> "hello world",
      "id2" -> "goodbye world",
      "id3" -> "hello again"
    )

    val filtered = DocumentLoaders.filter(loader)(_.content.contains("hello"))
    val results  = filtered.load().collect { case LoadResult.Success(d) => d }.toList

    (results should have).length(2)
    (results.map(_.id) should contain).allOf("id1", "id3")
  }

  "DocumentLoaders.map" should "transform documents" in {
    val loader = TextLoader("original", "id1")

    val mapped  = DocumentLoaders.map(loader)(doc => doc.copy(content = doc.content.toUpperCase))
    val results = mapped.load().collect { case LoadResult.Success(d) => d }.toList

    results.head.content shouldBe "ORIGINAL"
  }

  "DocumentLoaders.take" should "limit number of documents" in {
    val loader = TextLoader.fromPairs(
      "id1" -> "c1",
      "id2" -> "c2",
      "id3" -> "c3"
    )

    val limited = DocumentLoaders.take(loader, 2)
    (limited.load().toList should have).length(2)
  }

  "DocumentLoader.++" should "combine loaders using operator" in {
    val loader1 = TextLoader("c1", "id1")
    val loader2 = TextLoader("c2", "id2")

    val combined = loader1 ++ loader2
    (combined.load().toList should have).length(2)
  }

  // ========== LoadResult Tests ==========

  "LoadResult" should "create success results" in {
    val doc    = Document("id", "content")
    val result = LoadResult.success(doc)

    result shouldBe a[LoadResult.Success]
    result.asInstanceOf[LoadResult.Success].document shouldBe doc
  }

  it should "create failure results" in {
    val error  = org.llm4s.error.ProcessingError("test", "error message")
    val result = LoadResult.failure("source", error)

    result shouldBe a[LoadResult.Failure]
    result.asInstanceOf[LoadResult.Failure].source shouldBe "source"
  }

  it should "create skipped results" in {
    val result = LoadResult.skipped("source", "reason")

    result shouldBe a[LoadResult.Skipped]
    result.asInstanceOf[LoadResult.Skipped].reason shouldBe "reason"
  }

  // ========== LoadStats Tests ==========

  "LoadStats" should "calculate derived statistics" in {
    val stats = LoadStats(10, 7, 2, 1, Seq.empty)

    stats.totalAttempted shouldBe 10
    stats.successRate should be(0.7 +- 0.01)
    stats.hasErrors shouldBe true
  }

  it should "report no errors when all successful" in {
    val stats = LoadStats(5, 5, 0, 0, Seq.empty)

    stats.hasErrors shouldBe false
    stats.successRate shouldBe 1.0
  }

  // ========== SyncStats Tests ==========

  "SyncStats" should "calculate total and changed counts" in {
    val stats = SyncStats(added = 5, updated = 2, deleted = 1, unchanged = 42)

    stats.total shouldBe 50
    stats.changed shouldBe 8
  }

  // ========== DocumentRegistry Tests ==========

  "InMemoryDocumentRegistry" should "register and retrieve versions" in {
    val registry = InMemoryDocumentRegistry()
    val version  = DocumentVersion.fromContent("test")

    registry.register("doc-1", version) shouldBe Right(())
    registry.getVersion("doc-1") shouldBe Right(Some(version))
  }

  it should "return None for unknown documents" in {
    val registry = InMemoryDocumentRegistry()
    registry.getVersion("unknown") shouldBe Right(None)
  }

  it should "unregister documents" in {
    val registry = InMemoryDocumentRegistry()
    val version  = DocumentVersion.fromContent("test")

    registry.register("doc-1", version)
    registry.unregister("doc-1") shouldBe Right(())
    registry.getVersion("doc-1") shouldBe Right(None)
  }

  it should "list all document IDs" in {
    val registry = InMemoryDocumentRegistry()

    registry.register("doc-1", DocumentVersion.fromContent("c1"))
    registry.register("doc-2", DocumentVersion.fromContent("c2"))

    registry.allDocumentIds() shouldBe Right(Set("doc-1", "doc-2"))
  }

  it should "clear all registrations" in {
    val registry = InMemoryDocumentRegistry()

    registry.register("doc-1", DocumentVersion.fromContent("c1"))
    registry.clear() shouldBe Right(())
    registry.allDocumentIds() shouldBe Right(Set.empty)
  }

  // ========== LoadingConfig Tests ==========

  "LoadingConfig" should "have sensible defaults" in {
    val config = LoadingConfig.default

    config.failFast shouldBe false
    config.useHints shouldBe true
    config.skipEmptyDocuments shouldBe true
    config.parallelism should be > 0
  }

  it should "provide strict preset" in {
    LoadingConfig.strict.failFast shouldBe true
  }

  it should "provide lenient preset" in {
    LoadingConfig.lenient.failFast shouldBe false
  }

  it should "provide high performance preset" in {
    val hp = LoadingConfig.highPerformance
    hp.parallelism should be > LoadingConfig.default.parallelism
  }

  it should "support fluent configuration" in {
    val config = LoadingConfig.default.withFailFast
      .withParallelism(8)
      .withBatchSize(20)

    config.failFast shouldBe true
    config.parallelism shouldBe 8
    config.batchSize shouldBe 20
  }

  // ========== Additional DocumentLoaders Tests ==========

  "DocumentLoaders.empty" should "return no documents" in {
    val loader = DocumentLoaders.empty
    loader.load().toList shouldBe empty
    loader.estimatedCount shouldBe Some(0)
    loader.description shouldBe "Empty"
  }

  "DocumentLoaders.successesOnly" should "filter out failures and skips" in {
    val doc1  = Document("id1", "content1")
    val doc2  = Document("id2", "content2")
    val error = org.llm4s.error.ProcessingError("test", "error")
    val loader = new DocumentLoader {
      def load(): Iterator[LoadResult] = Iterator(
        LoadResult.success(doc1),
        LoadResult.failure("source", error),
        LoadResult.skipped("source2", "reason"),
        LoadResult.success(doc2)
      )
      def description: String = "Mixed"
    }

    val filtered = DocumentLoaders.successesOnly(loader)
    val results  = filtered.load().toList

    (results should have).length(2)
    results.forall(_.isSuccess) shouldBe true
    filtered.description shouldBe "SuccessesOnly(Mixed)"
  }

  "DocumentLoaders.fromIterator" should "create loader from iterator" in {
    val docs   = Seq(Document("id1", "c1"), Document("id2", "c2"))
    val loader = DocumentLoaders.fromIterator(docs.iterator, "TestIterator")

    val results = loader.load().collect { case LoadResult.Success(d) => d }.toList
    (results should have).length(2)
    loader.description shouldBe "TestIterator"
  }

  it should "use default description" in {
    val loader = DocumentLoaders.fromIterator(Iterator.empty)
    loader.description shouldBe "Custom"
  }

  "DocumentLoaders.fromDocuments" should "create loader from sequence" in {
    val docs   = Seq(Document("id1", "c1"), Document("id2", "c2"), Document("id3", "c3"))
    val loader = DocumentLoaders.fromDocuments(docs)

    loader.estimatedCount shouldBe Some(3)
    (loader.load().toList should have).length(3)
    loader.description shouldBe "Documents(3)"
  }

  "DocumentLoaders.defer" should "lazily create the loader" in {
    var created = false
    val loader = DocumentLoaders.defer {
      created = true
      TextLoader("content", "id")
    }

    created shouldBe false
    loader.description shouldBe "Deferred"

    // Trigger lazy evaluation
    loader.load().toList
    created shouldBe true
  }

  it should "cache the underlying loader" in {
    var createCount = 0
    val loader = DocumentLoaders.defer {
      createCount += 1
      TextLoader("content", "id")
    }

    loader.load().toList
    loader.load().toList
    loader.estimatedCount

    createCount shouldBe 1
  }

  "DocumentLoaders.withMetadata" should "add metadata to all documents" in {
    val loader  = TextLoader.fromPairs("id1" -> "c1", "id2" -> "c2")
    val wrapped = DocumentLoaders.withMetadata(loader, Map("source" -> "test"))

    val results = wrapped.load().collect { case LoadResult.Success(d) => d }.toList
    results.foreach(doc => doc.metadata should contain("source" -> "test"))
  }

  "DocumentLoaders.withHints" should "add hints to all documents" in {
    val loader  = TextLoader.fromPairs("id1" -> "c1")
    val hints   = DocumentHints.markdown
    val wrapped = DocumentLoaders.withHints(loader, hints)

    val results = wrapped.load().collect { case LoadResult.Success(d) => d }.toList
    results.head.hints shouldBe defined
  }

  it should "merge with existing hints" in {
    val doc     = Document("id", "content", hints = Some(DocumentHints.code))
    val loader  = DocumentLoaders.fromDocuments(Seq(doc))
    val wrapped = DocumentLoaders.withHints(loader, DocumentHints(skipReason = Some("test")))

    val results = wrapped.load().collect { case LoadResult.Success(d) => d }.toList
    results.head.hints.flatMap(_.skipReason) shouldBe Some("test")
  }

  "DocumentLoaders.drop" should "skip first n documents" in {
    val loader  = TextLoader.fromPairs("id1" -> "c1", "id2" -> "c2", "id3" -> "c3")
    val dropped = DocumentLoaders.drop(loader, 2)

    val results = dropped.load().collect { case LoadResult.Success(d) => d }.toList
    (results should have).length(1)
    results.head.id shouldBe "id3"
    dropped.description shouldBe "Drop(2, TextLoader(3 documents))"
  }

  it should "handle drop more than available" in {
    val loader  = TextLoader.fromPairs("id1" -> "c1")
    val dropped = DocumentLoaders.drop(loader, 10)

    dropped.load().toList shouldBe empty
    dropped.estimatedCount shouldBe Some(0)
  }

  it should "update estimatedCount correctly" in {
    val loader  = TextLoader.fromPairs("id1" -> "c1", "id2" -> "c2", "id3" -> "c3")
    val dropped = DocumentLoaders.drop(loader, 1)

    dropped.estimatedCount shouldBe Some(2)
  }

  "DocumentLoaders.combine estimatedCount" should "sum all counts when all defined" in {
    val loader1 = TextLoader.fromPairs("id1" -> "c1", "id2" -> "c2")
    val loader2 = TextLoader.fromPairs("id3" -> "c3")

    val combined = DocumentLoaders.combine(Seq(loader1, loader2))
    combined.estimatedCount shouldBe Some(3)
  }

  it should "return None when any count is undefined" in {
    val loader1 = TextLoader.fromPairs("id1" -> "c1")
    val loader2 = new DocumentLoader {
      def load(): Iterator[LoadResult] = Iterator.empty
      def description: String          = "Unknown"
      // estimatedCount defaults to None
    }

    val combined = DocumentLoaders.combine(Seq(loader1, loader2))
    combined.estimatedCount shouldBe None
  }

  "DocumentLoaders.filter" should "preserve errors in output" in {
    val error = org.llm4s.error.ProcessingError("test", "error")
    val loader = new DocumentLoader {
      def load(): Iterator[LoadResult] = Iterator(
        LoadResult.success(Document("id1", "hello")),
        LoadResult.failure("source", error),
        LoadResult.success(Document("id2", "goodbye"))
      )
      def description: String = "Mixed"
    }

    val filtered = DocumentLoaders.filter(loader)(_.content.contains("hello"))
    val results  = filtered.load().toList

    // Should keep the error and only the matching success
    (results should have).length(2)
    results.count(_.isSuccess) shouldBe 1
    results.count(r => r.isInstanceOf[LoadResult.Failure]) shouldBe 1
  }

  "DocumentLoaders.map" should "preserve errors" in {
    val error = org.llm4s.error.ProcessingError("test", "error")
    val loader = new DocumentLoader {
      def load(): Iterator[LoadResult] = Iterator(
        LoadResult.success(Document("id1", "content")),
        LoadResult.failure("source", error)
      )
      def description: String = "Mixed"
    }

    val mapped  = DocumentLoaders.map(loader)(d => d.copy(content = d.content.toUpperCase))
    val results = mapped.load().toList

    (results should have).length(2)
    results.head match {
      case LoadResult.Success(doc) => doc.content shouldBe "CONTENT"
      case _                       => fail("Expected success")
    }
    results(1) shouldBe a[LoadResult.Failure]
  }

  it should "preserve estimatedCount" in {
    val loader = TextLoader.fromPairs("id1" -> "c1", "id2" -> "c2")
    val mapped = DocumentLoaders.map(loader)(identity)

    mapped.estimatedCount shouldBe Some(2)
  }

  "DocumentLoaders.take" should "update estimatedCount" in {
    val loader  = TextLoader.fromPairs("id1" -> "c1", "id2" -> "c2", "id3" -> "c3")
    val limited = DocumentLoaders.take(loader, 2)

    limited.estimatedCount shouldBe Some(2)
  }

  it should "not exceed original count" in {
    val loader  = TextLoader.fromPairs("id1" -> "c1")
    val limited = DocumentLoaders.take(loader, 100)

    limited.estimatedCount shouldBe Some(1)
  }

  // ========== Additional TextLoader Tests ==========

  "TextLoader.empty" should "return no documents" in {
    TextLoader.empty.load().toList shouldBe empty
    TextLoader.empty.estimatedCount shouldBe Some(0)
  }

  "TextLoader.apply(content)" should "create with auto-generated ID" in {
    val loader  = TextLoader("test content")
    val results = loader.load().collect { case LoadResult.Success(d) => d }.toList

    (results should have).length(1)
    results.head.content shouldBe "test content"
    results.head.id should not be empty
  }

  "TextLoader.apply(content, id, metadata)" should "create with metadata" in {
    val loader  = TextLoader("content", "my-id", Map("key" -> "value"))
    val results = loader.load().collect { case LoadResult.Success(d) => d }.toList

    (results should have).length(1)
    results.head.id shouldBe "my-id"
    results.head.content shouldBe "content"
    results.head.metadata should contain("key" -> "value")
  }

  "TextLoader.fromMap" should "create from ID -> content map" in {
    val loader = TextLoader.fromMap(
      Map(
        "doc1" -> "content1",
        "doc2" -> "content2"
      )
    )

    loader.estimatedCount shouldBe Some(2)
    val results = loader.load().collect { case LoadResult.Success(d) => d }.toList
    results.map(_.id).toSet shouldBe Set("doc1", "doc2")
  }

  "TextLoader.fromContents" should "create with auto-generated IDs" in {
    val loader  = TextLoader.fromContents("content1", "content2", "content3")
    val results = loader.load().collect { case LoadResult.Success(d) => d }.toList

    (results should have).length(3)
    (results.map(_.content) should contain).allOf("content1", "content2", "content3")
    (results.map(_.id).distinct should have).length(3)
  }

  "TextLoader.withDocument(doc)" should "add a document" in {
    val loader = TextLoader.empty
      .withDocument(Document("id1", "content1"))
      .withDocument(Document("id2", "content2"))

    loader.estimatedCount shouldBe Some(2)
  }

  "TextLoaderBuilder.add(doc)" should "add a document object" in {
    val doc    = Document("my-doc", "my-content", Map("key" -> "value"))
    val loader = TextLoader.builder().add(doc).build()

    val results = loader.load().collect { case LoadResult.Success(d) => d }.toList
    (results should have).length(1)
    results.head shouldBe doc.ensureVersion
  }

  // ========== Additional LoadResult Tests ==========

  "LoadResult.isSuccess" should "return correct values" in {
    LoadResult.success(Document("id", "c")).isSuccess shouldBe true
    LoadResult.failure("s", org.llm4s.error.ProcessingError("t", "e")).isSuccess shouldBe false
    LoadResult.skipped("s", "r").isSuccess shouldBe false
  }

  "LoadResult.isFailure" should "return correct values" in {
    LoadResult.success(Document("id", "c")).isFailure shouldBe false
    LoadResult.failure("s", org.llm4s.error.ProcessingError("t", "e")).isFailure shouldBe true
    LoadResult.skipped("s", "r").isFailure shouldBe false
  }

  "LoadResult.isSkipped" should "return correct values" in {
    LoadResult.success(Document("id", "c")).isSkipped shouldBe false
    LoadResult.failure("s", org.llm4s.error.ProcessingError("t", "e")).isSkipped shouldBe false
    LoadResult.skipped("s", "r").isSkipped shouldBe true
  }

  "LoadResult.toOption" should "return Some for success, None otherwise" in {
    LoadResult.success(Document("id", "c")).toOption shouldBe defined
    LoadResult.failure("s", org.llm4s.error.ProcessingError("t", "e")).toOption shouldBe None
    LoadResult.skipped("s", "r").toOption shouldBe None
  }

  // ========== Additional Document Tests ==========

  "Document.length" should "return content length" in {
    Document("id", "hello").length shouldBe 5
    Document("id", "").length shouldBe 0
  }

  "Document.isEmpty" should "return true for empty content" in {
    Document("id", "").isEmpty shouldBe true
    Document("id", "content").isEmpty shouldBe false
  }

  "Document.nonEmpty" should "return true for non-empty content" in {
    Document("id", "").nonEmpty shouldBe false
    Document("id", "content").nonEmpty shouldBe true
  }

  // ========== Additional LoadStats Tests ==========

  "LoadStats.formattedErrors" should "return formatted error list" in {
    val error1 = org.llm4s.error.ProcessingError("source1", "Error 1")
    val error2 = org.llm4s.error.ProcessingError("source2", "Error 2")
    val stats = LoadStats(
      totalAttempted = 3,
      successful = 1,
      failed = 2,
      skipped = 0,
      errors = Seq(("file1.txt", error1), ("file2.txt", error2))
    )

    stats.formattedErrors should include("file1.txt")
    stats.formattedErrors should include("file2.txt")
  }

  it should "return empty string when no errors" in {
    val stats = LoadStats(1, 1, 0, 0, Seq.empty)
    stats.formattedErrors shouldBe ""
  }

  // ========== Additional SyncStats Tests ==========

  "SyncStats.hasChanges" should "return true when any changes" in {
    SyncStats(1, 0, 0, 0).hasChanges shouldBe true
    SyncStats(0, 1, 0, 0).hasChanges shouldBe true
    SyncStats(0, 0, 1, 0).hasChanges shouldBe true
    SyncStats(0, 0, 0, 10).hasChanges shouldBe false
  }

  // ========== Additional DocumentHints Tests ==========

  "DocumentHints.merge" should "combine hints with skipReason" in {
    val hints1 = DocumentHints(priority = 5)
    val hints2 = DocumentHints(skipReason = Some("test"))

    val merged = hints1.merge(hints2)
    merged.priority shouldBe 5
    merged.skipReason shouldBe Some("test")
  }

  it should "prefer this instance's values over other" in {
    val hints1 = DocumentHints(priority = 10)
    val hints2 = DocumentHints(priority = 5)

    // merge uses orElse, so hints1 values take precedence when defined
    val merged = hints1.merge(hints2)
    merged.priority shouldBe 10
  }

  "DocumentHints.shouldSkip" should "return true when skip reason is set" in {
    DocumentHints().shouldSkip shouldBe false
    DocumentHints(skipReason = Some("reason")).shouldSkip shouldBe true
  }

  // ========== Additional DocumentVersion Tests ==========

  "DocumentVersion.isDifferentFrom" should "detect changes" in {
    val v1 = DocumentVersion.fromContent("content A")
    val v2 = DocumentVersion.fromContent("content B")
    val v3 = DocumentVersion.fromContent("content A")

    v1.isDifferentFrom(v2) shouldBe true
    v1.isDifferentFrom(v3) shouldBe false
  }

  // ========== Additional LoadingConfig Tests ==========

  "LoadingConfig.withContinueOnError" should "set failFast to false" in {
    val config = LoadingConfig.strict.withContinueOnError
    config.failFast shouldBe false
  }

  "LoadingConfig.withVersioning" should "enable versioning" in {
    val config = LoadingConfig.default.withVersioning(true)
    config.enableVersioning shouldBe true
  }

  it should "disable versioning" in {
    val config = LoadingConfig.default.withVersioning(false)
    config.enableVersioning shouldBe false
  }

  "LoadingConfig.withSkipEmpty" should "enable skip empty" in {
    val config = LoadingConfig.default.copy(skipEmptyDocuments = false).withSkipEmpty(true)
    config.skipEmptyDocuments shouldBe true
  }

  it should "disable skip empty" in {
    val config = LoadingConfig.default.withSkipEmpty(false)
    config.skipEmptyDocuments shouldBe false
  }

  "LoadingConfig.withHints" should "enable hints" in {
    val config = LoadingConfig.default.copy(useHints = false).withHints(true)
    config.useHints shouldBe true
  }

  it should "disable hints" in {
    val config = LoadingConfig.default.withHints(false)
    config.useHints shouldBe false
  }

  "LoadingConfig.conservative" should "have lower parallelism" in {
    LoadingConfig.conservative.parallelism should be < LoadingConfig.default.parallelism
    LoadingConfig.conservative.batchSize should be < LoadingConfig.default.batchSize
  }
}
