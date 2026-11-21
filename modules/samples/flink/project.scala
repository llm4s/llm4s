// A Flink 2.x job in Scala 3 that enriches a stream with LLM4S calls, through Flink's Java DataStream API (Flink 2.0
// removed its Scala API). Not part of the sbt build and never published: see docs/guide/flink.md.
//
//   cd modules/samples/flink
//   scala-cli run . -- --scripted   # a scripted model: no key, no network
//   scala-cli test .                # the MiniCluster test
//
//> using scala 3.7.1
//> using jvm 21
//> using dep org.apache.flink:flink-streaming-java:2.3.0
//> using dep org.apache.flink:flink-clients:2.3.0
//> using dep org.llm4s::llm4s-core:0.6.0
//> using dep org.llm4s::llm4s-ollama:0.6.0
// The scripted model behind `--scripted` and the test; a real job leaves this out.
//> using dep org.llm4s::llm4s-testkit:0.6.0
//> using test.dep org.apache.flink:flink-test-utils:2.3.0
//> using test.dep org.scalameta::munit:1.3.6
//> using resourceDir resources
