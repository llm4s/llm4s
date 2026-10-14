package org.llm4s.agent.harness

import java.nio.channels.FileChannel
import java.nio.file.{ Files, Paths, StandardOpenOption }

/**
 * Run in a child JVM by `HostFileBackendSpec`: takes the OS lock on the file `args(0)`, prints `locked`,
 * and holds it until its standard input closes - another process mutating the same host root.
 */
object HoldLock {
  def main(args: Array[String]): Unit = {
    val file = Paths.get(args(0))
    Files.createDirectories(file.getParent)
    val channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    val held    = channel.lock()
    println("locked")
    System.out.flush()
    while (System.in.read() >= 0) {}
    held.release()
    channel.close()
  }
}
