// Shared Scala CLI settings for every probe: a Scala 2.13 program reading llm4s's Scala 3 artifacts. The llm4s
// artifacts themselves are not pinned here: run-probes.sh adds them, at the latest release (`--release`, the default
// for a manual run) or at the version a local `publishLocal` of this checkout produced (`--local VERSION`, what CI
// runs), and the command line's Scala version replaces the one below.
//> using scala 2.13.16
//> using jvm 21
//> using option -Ytasty-reader
//> using dep org.slf4j:slf4j-nop:2.0.17
