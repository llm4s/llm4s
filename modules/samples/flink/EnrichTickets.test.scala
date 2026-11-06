import java.io.{ ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream }

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment
import org.apache.flink.test.util.MiniClusterWithClientResource
import org.llm4s.llmconnect.LLMClient
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.types.Result

/**
 * Runs the job on Flink's MiniCluster (one task manager, two slots, parallelism 2) with a scripted model: no key, no
 * network. Flink serialises the job's functions to run them, so these runs also prove `ClassifyTicket` ships.
 */
class EnrichTicketsTest extends munit.FunSuite {

  private val cluster = new MiniClusterWithClientResource(
    new MiniClusterResourceConfiguration.Builder().setNumberTaskManagers(1).setNumberSlotsPerTaskManager(2).build()
  )

  override def beforeAll(): Unit = cluster.before()
  override def afterAll(): Unit  = cluster.after()

  /** Runs the job over `tickets` and returns what reached each output. */
  private def run(
    tickets: Seq[Ticket],
    newClient: () => Result[LLMClient],
    timeout: FiniteDuration
  ): (List[Enriched], List[DeadLetter]) = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setParallelism(2)
    val (enriched, deadLetters) =
      EnrichTickets.enrich(env.fromData(TypeInformation.of(classOf[Ticket]), tickets*), newClient, timeout)
    val enrichedIt = enriched.collectAsync()
    val deadIt     = deadLetters.collectAsync()
    env.execute("enrich-tickets-test")
    (enrichedIt.asScala.toList, deadIt.asScala.toList)
  }

  test("every ticket is enriched or dead-lettered, never lost") {
    val (enriched, dead) = run(EnrichTickets.SampleTickets, () => EnrichTickets.scriptedClient(), 10.seconds)

    assertEquals(
      enriched.sortBy(_.id).map(e => e.id -> e.category),
      List(1 -> "billing", 2 -> "bug", 4 -> "account")
    )
    assertEquals(dead.map(_.ticket.id).sorted, List(3, 5))
    assert(dead.exists(d => d.ticket.id == 3 && d.reason.contains("the scripted model failed")), dead)
    assert(dead.exists(d => d.ticket.id == 5 && d.reason == "unexpected answer: no idea"), dead)
  }

  test("a call that outlives the timeout goes to the dead letters, and the job finishes") {
    // A model that answers "slow" tickets only after the operator's timeout has passed.
    val slowModel: () => Result[LLMClient] = () =>
      Right(ScriptedLLMClient.respondingTo { conversation =>
        if (conversation.messages.last.content.contains("slow")) Thread.sleep(10000)
        Reply.text("bug")
      })
    val tickets = Seq(Ticket(1, "crash on start"), Ticket(2, "slow: never answered in time"), Ticket(3, "crash again"))

    val (enriched, dead) = run(tickets, slowModel, 500.millis)

    assertEquals(enriched.map(_.id).sorted, List(1, 3))
    assertEquals(dead.map(d => d.ticket.id -> d.reason), List(2 -> "timed out"))
  }

  test("a client that cannot be built fails the job at open()") {
    val noClient: () => Result[LLMClient] = () => Left(org.llm4s.error.ConfigurationError("no key"))
    val failure                           = intercept[Exception](run(Seq(Ticket(1, "crash")), noClient, 1.second))
    assert(
      Iterator
        .iterate[Throwable](failure)(_.getCause)
        .takeWhile(_ != null)
        .exists(_.getMessage.contains("no LLM client")),
      failure
    )
  }

  test("the async function serialises, with its client factory, and builds no client until open()") {
    var built                             = 0
    val counting: () => Result[LLMClient] = () => { built += 1; EnrichTickets.scriptedClient() }
    val bytes                             = new ByteArrayOutputStream()
    val out                               = new ObjectOutputStream(bytes)
    out.writeObject(new ClassifyTicket(counting, threads = 1))
    out.close()
    val copy = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray)).readObject()

    assert(copy.isInstanceOf[ClassifyTicket])
    assertEquals(built, 0)
  }
}
