// Probe A: build a Conversation from messages and read the Result (Either) from Scala 2.13.
import org.llm4s.llmconnect.model.{AssistantMessage, Conversation, SystemMessage, UserMessage}

object Probe extends App {
  val conversation = Conversation.create(
    SystemMessage("You are terse."),
    UserMessage("What is Scala?"),
    AssistantMessage("A JVM language."),
    UserMessage("And llm4s?")
  )
  conversation match {
    case Right(c)    => println(s"PASS: ${c.messages.size} messages, last: ${c.messages.last.content}")
    case Left(error) => println(s"FAIL: ${error.message}")
  }
}
