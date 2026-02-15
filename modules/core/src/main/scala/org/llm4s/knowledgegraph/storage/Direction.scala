package org.llm4s.knowledgegraph.storage

sealed trait Direction
object Direction {
  case object Outgoing extends Direction
  case object Incoming extends Direction
  case object Both     extends Direction
}
