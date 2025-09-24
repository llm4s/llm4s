package org.llm4s.shared

import upickle.default._

object ProtocolCodec {

  def decodeAgentCommand(json: String): WorkspaceAgentCommand =
    read[WorkspaceAgentCommand](json)

  def encodeAgentCommand(command: WorkspaceAgentCommand): String =
    write(command)

  def decodeAgentResponse(json: String): WorkspaceAgentResponse =
    read[WorkspaceAgentResponse](json)

  def encodeAgentResponse(response: WorkspaceAgentResponse): String =
    write(response)
}
