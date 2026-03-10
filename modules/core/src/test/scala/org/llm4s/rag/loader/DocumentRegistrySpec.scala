package org.llm4s.rag.loader

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for DocumentRegistry implementations.
 */
class DocumentRegistrySpec extends AnyFlatSpec with Matchers {

  // ==========================================================================
  // InMemoryDocumentRegistry
  // ==========================================================================

  "InMemoryDocumentRegistry" should "start empty" in {
    val registry = InMemoryDocumentRegistry()
    registry.count() shouldBe Right(0)
    registry.allDocumentIds() shouldBe Right(Set.empty)
  }

  it should "register and retrieve a version" in {
    val registry = InMemoryDocumentRegistry()
    val version  = DocumentVersion.fromContent("content")

    registry.register("doc-1", version)
    registry.getVersion("doc-1") shouldBe Right(Some(version))
  }

  it should "return None for unknown document" in {
    val registry = InMemoryDocumentRegistry()
    registry.getVersion("nonexistent") shouldBe Right(None)
  }

  it should "update existing version on re-register" in {
    val registry = InMemoryDocumentRegistry()
    val v1       = DocumentVersion.fromContent("original")
    val v2       = DocumentVersion.fromContent("updated")

    registry.register("doc-1", v1)
    registry.register("doc-1", v2)

    val result = registry.getVersion("doc-1")
    result.isRight shouldBe true
    result.toOption.get.get.contentHash shouldBe v2.contentHash
  }

  it should "unregister a document" in {
    val registry = InMemoryDocumentRegistry()
    val version  = DocumentVersion.fromContent("content")

    registry.register("doc-1", version)
    registry.unregister("doc-1")
    registry.getVersion("doc-1") shouldBe Right(None)
  }

  it should "handle unregistering non-existent document" in {
    val registry = InMemoryDocumentRegistry()
    val result   = registry.unregister("nonexistent")
    result.isRight shouldBe true
  }

  it should "list all document IDs" in {
    val registry = InMemoryDocumentRegistry()
    registry.register("doc-1", DocumentVersion.fromContent("a"))
    registry.register("doc-2", DocumentVersion.fromContent("b"))
    registry.register("doc-3", DocumentVersion.fromContent("c"))

    registry.allDocumentIds() shouldBe Right(Set("doc-1", "doc-2", "doc-3"))
  }

  it should "clear all registrations" in {
    val registry = InMemoryDocumentRegistry()
    registry.register("doc-1", DocumentVersion.fromContent("a"))
    registry.register("doc-2", DocumentVersion.fromContent("b"))

    registry.clear()
    registry.count() shouldBe Right(0)
    registry.allDocumentIds() shouldBe Right(Set.empty)
  }

  it should "check contains correctly" in {
    val registry = InMemoryDocumentRegistry()
    registry.register("doc-1", DocumentVersion.fromContent("a"))

    registry.contains("doc-1") shouldBe Right(true)
    registry.contains("doc-2") shouldBe Right(false)
  }

  it should "count documents correctly" in {
    val registry = InMemoryDocumentRegistry()
    registry.count() shouldBe Right(0)
    registry.register("doc-1", DocumentVersion.fromContent("a"))
    registry.count() shouldBe Right(1)
    registry.register("doc-2", DocumentVersion.fromContent("b"))
    registry.count() shouldBe Right(2)
    registry.unregister("doc-1")
    registry.count() shouldBe Right(1)
  }

  // ==========================================================================
  // DocumentVersion
  // ==========================================================================

  "DocumentVersion" should "produce consistent hashes for same content" in {
    val v1 = DocumentVersion.fromContent("hello world")
    val v2 = DocumentVersion.fromContent("hello world")
    v1.contentHash shouldBe v2.contentHash
  }

  it should "produce different hashes for different content" in {
    val v1 = DocumentVersion.fromContent("hello")
    val v2 = DocumentVersion.fromContent("world")
    v1.contentHash should not be v2.contentHash
  }

  it should "detect differences via isDifferentFrom" in {
    val v1 = DocumentVersion.fromContent("version1")
    val v2 = DocumentVersion.fromContent("version2")
    v1.isDifferentFrom(v2) shouldBe true
  }

