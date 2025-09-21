package org.llm4s.llmconnect.model

sealed trait Modality
case object Text  extends Modality
case object Image extends Modality
case object Audio extends Modality
case object Video extends Modality
