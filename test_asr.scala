import org.llm4s.speech._
import org.llm4s.speech.config.OpenAISpeechConfig
import org.llm4s.speech.model._
import org.llm4s.speech.provider._

object TestASR {
  def main(args: Array[String]): Unit = {
    println("Testing ASR functionality with OpenAI...")
    
    try {
      // Create config from environment
      val config = OpenAISpeechConfig.fromEnv("whisper-1")
      println(s"✓ Config created successfully")
      
      // Create ASR client
      val asrClient = Speech.asrClient(SpeechProvider.OpenAI, config)
      println(s"✓ ASR client created successfully")
      
      // Test with a sample audio file (you would need to provide one)
      val audioFile = new java.io.File("sample_audio.mp3")
      
      if (audioFile.exists()) {
        val audioData = java.nio.file.Files.readAllBytes(audioFile.toPath)
        println(s"✓ Loaded audio file: ${audioData.length} bytes")
        
        val options = ASRTranscriptionOptions(
          model = "whisper-1",
          language = Some("en"),
          responseFormat = "json"
        )
        
        println("Attempting to transcribe audio...")
        val result = asrClient.transcribe(audioData, options)
        
        result match {
          case Right(transcription) =>
            println(s"✓ ASR Success!")
            println(s"✓ Transcribed text: '${transcription.text}'")
            println(s"✓ Language: ${transcription.language.getOrElse("auto-detected")}")
            println(s"✓ Segments: ${transcription.segments.length}")
            
          case Left(error) =>
            println(s"✗ ASR Failed: ${error.message}")
        }
      } else {
        println("✗ No sample audio file found (sample_audio.mp3)")
        println("To test ASR, you need to provide an audio file")
        println("You can record a short audio clip and save it as 'sample_audio.mp3'")
      }
      
    } catch {
      case e: Exception =>
        println(s"✗ Setup failed: ${e.getMessage}")
        println("Make sure you have set OPENAI_API_KEY environment variable")
    }
  }
}