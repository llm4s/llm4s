package org.llm4s.samples.dashboard.providersetup

import org.llm4s.samples.dashboard.providersetup.ProviderSetupModel.*
import termflow.tui.KeyDecoder

object ProviderSetupMessages:

  enum GlobalMsg:
    case NoOp
    case Tick
    case Resize(width: Int, height: Int)
    case ConsoleInputError(error: Throwable)
    case RefreshStatus
    case StatusRefreshed(configStatus: ConfigStatus)
    case DemoChunkReceived(chunk: String)
    case DemoResponseReceived(result: Either[String, String])
    case CompareResponseReceived(providerName: String, latencyMs: Long, result: Either[String, String])
    case ExitRequested

  enum SetupMsg:
    case ConsoleInputKey(key: KeyDecoder.InputKey)
    case RunCommand(command: String)

  enum DemoMsg:
    case ConsoleInputKey(key: KeyDecoder.InputKey)
    case RunCommand(command: String)

  enum CompareMsg:
    case ConsoleInputKey(key: KeyDecoder.InputKey)
    case RunCommand(command: String)

  enum Msg:
    case Global(value: GlobalMsg)
    case Setup(value: SetupMsg)
    case Demo(value: DemoMsg)
    case Compare(value: CompareMsg)