  it should "detect same content via isDifferentFrom" in {
    val v1 = DocumentVersion.fromContent("same")
    val v2 = DocumentVersion.fromContent("same")
    v1.isDifferentFrom(v2) shouldBe false
  }

  it should "include custom timestamp" in {
    val v = DocumentVersion.fromContent("content", timestamp = Some(12345L))
    v.timestamp shouldBe Some(12345L)
  }

  // ==========================================================================
  // Document
  // ==========================================================================

  "Document" should "create with explicit ID" in {
    val doc = Document(id = "my-id", content = "Content text")
    doc.id shouldBe "my-id"
    doc.content shouldBe "Content text"
    doc.metadata shouldBe Map.empty
    doc.hints shouldBe None
    doc.version shouldBe None
  }

  it should "create with auto-generated ID" in {
    val doc = Document.create("Some content")
    doc.id should not be empty
    doc.content shouldBe "Some content"
  }

  it should "detect isEmpty and nonEmpty" in {
    Document(id = "e", content = "").isEmpty shouldBe true
    Document(id = "e", content = "").nonEmpty shouldBe false
    Document(id = "ne", content = "content").isEmpty shouldBe false
    Document(id = "ne", content = "content").nonEmpty shouldBe true
  }

  it should "report length" in {
    Document(id = "x", content = "hello").length shouldBe 5
  }

  it should "add metadata" in {
    val doc = Document(id = "x", content = "content")
      .withMetadata("key", "value")
    doc.metadata shouldBe Map("key" -> "value")
  }

  it should "add metadata entries" in {
    val doc = Document(id = "x", content = "content")
      .withMetadata(Map("k1" -> "v1", "k2" -> "v2"))
    doc.metadata shouldBe Map("k1" -> "v1", "k2" -> "v2")
  }

  it should "set hints" in {
    val doc = Document(id = "x", content = "content")
      .withHints(DocumentHints.markdown)
    doc.hints shouldBe Some(DocumentHints.markdown)
  }

  it should "set version" in {
    val version = DocumentVersion.fromContent("content")
    val doc     = Document(id = "x", content = "content").withVersion(version)
    doc.version shouldBe Some(version)
  }

  it should "ensure version creates one if missing" in {
    val doc = Document(id = "x", content = "content").ensureVersion
    doc.version shouldBe defined
    doc.version.get.contentHash should not be empty
  }

  it should "ensure version preserves existing version" in {
    val version = DocumentVersion.fromContent("other")
    val doc     = Document(id = "x", content = "content").withVersion(version).ensureVersion
    doc.version shouldBe Some(version)
  }

  // ==========================================================================
  // DocumentHints
  // ==========================================================================

  "DocumentHints" should "have sensible presets" in {
    DocumentHints.empty.shouldSkip shouldBe false
    DocumentHints.markdown.chunkingStrategy shouldBe defined
    DocumentHints.code.chunkingStrategy shouldBe defined
    DocumentHints.prose.chunkingStrategy shouldBe defined
  }

  it should "detect skip hint" in {
    DocumentHints.skip("test reason").shouldSkip shouldBe true
    DocumentHints.skip("test reason").skipReason shouldBe Some("test reason")
  }

  it should "merge hints with precedence (this wins for orElse fields)" in {
    val a = DocumentHints(priority = 1, batchSize = Some(10))
    val b = DocumentHints(priority = 2, batchSize = Some(20))

    val merged = a.merge(b)
    // merge uses max for priority, orElse for batchSize (this wins)
    merged.priority shouldBe 2
    merged.batchSize shouldBe Some(10)
  }

  it should "merge hints keeping existing when other is None" in {
    val a = DocumentHints(batchSize = Some(10))
    val b = DocumentHints()

    val merged = a.merge(b)
    merged.batchSize shouldBe Some(10)
  }

  it should "merge with max priority" in {
    val a = DocumentHints(priority = 5)
    val b = DocumentHints(priority = 3)
    a.merge(b).priority shouldBe 5
  }

  it should "create rate limited hints" in {
    val hints = DocumentHints.rateLimited(5)
    hints.batchSize shouldBe Some(5)
    hints.shouldSkip shouldBe false
  }
}
