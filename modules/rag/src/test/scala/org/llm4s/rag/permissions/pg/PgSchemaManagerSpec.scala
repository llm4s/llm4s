package org.llm4s.rag.permissions.pg

import org.llm4s.error.ProcessingError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PgSchemaManagerSpec extends AnyFlatSpec with Matchers {

  "PgSchemaManager.extendVectorsTable" should "reject an invalid table name before touching the connection" in {
    // A null connection proves no SQL is attempted: the name is interpolated into DDL.
    PgSchemaManager.extendVectorsTable(null, "vectors; DROP TABLE users") match {
      case Left(e: ProcessingError) => e.message should include("Invalid table name")
      case other                    => fail(s"Expected a ProcessingError, got $other")
    }
  }
}
