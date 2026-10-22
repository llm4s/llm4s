package org.llm4s.agent.testkit

import org.llm4s.agent.graph.{ FencingToken, ReplayGuard, ReplayKeys, ReplaySupport, RunContext }
import org.llm4s.agent.graph.tool.LockKey
import org.llm4s.agent.harness.*
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{ Files, Path }
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/** The harness's shipped backends pass the file contract and, when external, the replay contract (§4.25). */
object HarnessBackends:

  def storeFiles(store: LongTermStore, path: Vector[String] = Vector("memories")): StoreFileBackend =
    StoreFileBackend(store, path).fold(e => throw new IllegalStateException(e.message), identity)
  def p(s: String): VPath = VPath(s).fold(e => throw new IllegalArgumentException(e.message), identity)

  /** One lock directory for every host backend of the suite, as an application gives its backends. */
  private lazy val locks: Path = Files.createTempDirectory("llm4s-contract-locks")

  /**
   * A host backend over a fresh temporary directory. Windows reports no hard-link counts, so there the suite
   * vouches for its own directory; a volume that makes 8.3 short names is refused whatever is vouched for,
   * and has nothing of the backend to test, so the case is cancelled.
   */
  def host(): (HostFileBackend, Path) =
    val dir = Files.createTempDirectory("llm4s-contract")
    HostFileBackend.open(dir, locks, allowUncheckedIdentity = !HostFileBackend.reportsLinkCounts(dir)) match
      case Right(backend) => (backend, dir)
      case Left(e) if e.message.contains("8.3 short names") =>
        org.scalatest.Assertions.cancel(s"unsupported file system: ${e.message}")
      case Left(e) => throw new IllegalStateException(e.message)

  def onHost(dir: Path, path: VPath): Path = dir.resolve(path.value.drop(1))

  /** Why this machine's temporary volume cannot hold a host backend (it makes 8.3 short names), if it cannot. */
  lazy val hostUnsupported: Option[String] =
    val probed = Files.createTempDirectory("llm4s-probe")
    HostFileBackend.probeAliases(probed).toOption.filter(_.nonEmpty).map(_.mkString("; "))

  val memories: Namespace = Namespace(None, Vector("memories"))

  /** `change` applied straight to the store, as another writer would, leaving no applied key. */
  def raw(store: LongTermStore, ns: Namespace, key: String)(change: StoreChange[Unit]): Unit =
    val effect = ReplayKeys.unguarded(s"$key-${System.nanoTime()}")
    store.modify[Unit](ns, key, effect, FencingToken(0))(_ => Right(change)): Unit
    store.forget(effect, FencingToken(0)): Unit

class StateFileBackendContractSpec extends AnyFlatSpec with FileBackendContract:
  protected def newBackend(): FileBackend = StateFileBackend(maxBytes = 200)
  // path and content are 80 bytes each: two fit, the third is over
  override protected def boundedBatch: Option[Vector[(String, String)]] =
    Some(Vector("/a" -> "x" * 78, "/b" -> "x" * 78, "/c" -> "x" * 78))

class CompositeBackendContractSpec extends AnyFlatSpec with FileBackendContract:
  // the contract's paths live in the default; a mount beside them must not disturb them
  protected def newBackend(): FileBackend =
    CompositeBackend(
      StateFileBackend(),
      HarnessBackends.p("/memories") -> HarnessBackends.storeFiles(InMemoryLongTermStore())
    )
      .fold(e => throw new IllegalStateException(e.message), identity)

class HostFileBackendContractSpec extends AnyFlatSpec with FileBackendContract:
  protected def newBackend(): FileBackend = HarnessBackends.host()._1

class StoreFileBackendContractSpec extends AnyFlatSpec with FileBackendContract:
  protected def newBackend(): FileBackend =
    HarnessBackends.storeFiles(InMemoryLongTermStore(StoreLimits(maxBytes = 200)))
  override protected def tenantScoped: Boolean = true
  // each item is its key, plus {"content":"..."}: 94 bytes; two fit, the third is over
  override protected def boundedBatch: Option[Vector[(String, String)]] =
    Some(Vector("/a" -> "x" * 78, "/b" -> "x" * 78, "/c" -> "x" * 78))

class ResolvedBackendContractSpec extends AnyFlatSpec with FileBackendContract:
  protected def newBackend(): FileBackend =
    val byTenant = new java.util.concurrent.ConcurrentHashMap[Option[String], FileBackend]()
    FileBackend.resolved("by-tenant") { ctx =>
      // one store per tenant, each its own tree
      Right(
        byTenant.computeIfAbsent(
          ctx.run.config.tenantId.map(_.value),
          _ => HarnessBackends.storeFiles(InMemoryLongTermStore())
        )
      )
    }
  override protected def tenantScoped: Boolean = true

class ResolvedStateBackendContractSpec extends AnyFlatSpec with FileBackendContract:
  // a resolver may pick a state backend: its quota stays exact only if the resolved lock orders every write
  protected def newBackend(): FileBackend =
    val state = StateFileBackend(maxBytes = 200)
    FileBackend.resolved("state", writes = state.writes)(_ => Right(state))
  override protected def boundedBatch: Option[Vector[(String, String)]] =
    Some(Vector("/a" -> "x" * 78, "/b" -> "x" * 78, "/c" -> "x" * 78))

