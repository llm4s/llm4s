package org.llm4s.samples.chat.tui

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, StreamedChunk }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import termflow.tui.{ InMemoryHistoryStore, PromptHistory, TermFlowError, Theme }
import termflow.tui.TuiPrelude.*

import java.nio.file.Paths

class ChatTuiCommandsSpec extends AnyFlatSpec with Matchers {

  private val stubConfig = ChatTuiConfig(
    providerConfig = OpenAIConfig(
      apiKey = "test",
      model = "gpt-4o-mini",
      organization = None,
      baseUrl = "https://api.openai.com/v1",
      contextWindow = 4096,
      reserveCompletion = 1024
    ),
    modelRegistry = ModelRegistryService.fromModels(Iterable.empty),
    modelName = "openai/gpt-4o-mini",
    systemPrompt = "test",
    workspaceRoot = Paths.get("."),
    allowedModels = Vector("openai/gpt-4o-mini", "anthropic/claude-3-5-haiku-latest")
  )

  private val stubModel = ChatTuiModel.Model(
    width = 80,
    height = 24,
    config = stubConfig,
    client = NoopClient,
    conversation = Conversation(Seq.empty),
    entries = Vector.empty,
    scrollOffset = 0,
    autoTail = true,
    prompt = PromptHistory.initial(InMemoryHistoryStore(maxEntries = 10)),
    pending = PendingState.Idle,
    theme = Theme.dark,
    status = "",
    session = new ChatTuiModel.StreamSession()
  )

  "toMsg" should "treat empty input as NoOp" in {
    ChatTuiCommands.toMsg(stubModel)(PromptLine("")) shouldBe Right(ChatTuiModel.Msg.NoOp)
  }

  it should "route /help to AppendHelpEntry" in {
    ChatTuiCommands.toMsg(stubModel)(PromptLine("/help")) shouldBe Right(ChatTuiModel.Msg.AppendHelpEntry)
  }

  it should "route /tools to AppendToolsEntry" in {
    ChatTuiCommands.toMsg(stubModel)(PromptLine("/tools")) shouldBe Right(ChatTuiModel.Msg.AppendToolsEntry)
  }

  it should "route /clear to ClearConversation" in {
    ChatTuiCommands.toMsg(stubModel)(PromptLine("/clear")) shouldBe Right(ChatTuiModel.Msg.ClearConversation)
  }

  it should "route /quit to Quit" in {
    ChatTuiCommands.toMsg(stubModel)(PromptLine("/quit")) shouldBe Right(ChatTuiModel.Msg.Quit)
  }

  it should "accept /model when in the allow-list" in {
    ChatTuiCommands.toMsg(stubModel)(PromptLine("/model anthropic/claude-3-5-haiku-latest")) shouldBe
      Right(ChatTuiModel.Msg.SetModel("anthropic/claude-3-5-haiku-latest"))
  }

  it should "reject /model when not in the allow-list" in {
    ChatTuiCommands.toMsg(stubModel)(PromptLine("/model not-on-list")) match {
      case Left(TermFlowError.Validation(msg)) => msg should include("Unknown model")
      case other                               => fail(s"expected Validation, got $other")
    }
  }

  it should "toggle theme on bare /theme" in {
    ChatTuiCommands.toMsg(stubModel)(PromptLine("/theme")) shouldBe Right(ChatTuiModel.Msg.ToggleTheme)
  }

  it should "set theme on /theme dark / /theme light" in {
    ChatTuiCommands.toMsg(stubModel)(PromptLine("/theme dark")) shouldBe Right(ChatTuiModel.Msg.SetTheme(Theme.dark))
    ChatTuiCommands.toMsg(stubModel)(PromptLine("/theme light")) shouldBe Right(ChatTuiModel.Msg.SetTheme(Theme.light))
  }

  it should "reject /theme other" in {
    ChatTuiCommands.toMsg(stubModel)(PromptLine("/theme neon")) match {
      case Left(TermFlowError.Validation(msg)) => msg should include("Unknown theme")
      case other                               => fail(s"expected Validation, got $other")
    }
  }

  it should "set the system prompt on /system <text>" in {
    ChatTuiCommands.toMsg(stubModel)(PromptLine("/system You are concise.")) shouldBe
      Right(ChatTuiModel.Msg.SetSystem("You are concise."))
  }

  it should "fall through to Submit for ordinary text" in {
    ChatTuiCommands.toMsg(stubModel)(PromptLine("hello there")) shouldBe Right(ChatTuiModel.Msg.Submit("hello there"))
  }

  it should "raise CommandError for an unknown slash command" in {
    ChatTuiCommands.toMsg(stubModel)(PromptLine("/bogus")) match {
      case Left(TermFlowError.CommandError(input)) => input shouldBe "/bogus"
      case other                                   => fail(s"expected CommandError, got $other")
    }
  }

  /** A no-op LLMClient that we never invoke in these tests. */
  private object NoopClient extends LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      throw new UnsupportedOperationException("test stub")
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      throw new UnsupportedOperationException("test stub")
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }
}
