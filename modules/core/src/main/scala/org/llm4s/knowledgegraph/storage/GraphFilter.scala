package org.llm4s.knowledgegraph.storage

case class GraphFilter(
  label: Option[String] = None,
  relationship: Option[String] = None,
  propertyKey: Option[String] = None,
  propertyValue: Option[String] = None
)
