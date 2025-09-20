package org.llm4s.samples.dbx

import org.llm4s.llmconnect.config.dbx.DbxConfig
import org.llm4s.llmconnect.DbxClient

object CoreInit extends App {
  def main(args: Array[String]): Unit = {
    val cfg = DbxConfig.load()
    val client = new DbxClient(cfg)
    try {
      client.initCore() match {
        case Right(report) =>
          println(s"✅ DBx Core initialized successfully!")
          println(s"  Connection OK: ${report.connectionOk}")
          println(s"  Schema OK: ${report.schemaOk}")
          println(s"  PGVector Version: ${report.pgvectorVersion.getOrElse("N/A")}")
          println(s"  Write OK: ${report.writeOk}")

          // Show pool stats
          val stats = client.getPoolStats()
          println(s"\nConnection Pool Stats:")
          println(s"  Active: ${stats.activeConnections}")
          println(s"  Idle: ${stats.idleConnections}")
          println(s"  Total: ${stats.totalConnections}")

        case Left(err) =>
          System.err.println(s"❌ Failed to initialize DBx Core: ${err.message}")
          sys.exit(1)
      }
    } finally {
      client.close()
    }
  }
}