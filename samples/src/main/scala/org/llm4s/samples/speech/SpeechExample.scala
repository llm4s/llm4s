package org.llm4s.samples.speech

import org.llm4s.speech._
import org.llm4s.speech.config.{AzureSpeechConfig, ElevenLabsConfig, GoogleSpeechConfig, OpenAISpeechConfig}
import org.llm4s.speech.model._
import org.llm4s.speech.provider._
import org.slf4j.LoggerFactory

import java.io.{File, FileOutputStream}
import java.nio.file.{Files, Paths}

/**
 * Example demonstrating TTS and ASR functionality with different providers
 */
object SpeechExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("Starting Speech TTS/ASR Example")

    // Example 1: OpenAI TTS
    openAITTSExample()

    // Example 2: OpenAI ASR (if you have an audio file)
    // openAIASRExample()

    // Example 3: Azure Speech TTS
    azureTTSSExample()

    // Example 4: Google Speech TTS
    googleTTSSExample()

    // Example 5: ElevenLabs TTS
    elevenLabsTTSExample()

    logger.info("Speech TTS/ASR Example completed")
  }

  def openAITTSExample(): Unit = {
    logger.info("=== OpenAI TTS Example ===")
    
    try {
      val config = OpenAISpeechConfig.fromEnv("tts-1")
      val ttsClient = Speech.ttsClient(SpeechProvider.OpenAI, config)
      
      val text = "Hello, this is a test of the text-to-speech functionality using OpenAI's TTS API."
      val options = TTSSynthesisOptions(
        voice = "alloy",
        model = "tts-1",
        responseFormat = "mp3",
        speed = 1.0
      )
      
      ttsClient.synthesize(text, options) match {
        case Right(audioResponse) =>
          logger.info(s"Successfully synthesized audio: ${audioResponse.audioData.length} bytes")
          // Save the audio to a file
          saveAudioToFile(audioResponse.audioData, "openai_tts_output.mp3")
          
        case Left(error) =>
          logger.error(s"OpenAI TTS failed: ${error.message}")
      }
    } catch {
      case e: Exception =>
        logger.error(s"OpenAI TTS setup failed: ${e.getMessage}")
    }
  }

  def openAIASRExample(): Unit = {
    logger.info("=== OpenAI ASR Example ===")
    
    try {
      val config = OpenAISpeechConfig.fromEnv("whisper-1")
      val asrClient = Speech.asrClient(SpeechProvider.OpenAI, config)
      
      // Read audio file (you would need to provide an actual audio file)
      val audioFile = new File("sample_audio.mp3")
      if (audioFile.exists()) {
        val audioData = Files.readAllBytes(audioFile.toPath)
        
        val options = ASRTranscriptionOptions(
          model = "whisper-1",
          language = Some("en"),
          responseFormat = "json"
        )
        
        asrClient.transcribe(audioData, options) match {
          case Right(transcription) =>
            logger.info(s"Transcription: ${transcription.text}")
            logger.info(s"Language: ${transcription.language}")
            logger.info(s"Segments: ${transcription.segments.length}")
            
          case Left(error) =>
            logger.error(s"OpenAI ASR failed: ${error.message}")
        }
      } else {
        logger.warn("Sample audio file not found, skipping ASR example")
      }
    } catch {
      case e: Exception =>
        logger.error(s"OpenAI ASR setup failed: ${e.getMessage}")
    }
  }

  def azureTTSSExample(): Unit = {
    logger.info("=== Azure Speech TTS Example ===")
    
    try {
      val config = AzureSpeechConfig.fromEnv("en-US-JennyNeural")
      val ttsClient = Speech.ttsClient(SpeechProvider.Azure, config)
      
      val text = "Hello, this is a test of Azure's text-to-speech service."
      val options = TTSSynthesisOptions(
        voice = "en-US-JennyNeural",
        model = "en-US-JennyNeural",
        responseFormat = "mp3",
        speed = 1.0
      )
      
      ttsClient.synthesize(text, options) match {
        case Right(audioResponse) =>
          logger.info(s"Successfully synthesized audio: ${audioResponse.audioData.length} bytes")
          saveAudioToFile(audioResponse.audioData, "azure_tts_output.mp3")
          
        case Left(error) =>
          logger.error(s"Azure TTS failed: ${error.message}")
      }
    } catch {
      case e: Exception =>
        logger.error(s"Azure TTS setup failed: ${e.getMessage}")
    }
  }

  def googleTTSSExample(): Unit = {
    logger.info("=== Google Speech TTS Example ===")
    
    try {
      val config = GoogleSpeechConfig.fromEnv("latest")
      val ttsClient = Speech.ttsClient(SpeechProvider.Google, config)
      
      val text = "Hello, this is a test of Google's text-to-speech service."
      val options = TTSSynthesisOptions(
        voice = "en-US-Standard-A",
        model = "latest",
        responseFormat = "mp3",
        speed = 1.0
      )
      
      ttsClient.synthesize(text, options) match {
        case Right(audioResponse) =>
          logger.info(s"Successfully synthesized audio: ${audioResponse.audioData.length} bytes")
          saveAudioToFile(audioResponse.audioData, "google_tts_output.mp3")
          
        case Left(error) =>
          logger.error(s"Google TTS failed: ${error.message}")
      }
    } catch {
      case e: Exception =>
        logger.error(s"Google TTS setup failed: ${e.getMessage}")
    }
  }

  def elevenLabsTTSExample(): Unit = {
    logger.info("=== ElevenLabs TTS Example ===")
    
    try {
      val config = ElevenLabsConfig.fromEnv("eleven_monolingual_v1")
      val ttsClient = Speech.ttsClient(SpeechProvider.ElevenLabs, config)
      
      val text = "Hello, this is a test of ElevenLabs text-to-speech service."
      val options = TTSSynthesisOptions(
        voice = "21m00Tcm4TlvDq8ikWAM", // Example voice ID
        model = "eleven_monolingual_v1",
        responseFormat = "mp3",
        speed = 1.0
      )
      
      ttsClient.synthesize(text, options) match {
        case Right(audioResponse) =>
          logger.info(s"Successfully synthesized audio: ${audioResponse.audioData.length} bytes")
          saveAudioToFile(audioResponse.audioData, "elevenlabs_tts_output.mp3")
          
        case Left(error) =>
          logger.error(s"ElevenLabs TTS failed: ${error.message}")
      }
    } catch {
      case e: Exception =>
        logger.error(s"ElevenLabs TTS setup failed: ${e.getMessage}")
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