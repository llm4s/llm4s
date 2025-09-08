package org.llm4s.llmconnect.model

sealed trait Modality { def name: String }
case object Text  extends Modality { val name = "text"  }

