package org.llm4s.agent.harness

import org.llm4s.agent.graph.HarnessTestSupport.{ discardAttempt, recordingRunContext, stateOf }
import org.llm4s.agent.graph.middleware.MiddlewareId
import org.llm4s.agent.harness.HarnessFiles.*
import org.llm4s.error.ValidationError
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Stage 3 slice F5 (#1909, design §4.35): the read-once, bounded document mechanism F5 and F6 share. */
class RunDocumentsSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private def ctxOn(backend: FileBackend) =
    FileContext(org.llm4s.agent.graph.GraphTestSupport.testRunContext(), stateOf(backend.writes.toSeq*))

  "DocumentText.read" should "read a file whole in one call, one version of it, or None when it is missing" in {
    val backend = storeBackend()
    val lines   = (1 to 600).map(i => s"line $i")
    seed(backend, "/a.md" -> lines.mkString("\n"))
    val counting = new CountingBackend(backend)
    DocumentText.read(counting, p("/a.md"), 1_000_000L, ctxOn(backend)).value.value shouldBe
      BoundedText(lines.mkString("\n"), cut = false)
    counting.reads.get shouldBe 1
    DocumentText.read(backend, p("/missing.md"), 100L, ctxOn(backend)).value shouldBe None
  }

  it should "keep at most maxChars, cut on a code point, and say it was cut" in {
    val backend = storeBackend()
    seed(backend, "/a.md" -> "abc\ndef\n\uD83D\uDE00xyz")
    DocumentText.read(backend, p("/a.md"), 5L, ctxOn(backend)).value.value shouldBe BoundedText(
      "abc\nd",
      cut = true,
      lastLineCut = true
    )
    // the 9th character would split the surrogate pair, so the cut stops before it
    DocumentText.read(backend, p("/a.md"), 9L, ctxOn(backend)).value.value shouldBe BoundedText(
      "abc\ndef\n",
      cut = true,
      lastLineCut = true
    )
    DocumentText.read(backend, p("/a.md"), 7L, ctxOn(backend)).value.value shouldBe BoundedText("abc\ndef", cut = true)
    DocumentText.read(backend, p("/a.md"), 13L, ctxOn(backend)).value.value shouldBe
      BoundedText("abc\ndef\n\uD83D\uDE00xyz", cut = false)
  }

  it should "stop after the line its predicate accepts, asking for no more lines than the bound can hold" in {
    val backend = storeBackend()
    seed(backend, "/a.md" -> ((1 to 600).map(i => s"l$i").mkString("\n")))
    val counting = new CountingBackend(backend)
    DocumentText.read(counting, p("/a.md"), 1_000_000L, ctxOn(backend), _ == "l3").value.value shouldBe
      BoundedText("l1\nl2\nl3", cut = false)
    counting.reads.get shouldBe 1
  }

  it should "pass on an error that is not a missing file" in {
    val counting = new CountingBackend(storeBackend())
    counting.failing = Some(ValidationError("store", "down"))
    DocumentText.read(counting, p("/a.md"), 10L, ctxOn(counting)).left.value shouldBe ValidationError("store", "down")
  }

  "SectionText" should "add a part whole or not at all, or cut it with a marker counted in the bound" in {
    val s = new SectionText(21)
    s.addWhole("0123456789") shouldBe true
    s.addWhole("0123456789XY") shouldBe false
    s.addCut("## h\nbody body", prefix = 5, marker = "[cut]") shouldBe Some(true)
    s.text shouldBe "0123456789## h\nb[cut]"
    s.text.length should be <= 21
    s.addCut("## h\n", prefix = 5, marker = "[cut]") shouldBe None
    // a cut that would keep only the heading and the marker adds nothing
    val t = new SectionText(10)
    t.addCut("## h\nbody body", prefix = 5, marker = "[cut]") shouldBe None
    t.isEmpty shouldBe true
  }

  "Report" should "keep at most 32 issues and count the rest" in {
    val r = new Report
    (1 to 40).foreach(i => r.add(DocumentIssue(p(s"/f$i"), DocumentIssue.Kind.NotListed, None, "x")))
    val reported = r.result(MiddlewareId("m")).value
    reported.issues.size shouldBe Report.MaxIssues
    reported.omitted shouldBe 8
    new Report().result(MiddlewareId("m")) shouldBe None
  }

  "A DocumentsReported" should "round-trip through its codec, kinds by name (rule 7)" in {
    val event = DocumentsReported(
      "skills",
      Vector(DocumentIssue(p("/s/a/SKILL.md"), DocumentIssue.Kind.Malformed, Some(3), "x")),
      2
    )
    val json = HarnessEvents.DocumentsReported.encode(event)
    json("issues")(0)("kind").str shouldBe "Malformed"
    HarnessEvents.DocumentsReported.decode(json) shouldBe Some(event)
  }

  "RunCache" should "compute once per run incarnation and report in the computing task" in {
    val events   = new CopyOnWriteArrayList[(String, ujson.Value)]()
    val cache    = new RunCache[String](4)
    val computed = new AtomicInteger(0)
    def get(run: org.llm4s.agent.graph.RunContext) =
      cache(run, reports = true) { computed.incrementAndGet(); Right("v") }(_ => run.emit("reported", 1, ujson.Null))
    val first = recordingRunContext(events, token = Some(1L), task = "1.0")
    get(first).value shouldBe "v"
    get(recordingRunContext(events, token = Some(1L), task = "2.0")).value shouldBe "v"
    computed.get shouldBe 1
    events.size shouldBe 1 // the later task does not report again
    // the same run id under a new claim (a recovery) computes and reports again
    get(recordingRunContext(events, token = Some(2L), task = "1.0")).value shouldBe "v"
    computed.get shouldBe 2
    events.size shouldBe 2
    // another thread with the same run id and token is its own entry
    get(recordingRunContext(events, thread = "other", token = Some(1L), task = "1.0"))
    computed.get shouldBe 3
    // another runtime (another store) can issue the same thread, run id and token: still its own run
    get(recordingRunContext(events, token = Some(1L), task = "1.0", runtime = "other-store"))
    computed.get shouldBe 4
  }

  it should "report only on a reporting call: once in the task, whatever reads the value besides" in {
    val events = new CopyOnWriteArrayList[(String, ujson.Value)]()
    val cache  = new RunCache[String](4)
    val run    = recordingRunContext(events, token = Some(1L), task = "1.0")
    def get(context: org.llm4s.agent.graph.RunContext, reports: Boolean) =
      cache(context, reports)(Right("v"))(_ => context.emit("reported", 1, ujson.Null))
    // a reader first (another middleware asking for the skills), then the section's build, in one task
    get(run, reports = false)
    events.size shouldBe 0
    get(run, reports = true)
    events.size shouldBe 1
    get(run, reports = false)
    events.size shouldBe 1
    // a reader in a later task neither reports nor stops the first reporting task's report
    val other = new CopyOnWriteArrayList[(String, ujson.Value)]()
    val fresh = recordingRunContext(other, token = Some(2L), task = "1.0")
    cache(recordingRunContext(other, token = Some(2L), task = "0.0"), reports = false)(Right("v"))(_ => ())
    cache(fresh, reports = true)(Right("v"))(_ => fresh.emit("reported", 1, ujson.Null))
    other.size shouldBe 1
  }

  it should "report again when the computing task runs again after its attempt's events were discarded (rule 10)" in {
    val events = new CopyOnWriteArrayList[(String, ujson.Value)]()
    val cache  = new RunCache[String](4)
    val run    = recordingRunContext(events, token = Some(1L), task = "1.0")
    def get()  = cache(run, reports = true)(Right("v"))(_ => run.emit("reported", 1, ujson.Null))
    get()
    discardAttempt(run)
    events.size shouldBe 0
    get()
    events.size shouldBe 1
  }

  it should "let the callers of one run compute one at a time, a waiter retrying in turn after a failure" in {
    val events    = new CopyOnWriteArrayList[(String, ujson.Value)]()
    val cache     = new RunCache[String](4)
    val active    = new AtomicInteger(0)
    val most      = new AtomicInteger(0)
    val attempts  = new AtomicInteger(0)
    val successes = new AtomicInteger(0)
    def compute(): org.llm4s.types.Result[String] = {
      val now = active.incrementAndGet()
      most.accumulateAndGet(now, math.max)
      Thread.sleep(20)
      active.decrementAndGet()
      // the first attempt fails; the rest succeed
      if (attempts.getAndIncrement() == 0) Left(ValidationError("store", "down"))
      else { successes.incrementAndGet(); Right("v") }
    }
    val threads = (1 to 6).map { i =>
      new Thread(() => cache(recordingRunContext(events, task = s"t$i"), reports = true)(compute())(_ => ()): Unit)
    }
    threads.foreach { t =>
      t.start(); Thread.sleep(2)
    }
    threads.foreach(_.join())
    most.get shouldBe 1
    successes.get shouldBe 1
    cache.cached shouldBe 1
  }

  it should "give a waiter its run's value even when another run evicted it from the LRU meanwhile" in {
    val events   = new CopyOnWriteArrayList[(String, ujson.Value)]()
    val cache    = new RunCache[String](1)
    val computed = new AtomicInteger(0)
    val started  = new java.util.concurrent.CountDownLatch(1)
    val proceed  = new java.util.concurrent.CountDownLatch(1)
    val first = new Thread(() =>
      cache(recordingRunContext(events, task = "a"), reports = true) {
        started.countDown(); proceed.await(); computed.incrementAndGet(); Right("v")
      }(_ =>
        // while this caller still holds the slot, another run's value evicts this run's from the LRU (maxRuns 1)
        cache(recordingRunContext(events, run = "other"), reports = true)(Right("o"))(_ => ()): Unit
      ): Unit
    )
    first.start()
    started.await()
    val waiter = new Thread(() =>
      cache(recordingRunContext(events, task = "b"), reports = true) { computed.incrementAndGet(); Right("again") }(_ =>
        ()
      ): Unit
    )
    waiter.start()
    while (waiter.getState != Thread.State.WAITING) Thread.sleep(1)
    proceed.countDown()
    first.join()
    waiter.join()
    computed.get shouldBe 1
  }

  it should "let a virtual thread wait for its run's value without holding a monitor, and be interrupted" in {
    val events  = new CopyOnWriteArrayList[(String, ujson.Value)]()
    val cache   = new RunCache[String](4)
    val started = new java.util.concurrent.CountDownLatch(1)
    val proceed = new java.util.concurrent.CountDownLatch(1)
    val holder = Thread.ofVirtual().start { () =>
      cache(recordingRunContext(events, task = "a"), reports = true) {
        started.countDown(); proceed.await(); Right("v")
      }(_ => ()): Unit
    }
    started.await()
    val outcome = new java.util.concurrent.atomic.AtomicReference[String]("none")
    // what ends the waiter: its value, or the throw that escapes it (an interrupt is not caught by Try)
    val waiter = Thread
      .ofVirtual()
      .uncaughtExceptionHandler((_, e) => outcome.set(e.getClass.getSimpleName))
      .start { () =>
        outcome.set(cache(recordingRunContext(events, task = "b"), reports = true)(Right("w"))(_ => ()).toString)
      }
    while (waiter.getState != Thread.State.WAITING) Thread.sleep(1)
    waiter.interrupt()
    waiter.join()
    outcome.get shouldBe "InterruptedException"
    proceed.countDown()
    holder.join()
    cache.inFlightRuns shouldBe 0
    cache.cached shouldBe 1
  }

  it should "leave no in-flight entry behind a computation an interrupt ended" in {
    val events = new CopyOnWriteArrayList[(String, ujson.Value)]()
    val cache  = new RunCache[String](4)
    (1 to 3).foreach { i =>
      an[InterruptedException] should be thrownBy
        cache(recordingRunContext(events, run = s"i$i"), reports = true)(throw new InterruptedException("cancelled"))(
          _ => ()
        )
    }
    cache.inFlightRuns shouldBe 0
    cache.cached shouldBe 0
  }

  it should "not keep a failure, and hold at most maxRuns incarnations (rule 12)" in {
    val events = new CopyOnWriteArrayList[(String, ujson.Value)]()
    val cache  = new RunCache[String](2)
    val run    = recordingRunContext(events, token = Some(1L))
    cache(run, reports = true)(Right("v"))(_ => ()).value shouldBe "v"
    // failures of other runs, returned or thrown, take no place from the run that has its value
    (1 to 3).foreach { i =>
      cache(recordingRunContext(events, run = s"f$i"), reports = true)(Left(ValidationError("store", "down")))(_ =>
        ()
      ).isLeft shouldBe true
    }
    an[IllegalStateException] should be thrownBy cache(recordingRunContext(events, run = "thrown"), reports = true)(
      throw new IllegalStateException("boom")
    )(_ => ())
    cache.cached shouldBe 1
    // a full cache: a failure evicts no value
    cache(recordingRunContext(events, run = "second"), reports = true)(Right("s"))(_ => ()).value shouldBe "s"
    cache(recordingRunContext(events, run = "f9"), reports = true)(Left(ValidationError("store", "down")))(_ =>
      ()
    ).isLeft shouldBe true
    cache.cached shouldBe 2
    val again = new java.util.concurrent.atomic.AtomicInteger(0)
    cache(run, reports = true) { again.incrementAndGet(); Right("w") }(_ => ()).value shouldBe "v"
    again.get shouldBe 0
    (1 to 5).foreach(i => cache(recordingRunContext(events, run = s"r$i"), reports = true)(Right("v"))(_ => ()))
    cache.cached shouldBe 2
    an[IllegalArgumentException] should be thrownBy new RunCache[String](0)
  }
}
