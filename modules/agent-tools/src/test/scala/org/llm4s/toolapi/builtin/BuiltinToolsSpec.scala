package org.llm4s.toolapi.builtin

import org.llm4s.toolapi.tools.WeatherTool
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BuiltinToolsSpec extends AnyFlatSpec with Matchers {

  "BuiltinTools.core" should "include all core utilities" in {
    BuiltinTools.coreSafe.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tools => {
        tools.size shouldBe 4
        (tools.map(_.name) should contain).allOf(
          "get_current_datetime",
          "calculator",
          "generate_uuid",
          "json_tool"
        )
      }
    )
  }

  "BuiltinTools.safe" should "include core tools plus safe network tools" in {
    BuiltinTools
      .withHttpSafe()
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tools => {
          tools.size shouldBe 5 // 4 core + http
          (tools.map(_.name) should contain).allOf(
            "get_current_datetime",
            "calculator",
            "generate_uuid",
            "json_tool",
            "http_request"
          )
        }
      )
  }

  it should "not include shell or file tools" in {
    BuiltinTools
      .withHttpSafe()
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tools => {
          val toolNames = tools.map(_.name)
          toolNames should not contain "shell_command"
          toolNames should not contain "read_file"
          toolNames should not contain "write_file"
          toolNames should not contain "list_directory"
          toolNames should not contain "file_info"
        }
      )
  }

  "BuiltinTools.withFiles" should "include file system tools" in {
    BuiltinTools
      .withFilesSafe()
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tools => {
          val toolNames = tools.map(_.name)
          toolNames should contain("read_file")
          toolNames should contain("list_directory")
          toolNames should contain("file_info")
        }
      )
  }

  it should "not include write or shell tools" in {
    BuiltinTools
      .withFilesSafe()
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tools => {
          val toolNames = tools.map(_.name)
          toolNames should not contain "write_file"
          toolNames should not contain "shell_command"
        }
      )
  }

  "BuiltinTools.development" should "include all tools" in {
    BuiltinTools
      .developmentSafe()
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tools => {
          val toolNames = tools.map(_.name)

          // Core tools
          toolNames should contain("get_current_datetime")
          toolNames should contain("calculator")
          toolNames should contain("generate_uuid")
          toolNames should contain("json_tool")

          // Network tools
          toolNames should contain("http_request")

          // File tools
          toolNames should contain("read_file")
          toolNames should contain("list_directory")
          toolNames should contain("file_info")
          toolNames should contain("write_file")

          // Shell
          toolNames should contain("shell_command")
        }
      )
  }

  "BuiltinTools.custom" should "allow selective tool inclusion" in {
    // Only core, nothing else
    BuiltinTools
      .customSafe()
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tools => {
          tools.size shouldBe 4 // Only core
          tools.map(_.name) should not contain "http_request"
          tools.map(_.name) should not contain "read_file"
        }
      )
  }

  it should "include HTTP when configured" in {
    BuiltinTools
      .customSafe(
        httpConfig = Some(http.HttpConfig())
      )
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tools => tools.map(_.name) should contain("http_request")
      )
  }

  it should "include files when configured" in {
    BuiltinTools
      .customSafe(
        fileConfig = Some(filesystem.FileConfig())
      )
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tools => {
          (tools.map(_.name) should contain).allOf("read_file", "list_directory", "file_info")
          tools.map(_.name) should not contain "write_file"
        }
      )
  }

  it should "include write when configured" in {
    BuiltinTools
      .customSafe(
        writeConfig = Some(filesystem.WriteConfig(allowedPaths = Seq("/tmp")))
      )
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tools => tools.map(_.name) should contain("write_file")
      )
  }

  it should "include shell when configured" in {
    BuiltinTools
      .customSafe(
        shellConfig = Some(shell.ShellConfig.readOnly())
      )
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tools => tools.map(_.name) should contain("shell_command")
      )
  }

  "WeatherTool.toolSafe" should "return a valid tool" in {
    WeatherTool.toolSafe.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => tool.name shouldBe "get_weather"
    )
  }
}
