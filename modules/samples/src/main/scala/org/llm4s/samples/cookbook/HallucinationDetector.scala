package org.llm4s.samples.cookbook

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.samples.config.SamplesConfigLoader
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.builtin.search.{ ExaSearchTool, ExaSearchConfig }
import org.llm4s.agent.Agent
import org.llm4s.types.TryOps
import org.slf4j.LoggerFactory
import pureconfig.ConfigSource
import scala.util.Try

object HallucinationDetector {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    val clientResult = for {
      providerCfg <- Llm4sConfig.provider()
      client      <- LLMConnect.getClient(providerCfg)
    } yield client

    val exaConfigResult      = Llm4sConfig.loadExaSearchTool()
    val detectorConfigResult = SamplesConfigLoader.loadHallucinationDetector(ConfigSource.default)

    (clientResult, exaConfigResult, detectorConfigResult) match {
      case (Left(error), _, _) =>
        logger.error("Failed to create LLM client: {}", error.formatted)
        logger.error("Make sure LLM_MODEL and appropriate API key are set")
        logger.error("Example: export LLM_MODEL=openai/gpt-4o")

      case (_, Left(error), _) =>
        logger.error("Failed to load Exa Search configuration: {}", error.formatted)
        logger.error("Make sure EXA_API_KEY is set")
        logger.error("Example: export EXA_API_KEY=your-api-key")

      case (_, _, Left(error)) =>
        logger.error("Failed to load sample configuration: {}", error.formatted)
        logger.error("Make sure llm4s.samples.cookbook.hallucinationDetector.text is set in application.conf")

      case (Right(client), Right(exaConfig), Right(detectorConfig)) =>
        logger.info("✓ LLM client created successfully")
        logger.info("✓ Exa Search configuration loaded")
        logger.info("✓ Sample configuration loaded\n")

        // Configure the tool to return the text content of the search results
        val exaToolResult = ExaSearchTool.create(
          exaConfig,
          Some(
            ExaSearchConfig(
              numResults = 3,
              extraParams = Map(
                "contents" -> ujson.Obj(
                  "text" -> ujson.Obj(
                    "maxCharacters" -> ujson.Num(250)
                  )
                )
              )
            )
          )
        )

        exaToolResult match {
          case Left(error) =>
            logger.error("Failed to create Exa search tool: {}", error.formatted)

          case Right(exaSearchTool) =>
            val registry = new ToolRegistry(List(exaSearchTool))
            val agent    = new Agent(client)

            // Use the typed config loaded at the edge
            val text = detectorConfig.text

            logger.info(s"📄 Text to analyze: ${text.take(100)}...\n")

            // step 1 : extract claims from the text
            logger.info("\n🔍 Extracting claims from text...")
            val claims = extractClaims(text, agent)
            logger.info(s"✓ Extracted ${claims.length} claims\n")

            // step 2 : verify each claim using exa search
            val verifiedClaims = verifyClaims(claims, agent, registry)

            logger.info("\n" + "=" * 70)
            logger.info("🔬 Hallucination Detection Results")
            logger.info("=" * 70)

            if (verifiedClaims.isEmpty) {
              logger.info("⚠️  No verification results available")
            } else {
              verifiedClaims.zipWithIndex.foreach { case (v, idx) =>
                val claim       = v("claim").str
                val status      = v("status").str
                val evidence    = v.obj.get("evidence").flatMap(_.strOpt).getOrElse("N/A")
                val correctInfo = v.obj.get("correct_info").flatMap(_.strOpt)

                logger.info(s"\n${idx + 1}. ${status match {
                    case "Verified"      => "✅"
                    case "Hallucination" => "❌"
                    case _               => "❓"
                  }} $status")
                logger.info(s"   Claim: $claim")
                logger.info(s"   Evidence: $evidence")
                correctInfo.foreach(info => logger.info(s"   Correct Info: $info"))
              }
            }

            logger.info("\n" + "=" * 70)
            logger.info("✓ Verification complete")
            logger.info("=" * 70)
        }
    }
  }

  private def extractClaims(text: String, agent: Agent): List[String] = {
    val systemPrompt =
      """
  You are a factual claim extraction system.

  Your task is to extract ALL individual factual claims from the provided text.
  A factual claim is any statement that asserts something specific about the world that could be verified as true or false.

  Examples of factual claims:
  - "The Eiffel Tower is located in Paris"
  - "It was built in 1889"
  - "The tower is 330 meters tall"

  Extract EVERY distinct factual claim, even if they seem obvious or you suspect they might be false.
  Do NOT filter out claims that seem incorrect - extract them all.

  Output format: Return ONLY a valid JSON array of strings, with each string being one factual claim.
  Do NOT include explanations, comments, or markdown formatting.
  Do NOT wrap output in ``` code blocks.

  If no factual claims exist (rare), return: []
  """

    val userPrompt =
      s"""
  Extract all factual claims from the following text. Break down the text into individual, specific factual statements.

  Text:
  $text

  Return the extracted claims as a JSON array of strings.
  """

    agent.run(userPrompt, ToolRegistry.empty, systemPromptAddition = Some(systemPrompt)) match {
      case Left(error) =>
        logger.error("❌ Claim extraction failed: {}", error.formatted)
        List.empty[String]
      case Right(state) =>
        val finalResponse = state.conversation.messages
          .filter(_.role == org.llm4s.llmconnect.model.MessageRole.Assistant)
          .lastOption
          .map(_.content)
          .getOrElse("No claims extracted")

        val parseResult = for {
          cleaned <- Try(cleanJsonResponse(finalResponse)).toResult
          parsed  <- Try(ujson.read(cleaned)).toResult
          claims  <- Try(parsed.arr.map(_.str).toList).toResult
        } yield claims

        parseResult match {
          case Left(error) =>
            logger.error("❌ Failed to parse claims: {}", error.formatted)
            List.empty[String]
          case Right(claims) =>
            claims
        }
    }
  }

  private def verifyClaims(claims: List[String], agent: Agent, registry: ToolRegistry): List[ujson.Value] = {
    logger.info("🔍 Verifying claims with Exa Search...")

    val systemPrompt = """
    You are a claim verification agent with access to web search tools.

    Your task is to verify each factual claim by searching for evidence.
    For each claim:
    1. Use the exa_search tool to find relevant information
    2. Based on the search results, determine if the claim is accurate
    3. Categorize as "Verified", "Hallucination", or "Unverifiable"

    Return results as a JSON array of objects with this structure:
    {
      "claim": "The original claim text",
      "status": "Verified" or "Hallucination" or "Unverifiable",
      "evidence": "Brief summary of evidence found",
      "correct_info": "The corrected information if the claim was a hallucination (omit if verified)"
    }

    Output ONLY valid JSON without markdown formatting or explanations.
    """

    val userPrompt = s"""Verify each of these factual claims by searching for evidence:

${claims.zipWithIndex.map { case (claim, idx) => s"${idx + 1}. $claim" }.mkString("\n")}

For each claim, use the exa_search tool to find evidence, then return the structured JSON results.
"""

    agent.run(userPrompt, registry, systemPromptAddition = Some(systemPrompt)) match {
      case Left(error) =>
        logger.error("❌ Claim verification failed: {}", error.formatted)
        List.empty[ujson.Value]
      case Right(state) =>
        val finalResponse = state.conversation.messages
          .filter(_.role == org.llm4s.llmconnect.model.MessageRole.Assistant)
          .lastOption
          .map(_.content)
          .getOrElse("[]")

        val parseResult = for {
          cleaned <- Try(cleanJsonResponse(finalResponse)).toResult
          parsed  <- Try(ujson.read(cleaned)).toResult
          results <- Try(parsed.arr.toList).toResult
        } yield results

        parseResult match {
          case Left(error) =>
            logger.error("❌ Failed to parse verification results: {}", error.formatted)
            List.empty[ujson.Value]
          case Right(results) =>
            results
        }
    }
  }

  private def cleanJsonResponse(response: String): String = {
    val trimmed = response.trim
    if (trimmed.startsWith("```")) {
      trimmed
        .stripPrefix("```json")
        .stripPrefix("```")
        .stripSuffix("```")
        .trim
    } else {
      // Try to extract JSON if it's embedded in conversation
      val start = trimmed.indexOf('[')
      val end   = trimmed.lastIndexOf(']')
      if (start != -1 && end != -1 && end > start) {
        trimmed.substring(start, end + 1)
      } else {
        trimmed
      }
    }
  }

}
