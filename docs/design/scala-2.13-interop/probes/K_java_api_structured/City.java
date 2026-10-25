// The record JLlmClient.complete(prompt, City.class) reads the model's JSON into. A Scala 2.13 case class is not a
// Java record, so a 2.13 project declares the record in a Java source file, which sbt compiles alongside its Scala.
public record City(String name, String country, int population) {}
