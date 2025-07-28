package org.llm4s.samples.speech

import org.llm4s.llmconnect.LLM
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.LLMProvider
import org.llm4s.speech._
import org.llm4s.speech.config.OpenAISpeechConfig
import org.llm4s.speech.model._
import org.llm4s.speech.provider.SpeechProvider
import org.slf4j.LoggerFactory

import java.io.{File, FileOutputStream}
import java.nio.file.{Files, Paths}
import scala.io.StdIn

/**
 * Voice Assistant Example - Demonstrates integration of TTS, ASR, and LLM
 * 
 * This example shows how to create a voice assistant that:
 * 1. Listens to user speech (ASR)
 * 2. Processes the speech with an LLM
 * 3. Converts the LLM response to speech (TTS)
 * 4. Plays the response
 */
object VoiceAssistantExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("Starting Voice Assistant Example")

    // Initialize speech clients
    val speechConfig = OpenAISpeechConfig.fromEnv("tts-1")
    val ttsClient = Speech.ttsClient(SpeechProvider.OpenAI, speechConfig)
    val asrClient = Speech.asrClient(SpeechProvider.OpenAI, speechConfig)

    // Initialize LLM client
    val llmConfig = OpenAIConfig.fromEnv("gpt-4o")
    val llmClient = LLM.client(LLMProvider.OpenAI, llmConfig)

    // System prompt for the voice assistant
    val systemPrompt = SystemMessage(
      """You are a helpful voice assistant. Keep your responses concise and natural for speech.
        |Respond as if you're having a conversation with someone speaking to you.
        |Keep responses under 2-3 sentences for better voice interaction.""".stripMargin
    )

    logger.info("Voice Assistant initialized. Type 'quit' to exit.")

    // Main conversation loop
    var conversation = Conversation(Seq(systemPrompt))
    var running = true

    while (running) {
      print("You: ")
      val userInput = StdIn.readLine()

      if (userInput == null || userInput.toLowerCase == "quit") {
        running = false
      } else {
        // Add user message to conversation
        val userMessage = UserMessage(userInput)
        conversation = conversation.addMessage(userMessage)

        // Get LLM response
        val llmResponse = llmClient.complete(conversation, CompletionOptions())
        
        llmResponse match {
          case Right(completion) =>
            val assistantMessage = completion.message
            conversation = conversation.addMessage(assistantMessage)
            
            val responseText = assistantMessage.content
            logger.info(s"Assistant: $responseText")

            // Convert response to speech
            val ttsOptions = TTSSynthesisOptions(
              voice = "alloy",
              model = "tts-1",
              responseFormat = "mp3",
              speed = 1.0
            )

            ttsClient.synthesize(responseText, ttsOptions) match {
              case Right(audioResponse) =>
                logger.info(s"Generated speech: ${audioResponse.audioData.length} bytes")
                saveAudioToFile(audioResponse.audioData, "voice_assistant_response.mp3")
                logger.info("Audio saved to voice_assistant_response.mp3")
                
              case Left(error) =>
                logger.error(s"TTS failed: ${error.message}")
            }

          case Left(error) =>
            logger.error(s"LLM failed: ${error.message}")
        }
      }
    }

    logger.info("Voice Assistant stopped")
  }

  /**
   * Simulate voice input by reading from an audio file
   */
  def simulateVoiceInput(audioFilePath: String): Option[String] = {
    try {
      val audioFile = new File(audioFilePath)
      if (audioFile.exists()) {
        val audioData = Files.readAllBytes(audioFile.toPath)
        
        val asrClient = Speech.asrClient()
        val options = ASRTranscriptionOptions(
          model = "whisper-1",
          language = Some("en"),
          responseFormat = "json"
        )
        
        asrClient.transcribe(audioData, options) match {
          case Right(transcription) =>
            logger.info(s"Transcribed: ${transcription.text}")
            Some(transcription.text)
            
          case Left(error) =>
            logger.error(s"ASR failed: ${error.message}")
            None
        }
      } else {
        logger.warn(s"Audio file not found: $audioFilePath")
        None
      }
    } catch {
      case e: Exception =>
        logger.error(s"Failed to process audio file: ${e.getMessage}")
        None
    }
  }

  /**
   * Process audio file and get LLM response
   */
  def processAudioFile(audioFilePath: String): Unit = {
    logger.info(s"Processing audio file: $audioFilePath")

    // Step 1: Transcribe audio to text
    val transcribedText = simulateVoiceInput(audioFilePath)
    
    transcribedText.foreach { text =>
      // Step 2: Get LLM response
      val llmConfig = OpenAIConfig.fromEnv("gpt-4o")
      val llmClient = LLM.client(LLMProvider.OpenAI, llmConfig)
      
      val systemPrompt = SystemMessage(
        "You are a helpful voice assistant. Keep your responses concise and natural for speech."
      )
      val userMessage = UserMessage(text)
      val conversation = Conversation(Seq(systemPrompt, userMessage))
      
      val llmResponse = llmClient.complete(conversation, CompletionOptions())
      
      llmResponse match {
        case Right(completion) =>
          val responseText = completion.message.content
          logger.info(s"LLM Response: $responseText")

          // Step 3: Convert response to speech
          val ttsClient = Speech.ttsClient()
          val ttsOptions = TTSSynthesisOptions(
            voice = "alloy",
            model = "tts-1",
            responseFormat = "mp3",
            speed = 1.0
          )

          ttsClient.synthesize(responseText, ttsOptions) match {
            case Right(audioResponse) =>
              logger.info(s"Generated speech: ${audioResponse.audioData.length} bytes")
              saveAudioToFile(audioResponse.audioData, "voice_response.mp3")
              logger.info("Audio saved to voice_response.mp3")
              
            case Left(error) =>
              logger.error(s"TTS failed: ${error.message}")
          }

        case Left(error) =>
          logger.error(s"LLM failed: ${error.message}")
      }
    }
  }

  private def saveAudioToFile(audioData: Array[Byte], filename: String): Unit = {
    try {
      val file = new File(filename)
      val fos = new FileOutputStream(file)
      fos.write(audioData)
      fos.close()
      logger.info(s"Audio saved to: ${file.getAbsolutePath}")
    } catch {
      case e: Exception =>
        logger.error(s"Failed to save audio file: ${e.getMessage}")
    }
  }
}