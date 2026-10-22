package org.llm4s.agent.harness

import java.nio.file.Paths

/**
 * Run in a child JVM by `HostFileBackendSpec`: opens a `HostFileBackend` over `args(0)` with the lock
 * directory `args(1)`, prints `open` (or `refused: <why>`), and holds it until its standard input closes -
 * another process with the root open. `args(2)` is `unprobed` where the spec stubs the 8.3 probe.
 */
object HoldRoot {
  def main(args: Array[String]): Unit =
    // the identity checks are the parent's to test; this child only holds the lock
    HostFileBackend.openWith(
      Paths.get(args(0)),
      Paths.get(args(1)),
      allowUncheckedIdentity = true,
      probe = if (args.lift(2).contains("unprobed")) _ => Right(Vector.empty) else HostFileBackend.probeAliases
    ) match {
      case Right(backend) =>
        println("open")
        System.out.flush()
        while (System.in.read() >= 0) {}
        backend.close()
      case Left(error) =>
        println(s"refused: ${error.message}")
        System.out.flush()
    }
}
