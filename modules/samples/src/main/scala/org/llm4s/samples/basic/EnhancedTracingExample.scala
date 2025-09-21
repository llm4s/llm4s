package org.llm4s.samples.basic

import org.llm4s.config.ConfigReader
import org.llm4s.config.ConfigReader.LLMConfig
import org.llm4s.llmconnect.model.TokenUsage
import org.llm4s.trace.{ EnhancedTracing, TraceEvent, TracingComposer, TracingMode }
import org.slf4j.LoggerFactory

object EnhancedTracingExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("Enhanced Tracing Example")
    logger.info("=" * 50)
    val result = for {
      config <- LLMConfig()
      _ = {
        logger.info("1. Basic Enhanced Tracing")
        val basicTracer = EnhancedTracing.create(TracingMode.Console)(config)

        val agentEvent = TraceEvent.AgentInitialized(
          query = "What's the weather like?",
          tools = Vector("weather", "calculator")
        )

        val completionEvent = TraceEvent.CompletionReceived(
          id = "test-123",
          model = "gpt-4",
          toolCalls = 2,
          content = "I'll check the weather for you."
        )

        // Trace events
        basicTracer.traceEvent(agentEvent)
        basicTracer.traceEvent(completionEvent)

        val (consoleTracer: EnhancedTracing, noOpTracer: EnhancedTracing) = example2(config, agentEvent)

        example3(agentEvent, consoleTracer)
        example4(consoleTracer)
        example5(consoleTracer, noOpTracer)
        example6(basicTracer, agentEvent)
        example7(config)
        example8(config)
      }
    } yield ()
    result.fold(err => logger.error(s"Error: ${err.formatted}"), identity)
  }

  private def example2(config: ConfigReader, agentEvent: TraceEvent.AgentInitialized) = {
    // Example 2: Composed tracing (multiple tracers)
    logger.info("2. Composed Tracing (Console + NoOp)")
    val consoleTracer  = EnhancedTracing.create(TracingMode.Console)(config)
    val noOpTracer     = EnhancedTracing.create(TracingMode.NoOp)(config)
    val composedTracer = TracingComposer.combine(consoleTracer, noOpTracer)

    composedTracer.traceEvent(agentEvent)
    (consoleTracer, noOpTracer)
  }

  private def example3(agentEvent: TraceEvent.AgentInitialized, consoleTracer: EnhancedTracing) = {
    // Example 3: Filtered tracing (only error events)
    logger.info("3. Filtered Tracing (Only Errors)")
    val errorOnlyTracer = TracingComposer.filter(consoleTracer) { event =>
      event.isInstanceOf[TraceEvent.ErrorOccurred]
    }

    // This won't be traced (not an error)
    errorOnlyTracer.traceEvent(agentEvent)

    // This will be traced (is an error)
    val errorEvent = TraceEvent.ErrorOccurred(
      error = new RuntimeException("Test error"),
      context = "Enhanced tracing example"
    )
    errorOnlyTracer.traceEvent(errorEvent)
  }

  private def example4(consoleTracer: EnhancedTracing) = {
    // Example 4: Transformed tracing (add metadata)
    logger.info("4. Transformed Tracing (Add Metadata)")
    val transformedTracer = TracingComposer.transform(consoleTracer) {
      case e: TraceEvent.CustomEvent =>
        TraceEvent.CustomEvent(
          name = s"[ENHANCED] ${e.name}",
          data = ujson.Obj.from(e.data.obj.toSeq :+ ("enhanced" -> true))
        )
      case other => other
    }

    val customEvent = TraceEvent.CustomEvent("test", ujson.Obj("value" -> 42))
    transformedTracer.traceEvent(customEvent)
  }

  private def example5(consoleTracer: EnhancedTracing, noOpTracer: EnhancedTracing) = {
    // Example 5: Complex composition
    logger.info("5. Complex Composition")
    val complexTracer = TracingComposer.combine(
      consoleTracer,
      TracingComposer.filter(noOpTracer) {
        _.isInstanceOf[TraceEvent.CompletionReceived]
      },
      TracingComposer.transform(consoleTracer) {
        case e: TraceEvent.TokenUsageRecorded =>
          TraceEvent.TokenUsageRecorded(
            usage = e.usage,
            model = s"[COST] ${e.model}",
            operation = e.operation
          )
        case other => other
      }
    )

    val tokenEvent = TraceEvent.TokenUsageRecorded(
      usage = TokenUsage(10, 20, 30),
      model = "gpt-4",
      operation = "completion"
    )
    complexTracer.traceEvent(tokenEvent)
  }

  private def example6(basicTracer: EnhancedTracing, agentEvent: TraceEvent.AgentInitialized): Unit = {
    // Example 6: Error handling
    logger.info("6. Error Handling")
    val result = basicTracer.traceEvent(agentEvent)
    result match {
      case Right(_)    => logger.info("Tracing successful")
      case Left(error) => logger.error(s"Tracing failed: ${error.message}")
    }
  }

  private def example7(config: ConfigReader): Unit = {
    // Example 7: Type-safe mode creation
    logger.info("7. Type-Safe Mode Creation")
    val modes = Seq(TracingMode.Console, TracingMode.NoOp, TracingMode.Langfuse)
    modes.foreach { mode =>
      val tracer = EnhancedTracing.create(mode)(config)
      logger.info(s"Created tracer for mode: $mode - ${tracer.getClass.getSimpleName}")
    }
  }

  private def example8(config: ConfigReader): Unit = {
    // Example 8: Environment-based configuration
    logger.info("8. Environment-Based Configuration")
    val envTracer = EnhancedTracing.create()(config)
    logger.info(s"Created tracer from environment: ${envTracer.getClass.getSimpleName}")

    logger.info("Enhanced Tracing Example Complete!")
    logger.info("=" * 50)
    logger.info("Key Benefits:")
    logger.info("• Type-safe events prevent typos")
    logger.info("• Composable tracers for complex scenarios")
    logger.info("• Filtering and transformation capabilities")
    logger.info("• Better error handling with Result[Unit]")
    logger.info("• Backward compatibility maintained")
  }
}
