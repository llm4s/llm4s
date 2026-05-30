package org.llm4s.samples.chat.tui

import org.llm4s.samples.chat.tui.ChatTuiModel.{ Model, Msg }
import termflow.tui.Theme
import termflow.tui.TermFlowError
import termflow.tui.TuiPrelude.*

/** Slash-command parsing. Lives in its own object to keep the update layer readable. */
private[tui] object ChatTuiCommands:

  def toMsg(model: Model)(input: PromptLine): Result[Msg] =
    val raw = input.value.trim
    raw match {
      case ""                => Right(Msg.NoOp)
      case "/quit" | "/exit" => Right(Msg.Quit)
      case "/clear"          => Right(Msg.ClearConversation)
      case "/help"           => Right(Msg.AppendHelpEntry)
      case "/tools"          => Right(Msg.AppendToolsEntry)
      case s if s.startsWith("/model ") =>
        val name = s.stripPrefix("/model ").trim
        if model.config.allowedModels.contains(name) then Right(Msg.SetModel(name))
        else Left(TermFlowError.Validation(s"Unknown model: $name"))
      case "/theme"       => Right(Msg.ToggleTheme)
      case "/theme dark"  => Right(Msg.SetTheme(Theme.dark))
      case "/theme light" => Right(Msg.SetTheme(Theme.light))
      case s if s.startsWith("/theme ") =>
        Left(TermFlowError.Validation(s"Unknown theme: ${s.stripPrefix("/theme ").trim}"))
      case s if s.startsWith("/system ") =>
        val prompt = s.stripPrefix("/system ").trim
        if prompt.isEmpty then Left(TermFlowError.Validation("/system requires a prompt"))
        else Right(Msg.SetSystem(prompt))
      case s if s.startsWith("/") =>
        Left(TermFlowError.CommandError(s))
      case other =>
        Right(Msg.Submit(other))
    }
