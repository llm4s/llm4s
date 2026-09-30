package org.llm4s.assistant

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import fansi._
import cats.Show

/**
 * Tests for ConsoleInterface types (MessageType, ConsoleConfig)
 */
class ConsoleInterfaceSpec extends AnyFlatSpec with Matchers {

  // ============ MessageType ============

  "MessageType" should "have Info type" in {
    MessageType.Info shouldBe a[MessageType]
  }

  it should "have Success type" in {
    MessageType.Success shouldBe a[MessageType]
  }

  it should "have Warning type" in {
    MessageType.Warning shouldBe a[MessageType]
  }

  it should "have Error type" in {
    MessageType.Error shouldBe a[MessageType]
  }

  it should "have AssistantResponse type" in {
    MessageType.AssistantResponse shouldBe a[MessageType]
  }

  it should "have Show instance" in {
    import MessageType.showMessageType

    Show[MessageType].show(MessageType.Info) shouldBe "Info"
    Show[MessageType].show(MessageType.Success) shouldBe "Success"
    Show[MessageType].show(MessageType.Warning) shouldBe "Warning"
    Show[MessageType].show(MessageType.Error) shouldBe "Error"
    Show[MessageType].show(MessageType.AssistantResponse) shouldBe "Assistant"
  }

  // ============ ConsoleConfig ============

  "ConsoleConfig" should "have sensible defaults" in {
    val config = ConsoleConfig()

    config.promptSymbol shouldBe "User> "
    config.assistantSymbol shouldBe "Assistant> "
    config.colorScheme should not be empty
  }

  it should "have color scheme for all message types" in {
    val config = ConsoleConfig()

    config.colorScheme.get(MessageType.Info) shouldBe defined
    config.colorScheme.get(MessageType.Success) shouldBe defined
    config.colorScheme.get(MessageType.Warning) shouldBe defined
    config.colorScheme.get(MessageType.Error) shouldBe defined
    config.colorScheme.get(MessageType.AssistantResponse) shouldBe defined
  }

  it should "allow custom prompt symbols" in {
    val config = ConsoleConfig(
      promptSymbol = ">>> ",
      assistantSymbol = "AI: "
    )

    config.promptSymbol shouldBe ">>> "
    config.assistantSymbol shouldBe "AI: "
  }

  it should "allow custom color scheme" in {
    val customScheme: Map[MessageType, Attrs] = Map(
      MessageType.Info              -> Color.White,
      MessageType.Success           -> Color.Cyan,
      MessageType.Warning           -> Color.Magenta,
      MessageType.Error             -> Color.Red,
      MessageType.AssistantResponse -> Color.Green
    )
    val config = ConsoleConfig(colorScheme = customScheme)

    config.colorScheme shouldBe customScheme
  }

  // ============ ConsoleConfig.StyleConfig ============

  "ConsoleConfig.StyleConfig" should "have sensible defaults" in {
    val styles = ConsoleConfig.StyleConfig()

    styles.prompt should not be null
    styles.highlight should not be null
    styles.title should not be null
    styles.command should not be null
    styles.bold should not be null
  }

  it should "allow custom styles" in {
    val customStyles = ConsoleConfig.StyleConfig(
      prompt = Color.Red,
      highlight = Bold.On ++ Color.Yellow,
      title = Color.Blue,
      command = Color.Green,
      bold = Bold.On
    )

    customStyles.prompt shouldBe Color.Red
    customStyles.title shouldBe Color.Blue
  }

  it should "be included in ConsoleConfig" in {
    val config = ConsoleConfig()

    config.styles should not be null
    config.styles.prompt should not be null
  }

  it should "allow custom styles in ConsoleConfig" in {
    val customStyles = ConsoleConfig.StyleConfig(prompt = Color.Magenta)
    val config       = ConsoleConfig(styles = customStyles)

    config.styles.prompt shouldBe Color.Magenta
  }

  // ============ ShowInstances ============

  "ShowInstances.showAssistantError" should "format IO errors" in {
    import ShowInstances.showAssistantError
    import org.llm4s.error.AssistantError

    val error = AssistantError.IOError("Failed to read", "read", None)
    Show[AssistantError].show(error) shouldBe "IO Error: Failed to read"
  }

  it should "format EOF errors" in {
    import ShowInstances.showAssistantError
    import org.llm4s.error.AssistantError

    val error = AssistantError.EOFError("Unexpected EOF", "read")
    Show[AssistantError].show(error) shouldBe "EOF Error: Unexpected EOF"
  }

  it should "format Display errors" in {
    import ShowInstances.showAssistantError
    import org.llm4s.error.AssistantError

    val error = AssistantError.DisplayError("Display failed", "render", None)
    Show[AssistantError].show(error) shouldBe "Display Error: Display failed"
  }

  it should "format other errors using message" in {
    import ShowInstances.showAssistantError
    import org.llm4s.error.AssistantError
    import org.llm4s.types.SessionId

    val error = AssistantError.SessionError("Session problem", SessionId("test"), "save")
    Show[AssistantError].show(error) should include("Session problem")
  }

  "ShowInstances.showLLMError" should "format LLM errors" in {
    import ShowInstances.showLLMError
    import org.llm4s.error.SimpleError

    val error = SimpleError("Test error")

    Show[org.llm4s.error.LLMError].show(error) should include("Test error")
  }
}
