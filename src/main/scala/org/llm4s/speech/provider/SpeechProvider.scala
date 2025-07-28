package org.llm4s.speech.provider

sealed trait SpeechProvider
object SpeechProvider {
  case object OpenAI     extends SpeechProvider
  case object Azure      extends SpeechProvider
  case object Google     extends SpeechProvider
  case object Amazon     extends SpeechProvider
  case object ElevenLabs extends SpeechProvider
}
