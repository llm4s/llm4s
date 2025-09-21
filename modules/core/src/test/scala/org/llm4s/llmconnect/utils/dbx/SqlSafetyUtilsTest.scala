package org.llm4s.llmconnect.utils.dbx

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.llm4s.llmconnect.model.dbx.SchemaError

class SqlSafetyUtilsTest extends AnyWordSpec with Matchers {

  "SqlSafetyUtils" should {

    "validateIdentifier" should {
      "accept valid SQL identifiers" in {
        SqlSafetyUtils.validateIdentifier("users") shouldBe Right("users")
        SqlSafetyUtils.validateIdentifier("user_profiles") shouldBe Right("user_profiles")
        SqlSafetyUtils.validateIdentifier("_private") shouldBe Right("_private")
        SqlSafetyUtils.validateIdentifier("table123") shouldBe Right("table123")
        SqlSafetyUtils.validateIdentifier("my$table") shouldBe Right("my$table")
      }

      "reject invalid SQL identifiers" in {
        SqlSafetyUtils.validateIdentifier("123table") shouldBe a[Left[_, _]]
        SqlSafetyUtils.validateIdentifier("table-name") shouldBe a[Left[_, _]]
        SqlSafetyUtils.validateIdentifier("table name") shouldBe a[Left[_, _]]
        SqlSafetyUtils.validateIdentifier("table;drop") shouldBe a[Left[_, _]]
        SqlSafetyUtils.validateIdentifier("table'name") shouldBe a[Left[_, _]]
        SqlSafetyUtils.validateIdentifier("table\"name") shouldBe a[Left[_, _]]
      }

      "reject null or empty identifiers" in {
        SqlSafetyUtils.validateIdentifier(null) shouldBe a[Left[_, _]]
        SqlSafetyUtils.validateIdentifier("") shouldBe a[Left[_, _]]
        SqlSafetyUtils.validateIdentifier("  ") shouldBe a[Left[_, _]]
      }

      "reject identifiers that are too long" in {
        val longIdentifier = "a" * 64 // 64 characters, exceeds 63 limit
        SqlSafetyUtils.validateIdentifier(longIdentifier) shouldBe a[Left[_, _]]
      }
    }

    "validateIdentifiers" should {
      "accept multiple valid identifiers" in {
        SqlSafetyUtils.validateIdentifiers("schema1", "table1", "column1") shouldBe Right(())
      }

      "reject if any identifier is invalid" in {
        val result = SqlSafetyUtils.validateIdentifiers("valid_schema", "invalid-table", "valid_column")
        result shouldBe a[Left[_, _]]
        result.swap.getOrElse(throw new Exception("Expected Left")) shouldBe a[SchemaError]
      }
    }

    "quoteIdentifier" should {
      "wrap identifier in double quotes" in {
        SqlSafetyUtils.quoteIdentifier("users") shouldBe "\"users\""
      }

      "escape internal quotes" in {
        SqlSafetyUtils.quoteIdentifier("user\"s") shouldBe "\"user\"\"s\""
      }
    }

    "qualifiedTableName" should {
      "create properly quoted schema.table name" in {
        SqlSafetyUtils.qualifiedTableName("public", "users") shouldBe Right("\"public\".\"users\"")
        SqlSafetyUtils.qualifiedTableName("my_schema", "my_table") shouldBe Right("\"my_schema\".\"my_table\"")
      }

      "reject invalid schema names" in {
        SqlSafetyUtils.qualifiedTableName("invalid-schema", "users") shouldBe a[Left[_, _]]
      }

      "reject invalid table names" in {
        SqlSafetyUtils.qualifiedTableName("public", "invalid table") shouldBe a[Left[_, _]]
      }

      "prevent SQL injection attempts" in {
        SqlSafetyUtils.qualifiedTableName("public", "users; DROP TABLE secrets") shouldBe a[Left[_, _]]
        SqlSafetyUtils.qualifiedTableName("public'; DROP SCHEMA public CASCADE;--", "users") shouldBe a[Left[_, _]]
      }
    }
  }
}
