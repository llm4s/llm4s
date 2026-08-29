package org.llm4s.vectorstore

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.error.ProcessingError

class PgVectorStoreValidationSpec extends AnyFlatSpec with Matchers {

  "validateTableName" should "accept valid table names with letters, digits, and underscores" in {
    // Test multiple valid patterns
    PgVectorStore.validateTableName("vectors") shouldBe Right("vectors")
    PgVectorStore.validateTableName("vectors_1") shouldBe Right("vectors_1")
    PgVectorStore.validateTableName("_vectors") shouldBe Right("_vectors")
    PgVectorStore.validateTableName("VECTORS123") shouldBe Right("VECTORS123")
    PgVectorStore.validateTableName("v") shouldBe Right("v")
  }

  it should "reject table names with special characters" in {
    PgVectorStore.validateTableName("vectors-1") should matchPattern {
      case Left(ProcessingError(msg, op, _)) if msg.contains("Invalid table name") && op == "pgvector-store" =>
    }
  }

  it should "reject table names with spaces" in {
    PgVectorStore.validateTableName("vectors table") should matchPattern {
      case Left(ProcessingError(msg, op, _)) if msg.contains("Invalid table name") && op == "pgvector-store" =>
    }
  }

  it should "reject SQL injection attempts" in {
    PgVectorStore.validateTableName("; DROP TABLE users; --") should matchPattern {
      case Left(ProcessingError(msg, op, _)) if msg.contains("Invalid table name") && op == "pgvector-store" =>
    }
  }

  it should "reject empty table names" in {
    PgVectorStore.validateTableName("") should matchPattern {
      case Left(ProcessingError(msg, op, _)) if msg.contains("Invalid table name") && op == "pgvector-store" =>
    }
  }

  it should "reject null table names" in {
    PgVectorStore.validateTableName(null) should matchPattern {
      case Left(ProcessingError(msg, op, _)) if msg.contains("must not be null") && op == "pgvector-store" =>
    }
  }

  it should "reject table names starting with a digit" in {
    PgVectorStore.validateTableName("123") should matchPattern {
      case Left(ProcessingError(msg, op, _)) if msg.contains("Invalid table name") && op == "pgvector-store" =>
    }
    PgVectorStore.validateTableName("1table") should matchPattern {
      case Left(ProcessingError(msg, op, _)) if msg.contains("Invalid table name") && op == "pgvector-store" =>
    }
  }
}
