package org.llm4s.toolapi

import org.llm4s.shared.ToolFunction
import org.llm4s.workspace.ContainerisedWorkspace
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class WorkspaceToolsSpec extends AnyFlatSpec with Matchers {
  // we don't need a real workspace for schema tests
  private val dummy = new ContainerisedWorkspace("/does/not/matter", "", 0)

  "Read tool schema" should "describe lines as 0-indexed" in {
    val result = WorkspaceTools.createReadTool(dummy)((params, ws) => Right(ujson.Obj()))
    result.isRight shouldBe true
    val tool      = result.toOption.get.asInstanceOf[ToolFunction[Map[String, Any], ujson.Value]]
    val schemaStr = tool.schema.toString
    schemaStr should include("0-indexed")
  }

  "Modify tool schema" should "describe operations with 0-indexed line numbers" in {
    val result = WorkspaceTools.createModifyTool(dummy)((params, ws) => Right(ujson.Obj()))
    result.isRight shouldBe true
    val tool      = result.toOption.get.asInstanceOf[ToolFunction[Map[String, Any], ujson.Value]]
    val schemaStr = tool.schema.toString
    schemaStr should include("0-indexed")
  }
}