// Replay: each external backend through its own guarded path.

class HostFileBackendReplaySpec extends AnyFlatSpec with ReplayContract:
  override def withFixture(test: NoArgTest) =
    HarnessBackends.hostUnsupported.fold(super.withFixture(test))(why =>
      org.scalatest.Canceled(s"unsupported file system: $why")
    )
  protected def newSubject(): ReplayContract.Subject =
    // the contract names its subject when it registers its cases; on a volume the backend refuses, a
    // stand-in names it, and withFixture cancels every case before one could use it
    HarnessBackends.hostUnsupported.fold(hostSubject())(why =>
      new ReplayContract.Subject:
        def name: String           = s"a host file backend (unsupported here: $why)"
        def support: ReplaySupport = ReplaySupport.Verifiable
        def perform(context: RunContext, slot: Int): Result[ujson.Value] = Left(BackendRefused("perform", "", why))
        def observe(slot: Int): Result[String]                           = Left(BackendRefused("observe", "", why))
        def initial(slot: Int): String                                   = ""
        def once(slot: Int): String                                      = ""
        def interfere(slot: Int): Unit                                   = ()
    )

  private def hostSubject(): ReplayContract.Subject =
    val (backend, dir) = HarnessBackends.host()
    FileBackendContract.EditSubject(
      "a host file backend",
      backend,
      ReplaySupport.Verifiable,
      (path, text) => Files.writeString(HarnessBackends.onHost(dir, path), text): Unit,
      path => Right(new String(Files.readAllBytes(HarnessBackends.onHost(dir, path)), UTF_8))
    )

class StoreFileBackendReplaySpec extends AnyFlatSpec with ReplayContract:
  protected def newSubject(): ReplayContract.Subject =
    val store = InMemoryLongTermStore()
    val ns    = HarnessBackends.memories
    // seeded and interfered with straight in the store, as another thread's write
    def seed(path: VPath, text: String): Unit =
      HarnessBackends.raw(store, ns, path.value)(StoreChange.Put(ujson.Obj("content" -> text), ()))
    FileBackendContract.EditSubject(
      "a store file backend",
      HarnessBackends.storeFiles(store),
      ReplaySupport.Transactional,
      seed,
      path =>
        store.get(ns, path.value).map(_.flatMap(_.value.objOpt).flatMap(_.get("content")).map(_.str).getOrElse("")),
      () => store.appliedKeys
    )

class LongTermStoreReplaySpec extends AnyFlatSpec with ReplayContract:
  protected def newSubject(): ReplayContract.Subject = new ReplayContract.Subject:
    private val store          = InMemoryLongTermStore()
    private val ns             = Namespace(None, Vector("counters"))
    def name: String           = "a long-term store's transactional modify"
    def support: ReplaySupport = ReplaySupport.Transactional
    // each application increments the slot's stored count inside the store's transaction, so a second
    // application shows, and a replay the store answers from its applied key does not
    def perform(context: RunContext, slot: Int): Result[ujson.Value] =
      val ctx    = FileContext.withoutState(context)
      val effect = ctx.effectKey("store.increment", s"slot-$slot")
      ReplayGuard
        .transactional[Int](context, effect, store)(token =>
          store.modify[Int](ns, s"slot-$slot", effect, token) { view =>
            val next = view.current.flatMap(_.value.numOpt).fold(1)(_.toInt + 1)
            Right(StoreChange.Put(ujson.Num(next), next))
          }
        )
        .map(ujson.Num(_))
    def observe(slot: Int): Result[String] =
      store
        .get(ns, s"slot-$slot")
        .map(_.fold("absent")(i => i.value.numOpt.fold(i.value.toString)(n => n.toInt.toString)))
    def initial(slot: Int): String = "absent"
    def once(slot: Int): String    = "1"
    def interfere(slot: Int): Unit =
      HarnessBackends.raw(store, ns, s"slot-$slot")(StoreChange.Put(ujson.Str("other"), ()))
    override def appliedKeys: Int = store.appliedKeys

class SandboxBackendReplaySpec extends AnyFlatSpec with ReplayContract:
  protected def newSubject(): ReplayContract.Subject = new ReplayContract.Subject:
    private val ran = Vector.fill(2)(new AtomicInteger())
    private val runner = new SandboxRunner:
      def locks(workdir: VPath): Set[LockKey] = Set(LockKey("sandbox"))
      def run(
        command: String,
        workdir: VPath,
        timeout: FiniteDuration,
        maxOutputBytes: Int,
        ctx: FileContext
      ): Result[ExecResult] =
        val slot = command.last.asDigit
        Right(ExecResult(0, s"run ${ran(slot).incrementAndGet()}", ""))
    private val sandbox        = SandboxBackend(runner)
    def name: String           = "a sandbox backend"
    def support: ReplaySupport = ReplaySupport.Unverifiable
    def perform(context: RunContext, slot: Int): Result[ujson.Value] =
      sandbox
        .execute(s"step $slot", HarnessBackends.p("/"), 1.second, FileContext.withoutState(context))
        .map(r => ujson.Str(r.stdout))
    def observe(slot: Int): Result[String] = Right(ran(slot).get.toString)
    def initial(slot: Int): String         = "0"
    def once(slot: Int): String            = "1"
    def interfere(slot: Int): Unit         = ran(slot).addAndGet(10): Unit
