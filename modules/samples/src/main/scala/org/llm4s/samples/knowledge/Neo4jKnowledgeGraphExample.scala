package org.llm4s.samples.knowledge

import org.llm4s.config.Llm4sConfig
import org.llm4s.knowledgegraph.neo4j.Neo4jGraphStore
import org.llm4s.knowledgegraph.{ Edge, Node }
import org.llm4s.knowledgegraph.storage.Direction
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.{ Conversation, SystemMessage, UserMessage }
import org.slf4j.LoggerFactory

import scala.util.Using

object Neo4jKnowledgeGraphExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    val neo4jConfig = Neo4jGraphStore.Config(password = "password")

    Neo4jGraphStore(neo4jConfig) match {
      case Left(error) =>
        logger.error(s"Failed to connect to Neo4j. Is the Docker container running? Error: ${error.formatted}")
        System.exit(1)
      case Right(store) =>
        logger.info("Successfully connected to Neo4j.")
        given scala.util.Using.Releasable[Neo4jGraphStore] = resource => resource.close()
        Using.resource(store) { storeInstance =>
          val alice    = Node("Alice", "Person", Map("name" -> "Alice", "role" -> "Manager"))
          val bob      = Node("Bob", "Person", Map("name" -> "Bob", "role" -> "Engineer"))
          val acmeCorp = Node("AcmeCorp", "Organization", Map("name" -> "Acme Corporation"))
          val london   = Node("London", "Location", Map("name" -> "London"))

          Seq(alice, bob, acmeCorp, london).foreach { node =>
            storeInstance
              .upsertNode(node)
              .fold(
                e => logger.error(s"Failed to upsert node ${node.id}: ${e.formatted}"),
                _ => ()
              )
          }

          val bobToAlice   = Edge("Bob", "Alice", "REPORTS_TO")
          val aliceToAcme  = Edge("Alice", "AcmeCorp", "WORKS_AT")
          val acmeToLondon = Edge("AcmeCorp", "London", "LOCATED_IN")

          Seq(bobToAlice, aliceToAcme, acmeToLondon).foreach { edge =>
            storeInstance
              .upsertEdge(edge)
              .fold(
                e => logger.error(s"Failed to upsert edge ${edge.relationship}: ${e.formatted}"),
                _ => ()
              )
          }

          val graphResult = storeInstance.getNeighbors("Bob", Direction.Outgoing) match {
            case Right(neighbors) =>
              neighbors
                .map { pair =>
                  val rel = pair.edge.relationship
                  val tgt = pair.node.id
                  val secondary = storeInstance
                    .getNeighbors(tgt, Direction.Outgoing)
                    .fold(
                      e => {
                        logger.error(s"Failed secondary traversal for $tgt: ${e.formatted}")
                        Seq.empty
                      },
                      identity
                    )
                    .map { p2 =>
                      val p2Rel = p2.edge.relationship
                      val p2Tgt = p2.node.id
                      val tertiary = storeInstance
                        .getNeighbors(p2Tgt, Direction.Outgoing)
                        .fold(
                          e => {
                            logger.error(s"Failed tertiary traversal for $p2Tgt: ${e.formatted}")
                            Seq.empty
                          },
                          identity
                        )
                        .map(p3 => s"which is ${p3.edge.relationship} ${p3.node.id}")
                        .mkString(" ")
                      s"who ${p2Rel} ${p2Tgt} ${tertiary}"
                    }
                    .mkString(" and ")
                  s"Bob ${rel} ${tgt}, ${secondary}"
                }
                .mkString("; ")
            case Left(err) =>
              logger.error(s"Failed to query graph: ${err.formatted}")
              "No graph context available."
          }

          val query = "Who does Bob report to and where do they work?"
          logger.info(s"LLM Query: ${query}")

          val llmResult = for {
            providerCfg     <- Llm4sConfig.defaultProvider()
            registryService <- Llm4sConfig.modelRegistryService()
            given org.llm4s.model.ModelRegistryService = registryService
            client <- LLMConnect.getClient(providerCfg)

            conversation = Conversation(
              Seq(
                SystemMessage("You are an assistant answering questions using the provided graph context."),
                UserMessage(s"Context:\n${graphResult}\n\nQuestion:\n${query}")
              )
            )

            completion <- client.complete(conversation)
            _ = logger.info(s"LLM Response:\n${completion.message.content}")
          } yield ()

          llmResult.fold(
            e => logger.error(s"LLM execution failed: ${e.formatted}"),
            _ => ()
          )

        }
    }
  }
}
