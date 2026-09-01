plugins {
    java
    application
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("org.llm4s:core_3:0.1.16")
    implementation("org.scala-lang:scala3-library_3:3.3.3")
}

application {
    mainClass.set("org.llm4s.samples.HelloLLM4S")
}
