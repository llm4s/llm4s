import org.llm4s.speech._
import org.llm4s.speech.config.ElevenLabsConfig
import org.llm4s.speech.model._
import org.llm4s.speech.provider._

object TestTTS {
  def main(args: Array[String]): Unit = {
    println("Testing TTS functionality with ElevenLabs...")
    
    try {
      // Create config from environment
      val config = ElevenLabsConfig.fromEnv("eleven_monolingual_v1")
      println(s"✓ Config created successfully")
      
      // Create TTS client
      val ttsClient = Speech.ttsClient(SpeechProvider.ElevenLabs, config)
      println(s"✓ TTS client created successfully")
      
      // Test synthesis
      val text = "Hello! This is a test of the text-to-speech functionality."
      val options = TTSSynthesisOptions(
        voice = "21m00Tcm4TlvDq8ikWAM", // Rachel voice (free)
        model = "eleven_monolingual_v1",
        responseFormat = "mp3",
        speed = 1.0
      )
      
      println(s"Attempting to synthesize: '$text'")
      val result = ttsClient.synthesize(text, options)
      
      result match {
        case Right(audioResponse) =>
          println(s"✓ TTS Success! Generated ${audioResponse.audioData.length} bytes of audio")
          println(s"✓ Format: ${audioResponse.format}")
          
          // Save to file
          import java.io.FileOutputStream
          val fos = new FileOutputStream("test_output.mp3")
          fos.write(audioResponse.audioData)
          fos.close()
          println(s"✓ Audio saved to test_output.mp3")
          
        case Left(error) =>
          println(s"✗ TTS Failed: ${error.message}")
      }
      
    } catch {
      case e: Exception =>
        println(s"✗ Setup failed: ${e.getMessage}")
        println("Make sure you have set ELEVENLABS_API_KEY environment variable")
    }
  }
}