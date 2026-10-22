// A Spark job (Scala 2.13, like Spark and Databricks) that calls LLM4S's Scala 3 artifacts through the TASTy reader.
// Not part of the sbt build and never published: see docs/guide/spark.md.
//> using scala 2.13.16
//> using jvm 21
//> using option -Ytasty-reader
//> using dep org.apache.spark:spark-sql_2.13:4.0.1
//> using dep org.llm4s:llm4s-core_3:0.5.1
//> using dep org.llm4s:llm4s-ollama_3:0.5.1
//> using javaOpt --add-opens=java.base/sun.nio.ch=ALL-UNNAMED
//> using javaOpt --add-opens=java.base/java.lang=ALL-UNNAMED
//> using javaOpt --add-opens=java.base/java.util=ALL-UNNAMED
//> using javaOpt --add-opens=java.base/java.nio=ALL-UNNAMED
