package org.llm4s.samples.dbx


import org.llm4s.llmconnect.config.dbx.DbxConfig
import org.llm4s.llmconnect.DbxClient


object CoreInit extends App {
  def main(args: Array[String]): Unit = {
    val cfg = DbxConfig.load()
    val client = new DbxClient(cfg)
    client.initCore() match {
      case Right(_) => println("yes, we are on PGVector")
      case Left(err) =>
        System.err.println(err.message)
        sys.exit(1)
    }
  }
}