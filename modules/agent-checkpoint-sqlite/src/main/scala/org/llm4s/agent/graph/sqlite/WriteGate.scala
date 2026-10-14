package org.llm4s.agent.graph.sqlite

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.util.{ Try, Using }

/**
 * The turn-taking of the [[SqliteCheckpointer]]s in this JVM that write one database file: a fair lock, so writers
 * are admitted first come, first served (#1951).
 *
 * SQLite does not queue a connection waiting for a file's write lock. Its busy handler sleeps - for 1 ms, then 2,
 * 5, ... up to 100 ms - and tries again, so it gets the lock only if it wakes in a gap between the other writers'
 * transactions. Beside a store committing back to back, the gaps are microseconds wide, and a short write can
 * wait out its whole busy timeout. A store holds its file's gate for the length of each write transaction, so
 * within one process the waiting is done here, in order, and SQLite's lock is free when a store asks for it -
 * unless another process holds it.
 *
 * Gates are shared by path - the real path of the file's directory and the file's name - and counted, so the gate
 * goes once the last store using it has closed. Stores that reach one file through different names (a hard link)
 * get different gates and fall back on SQLite's polling.
 */
final private[sqlite] class WriteGate private (val key: Path):
  private val lock  = new ReentrantLock(true)
  private var users = 0 // guarded by the WriteGate object

  /**
   * `body`, run holding the gate once the writers queued ahead have had their turn; `None`, with `body` not run, if
   * that takes longer than `timeout`. An interrupt while waiting throws `InterruptedException`.
   */
  def holding[A](timeout: FiniteDuration)(body: => A): Option[A] =
    // the timed `tryLock` keeps the queue's order; the untimed one would barge ahead of it
    Option.when(lock.tryLock(timeout.toNanos, TimeUnit.NANOSECONDS)) {
      Using.resource(WriteGate.Unlock(lock))(_ => body)
    }

private[sqlite] object WriteGate:

  private val gates = mutable.HashMap.empty[Path, WriteGate]

  /** The gate of the file at `path`, counted as used until [[release]]. */
  def acquire(path: Path): WriteGate = synchronized {
    val key  = keyOf(path)
    val gate = gates.getOrElseUpdate(key, new WriteGate(key))
    gate.users += 1
    gate
  }

  /** Gives back a gate from [[acquire]]; the last release drops it. */
  def release(gate: WriteGate): Unit = synchronized {
    gate.users -= 1
    if gate.users <= 0 then gates.remove(gate.key): Unit
  }

  /** The gate the stores on `path` share now, if any store holds it: for tests. */
  private[sqlite] def of(path: Path): Option[WriteGate] = synchronized(gates.get(keyOf(path)))

  /**
   * The file's directory by its real path, so `/var/...` and `/private/var/...` on macOS, or a relative and an
   * absolute path, name one gate; the file itself may not exist yet, so its name is taken as given.
   */
  private def keyOf(path: Path): Path =
    val absolute = path.toAbsolutePath.normalize
    Option(absolute.getParent)
      .flatMap(dir => Try(dir.toRealPath().resolve(absolute.getFileName)).toOption)
      .getOrElse(absolute)

  final private class Unlock(lock: ReentrantLock) extends AutoCloseable:
    def close(): Unit = lock.unlock()
