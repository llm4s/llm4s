package org.llm4s.sc2

import org.llm4s.toolapi.builtin.BuiltinTools
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cross-version test for BuiltinTools factory methods and tool set composition.
 * Verifies that coreSafe, withHttpSafe(), withFilesSafe(), and developmentSafe() behave identically in Scala 2.13 and 3.x.
 * No network, no filesystem I/O — construction and discovery only.
 * Same logic as sc3.BuiltinToolsCrossTest; positive assertions only (no negative cases for factory methods).
 */
class BuiltinToolsCrossTest extends AnyFlatSpec with Matchers {

  val coreToolNames = Set("get_current_datetime", "calculator", "generate_uuid", "json_tool")
  val fileReadNames = Set("read_file", "list_directory", "file_info")

  "BuiltinTools.coreSafe" should "return a non-empty sequence of tools" in {
    val tools = BuiltinTools.coreSafe.getOrElse(fail("coreSafe failed"))
    tools should not be empty
  }

  it should "include expected core tool names" in {
    val names = BuiltinTools.coreSafe.getOrElse(fail("coreSafe failed")).map(_.name).toSet
    coreToolNames.foreach { name =>
      names should contain(name)
    }
  }

  it should "have exactly four tools" in {
    BuiltinTools.coreSafe.getOrElse(fail("coreSafe failed")).size shouldBe 4
  }

  "BuiltinTools.withHttpSafe()" should "include all core tools" in {
    val names = BuiltinTools.withHttpSafe().getOrElse(fail("withHttpSafe failed")).map(_.name).toSet
    coreToolNames.foreach { name =>
      names should contain(name)
    }
  }

  it should "include http_request" in {
    val names = BuiltinTools.withHttpSafe().getOrElse(fail("withHttpSafe failed")).map(_.name).toSet
    names should contain("http_request")
  }

  it should "not include write_file or shell_command" in {
    val names = BuiltinTools.withHttpSafe().getOrElse(fail("withHttpSafe failed")).map(_.name).toSet
    names should not contain "write_file"
    names should not contain "shell_command"
  }

  it should "have more tools than coreSafe" in {
    val coreSize = BuiltinTools.coreSafe.getOrElse(fail("coreSafe failed")).size
    val safeSize = BuiltinTools.withHttpSafe().getOrElse(fail("withHttpSafe failed")).size
    safeSize should be > coreSize
  }

  "BuiltinTools.withFilesSafe()" should "include all withHttpSafe tools" in {
    val safeNames      = BuiltinTools.withHttpSafe().getOrElse(fail("withHttpSafe failed")).map(_.name).toSet
    val withFilesNames = BuiltinTools.withFilesSafe().getOrElse(fail("withFilesSafe failed")).map(_.name).toSet
    safeNames.foreach { name =>
      withFilesNames should contain(name)
    }
  }

  it should "include read-only file tools" in {
    val names = BuiltinTools.withFilesSafe().getOrElse(fail("withFilesSafe failed")).map(_.name).toSet
    fileReadNames.foreach { name =>
      names should contain(name)
    }
  }

  it should "not include write_file or shell_command" in {
    val names = BuiltinTools.withFilesSafe().getOrElse(fail("withFilesSafe failed")).map(_.name).toSet
    names should not contain "write_file"
    names should not contain "shell_command"
  }

  "BuiltinTools.developmentSafe()" should "include core tools" in {
    val names = BuiltinTools.developmentSafe().getOrElse(fail("developmentSafe failed")).map(_.name).toSet
    coreToolNames.foreach { name =>
      names should contain(name)
    }
  }

  it should "include write_file and shell_command" in {
    val names = BuiltinTools.developmentSafe().getOrElse(fail("developmentSafe failed")).map(_.name).toSet
    names should contain("write_file")
    names should contain("shell_command")
  }

  it should "include read-only file tools" in {
    val names = BuiltinTools.developmentSafe().getOrElse(fail("developmentSafe failed")).map(_.name).toSet
    fileReadNames.foreach { name =>
      names should contain(name)
    }
  }

  "Each tool" should "have a non-empty name and description" in {
    BuiltinTools.coreSafe.getOrElse(fail("coreSafe failed")).foreach { tool =>
      tool.name should not be empty
      tool.description should not be empty
    }
  }
}
