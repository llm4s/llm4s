package org.llm4s.samples.agent

import org.llm4s.agent.Agent
import org.llm4s.config.Llm4sConfig
import com.typesafe.config.ConfigFactory
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.builtin.search.{ BraveSearchTool, SafeSearch }
import org.llm4s.toolapi.builtin.search.BraveSearchCategory
import org.llm4s.toolapi.builtin.search.BraveSearchConfig
import org.slf4j.LoggerFactory

/**
 * Researcher Agent Example - Demonstrates all four Brave Search tools in an intelligent research workflow.
 *
 * This example showcases how an agentic LLM can autonomously conduct multi-modal research by:
 * 1. Planning a research strategy for a given topic
 * 2. Executing searches across web, news, images, and videos
 * 3. Synthesizing findings into a comprehensive research report
 *
 * The agent decides which tools to use and in what order based on the research topic.
 *
 * @example
 * {{{
 * export LLM_MODEL=openai/gpt-4o
 * export OPENAI_API_KEY=sk-...
 * export BRAVE_SEARCH_API_KEY=your-brave-api-key
 * ,
 * }}}
 */

object ResearcherAgentExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    val config        = ConfigFactory.load()
    val researchTopic = config.getString("llm4s.samples.agent.research-topic")
    logger.info("🔬 === Researcher Agent Example ===\n")
    logger.info("Research Topic: {}", researchTopic)
    logger.info("=" * 70)

    // Create LLM client and load Brave Search configuration
    val clientResult = for {
      providerCfg <- Llm4sConfig.provider()
      client      <- LLMConnect.getClient(providerCfg)
    } yield client

    val braveConfigResult = Llm4sConfig.loadBraveSearchTool()

    (clientResult, braveConfigResult) match {
      case (Left(error), _) =>
        logger.error("Failed to create LLM client: {}", error)
        logger.error("Make sure LLM_MODEL and appropriate API key are set")
        logger.error("Example: export LLM_MODEL=openai/gpt-4o")

      case (_, Left(error)) =>
        logger.error("Failed to load Brave Search configuration: {}", error)
        logger.error("Make sure BRAVE_SEARCH_API_KEY is set")
        logger.error("Example: export BRAVE_SEARCH_API_KEY=your-api-key")

      case (Right(client), Right(braveConfig)) =>
        logger.info("✓ LLM client created successfully")
        logger.info("✓ Brave Search configuration loaded\n")

        // Create tool registry with all four Brave Search tools
        val tools = createResearchTools(braveConfig)
        logger.info("✓ Research tools initialized: {}", tools.map(_.name).mkString(", "))

        val registry = new ToolRegistry(tools)
        val agent    = new Agent(client)

        // Execute research workflow
        executeResearch(agent, registry, researchTopic)

        logger.info("\n" + "=" * 70)
        logger.info("🔬 === Research Complete ===")
    }
  }

  /**
   * Creates all four Brave Search tools using loaded configuration.
   * This demonstrates clean API usage with configuration loading from reference.conf.
   *
   * @param braveConfig The loaded Brave Search configuration containing API key and defaults
   * @return Sequence of configured Brave Search tools for Web, News, Image, and Video
   */
  private def createResearchTools(
    braveConfig: org.llm4s.config.BraveSearchToolConfig
  ): Seq[org.llm4s.toolapi.ToolFunction[_, _]] = {
    val tools = for {
      web  <- BraveSearchTool.create(braveConfig, BraveSearchCategory.Web, Some(BraveSearchConfig(count = 10)))
      news <- BraveSearchTool.create(braveConfig, BraveSearchCategory.News, Some(BraveSearchConfig(count = 10)))
      image <- BraveSearchTool.create(
        braveConfig,
        BraveSearchCategory.Image,
        Some(BraveSearchConfig(count = 2, safeSearch = SafeSearch.Strict))
      )
      video <- BraveSearchTool.create(
        braveConfig,
        BraveSearchCategory.Video,
        Some(BraveSearchConfig(count = 2, safeSearch = SafeSearch.Strict))
      )
    } yield Seq(web, news, image, video)

    tools match {
      case Right(t) => t
      case Left(err) =>
        logger.error("Failed to create research tools: {}", err.formatted)
        Seq.empty
    }
  }

  /**
   * Executes the multi-phase research workflow with the agent.
   */

  // research topic could be moved to config file

  private def executeResearch(agent: Agent, registry: ToolRegistry, researchTopic: String): Unit = {
    val systemPrompt = createResearchSystemPrompt()

    val researchQuery =
      s"""Conduct comprehensive research on: "$researchTopic"
         |
         |Follow this research workflow:
         |1. Use brave_web_search to gather foundational information and key facts
         |2. Use brave_news_search to find recent developments and current events
         |3. Use brave_image_search to find relevant visual references
         |4. Use brave_video_search to find explanatory videos or documentaries
         |
         |After gathering information from all sources, provide a comprehensive research summary that:
         |- Highlights key findings from each source type
         |- Identifies patterns and connections across different information modalities
         |- Presents the most important insights about the topic
         |
         |Format your final response with clear sections for Web Findings, News Updates, Visual References, Video Resources, and Research Summary.
       """.stripMargin

    logger.info("\n📊 PHASE 1: Research Planning & Execution\n")

    agent.run(researchQuery, registry, systemPromptAddition = Some(systemPrompt)) match {
      case Left(error) =>
        logger.error("Research failed: {}", error.formatted)

      case Right(state) =>
        // Extract and display the final research summary
        val finalResponse = state.conversation.messages
          .filter(_.role == org.llm4s.llmconnect.model.MessageRole.Assistant)
          .lastOption
          .map(_.content)
          .getOrElse("No research summary available")

        println("\n" + "=" * 70)
        println("📋 RESEARCH SUMMARY")
        println("=" * 70)
        println(s"\n$finalResponse\n")
    }
  }

  /**
   * Creates a system prompt that guides the agent's research behavior.
   */
  private def createResearchSystemPrompt(): String =
    """You are an expert research assistant with access to multiple search tools.
      |
      |Your role is to conduct thorough, multi-modal research on topics by:
      |- Using web search for foundational knowledge and comprehensive information
      |- Using news search for recent developments, current events, and trending topics
      |- Using image search to find visual references, diagrams, charts, and photographs
      |- Using video search to find explanatory content, documentaries, and presentations
      |
      |Research Guidelines:
      |1. Always use multiple search modalities to get a complete picture
      |2. Consider which type of search is most appropriate for different information needs
      |3. Synthesize findings from all sources into coherent insights
      |4. Highlight connections between different types of information
      |5. Present information in a clear, structured format
      |
      |Remember: Different search types provide complementary perspectives on the topic.
    """.stripMargin

}
