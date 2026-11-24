# LLM4S Hardware Design Extension
## Implementation Roadmap & Technical Overview

**Version:** 1.0  
**Date:** December 2024  
**Author:** LLM4S Community  
**Status:** Proposal

---

## Executive Summary

This document outlines a roadmap for extending LLM4S to support hardware design workflows, with particular focus on the Chisel/FIRRTL ecosystem. The goal is to bring the power of agentic LLM tooling to hardware engineers, addressing critical pain points in verification, design exploration, and documentation while leveraging the natural synergy of Scala-based tooling.

Hardware design represents an ideal domain for LLM assistance: it has well-defined semantics, rich type systems, long iteration cycles, and verification-dominated workflows where AI can provide substantial leverage. By building on LLM4S's existing agent and tool infrastructure, we can create a first-class hardware design experience that integrates naturally with existing Chisel workflows.

---

## Vision & Goals

### Vision

Enable hardware engineers to leverage LLM-powered agents throughout their design workflow—from natural language specification through verified, synthesizable RTL—while maintaining the rigour and correctness guarantees that hardware design demands.

### Primary Goals

1. **Accelerate Verification** — Reduce the 50-60% of project time spent on verification through intelligent test generation and coverage closure
2. **Lower Barriers to Entry** — Make hardware design more accessible through natural language interfaces and intelligent assistance
3. **Enable Design Exploration** — Automate design space exploration to find optimal area/timing/power trade-offs
4. **Preserve Rigour** — Ensure all generated hardware passes elaboration, simulation, and formal checks

### Non-Goals

- Replacing hardware engineers or their judgment
- Generating production RTL without human review
- Supporting non-Chisel HDLs initially (Verilog/VHDL support is a future consideration)

---

## Architecture Overview

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              User Interface Layer                            │
├─────────────────────────────────────────────────────────────────────────────┤
│  CLI Tools  │  REPL Integration  │  IDE Plugins  │  Web Interface (future)  │
└──────┬──────┴─────────┬──────────┴───────┬───────┴────────────┬─────────────┘
       │                │                  │                    │
       ▼                ▼                  ▼                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Agent Layer                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────────┐  ┌──────────────────┐   │
│  │   Design    │  │ Verification │  │    DSE      │  │  Documentation   │   │
│  │   Agent     │  │    Agent     │  │   Agent     │  │     Agent        │   │
│  └──────┬──────┘  └──────┬───────┘  └──────┬──────┘  └────────┬─────────┘   │
│         │                │                 │                  │              │
│         └────────────────┼─────────────────┼──────────────────┘              │
│                          │                 │                                 │
│                          ▼                 ▼                                 │
│                    ┌─────────────────────────────┐                           │
│                    │     Agent Orchestrator      │                           │
│                    │   (Multi-agent workflows)   │                           │
│                    └─────────────────────────────┘                           │
│                                                                              │
└──────────────────────────────────┬──────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Core Services                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────────────┐  │
│  │  Schema Library │  │  RAG Knowledge  │  │     Prompt Management       │  │
│  │  (HW types)     │  │     Base        │  │     (HW-specific)           │  │
│  └─────────────────┘  └─────────────────┘  └─────────────────────────────┘  │
│                                                                              │
└──────────────────────────────────┬──────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Tool Layer                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌───────────┐  │
│  │Elaboration │ │ Simulation │ │ Synthesis  │ │  Formal    │ │ Waveform  │  │
│  │   Tool     │ │    Tool    │ │   Tool     │ │   Tool     │ │  Parser   │  │
│  └─────┬──────┘ └─────┬──────┘ └─────┬──────┘ └─────┬──────┘ └─────┬─────┘  │
│        │              │              │              │              │         │
└────────┼──────────────┼──────────────┼──────────────┼──────────────┼─────────┘
         │              │              │              │              │
         ▼              ▼              ▼              ▼              ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         External Tool Integration                            │
├─────────────────────────────────────────────────────────────────────────────┤
│  Chisel/FIRRTL  │  Verilator  │  Yosys  │  SymbiYosys  │  Vivado/Quartus    │
└─────────────────┴─────────────┴─────────┴──────────────┴────────────────────┘
```

### Module Structure

```
llm4s-hardware/
├── build.sbt
├── core/
│   └── src/main/scala/llm4s/hardware/
│       ├── HardwareContext.scala        # Design context management
│       ├── ChiselIntegration.scala      # Chisel toolchain integration
│       └── FirrtlAnalysis.scala         # FIRRTL parsing and analysis
│
├── schemas/
│   └── src/main/scala/llm4s/hardware/schemas/
│       ├── PortSchema.scala             # I/O port definitions
│       ├── ModuleSchema.scala           # Module specifications
│       ├── BehaviourSchema.scala        # Behavioural specifications
│       ├── TimingSchema.scala           # Timing constraints
│       ├── TestSchema.scala             # Test scenario definitions
│       └── MetricsSchema.scala          # Design metrics (area/timing/power)
│
├── tools/
│   └── src/main/scala/llm4s/hardware/tools/
│       ├── ElaborationTool.scala        # Chisel → FIRRTL
│       ├── SimulationTool.scala         # Verilator/VCS wrapper
│       ├── SynthesisTool.scala          # Yosys/Vivado wrapper
│       ├── FormalTool.scala             # SymbiYosys wrapper
│       ├── CoverageTool.scala           # Coverage analysis
│       └── WaveformTool.scala           # VCD parsing
│
├── agents/
│   └── src/main/scala/llm4s/hardware/agents/
│       ├── DesignAgent.scala            # RTL generation
│       ├── VerificationAgent.scala      # Test generation
│       ├── OptimizationAgent.scala      # DSE agent
│       ├── DocumentationAgent.scala     # Doc generation
│       └── MigrationAgent.scala         # Verilog → Chisel
│
├── rag/
│   └── src/main/scala/llm4s/hardware/rag/
│       ├── ChiselKnowledgeBase.scala    # Chisel-specific RAG
│       ├── DesignPatterns.scala         # HDL pattern library
│       └── Indexers.scala               # Document indexing
│
├── pipelines/
│   └── src/main/scala/llm4s/hardware/pipelines/
│       ├── NL2Chisel.scala              # NL → Chisel pipeline
│       ├── VerificationPipeline.scala   # Automated verification
│       └── DSEPipeline.scala            # Design space exploration
│
└── examples/
    └── src/main/scala/llm4s/hardware/examples/
        ├── FifoDesign.scala             # FIFO generation example
        ├── ArbiterDesign.scala          # Arbiter example
        └── RegisterFile.scala           # Register file example
```

---

## Implementation Phases

### Phase 0: Foundation (Weeks 1-4)

**Objective:** Establish project infrastructure and validate core integration approach.

#### Deliverables

| Deliverable | Description | Success Criteria |
|-------------|-------------|------------------|
| Project scaffold | SBT multi-module project with CI | Builds on CI, publishes snapshots |
| Chisel dependency integration | Chisel 6.x as provided dependency | Can import Chisel types |
| Basic elaboration tool | Compile Chisel string → FIRRTL | Elaborates simple counter |
| Error parsing prototype | Parse common Chisel errors | Extracts line/col from 5 error types |
| Integration test suite | Tests against known Chisel snippets | 90%+ pass rate on test corpus |

#### Technical Decisions

**Chisel Version Strategy**
```scala
// build.sbt
val chiselVersion = "6.0.0"

lazy val core = project
  .settings(
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion % Provided,
      "edu.berkeley.cs" %% "chiseltest" % "6.0.0" % Test
    )
  )
```

We use `Provided` scope to avoid forcing a specific Chisel version on users. The user's project provides Chisel; we adapt to it.

**Elaboration Sandbox**

Chisel elaboration executes user code—we need isolation:

```scala
trait ElaborationSandbox {
  def elaborate(
    source: String,
    moduleName: String,
    timeout: Duration = 30.seconds,
    memoryLimit: Long = 512.megabytes
  ): ElaborationResult
}

// Initial implementation: subprocess with resource limits
class SubprocessSandbox extends ElaborationSandbox {
  def elaborate(source: String, moduleName: String, timeout: Duration, memoryLimit: Long) = {
    // Write source to temp file
    // Spawn JVM subprocess with memory limits
    // Parse stdout/stderr
    // Return structured result
  }
}
```

#### Milestone: M0 — "Hello Hardware"

A simple demonstration that the integration works:

```scala
val tool = ElaborationTool()
val result = tool.execute("""
  |import chisel3._
  |class Counter(width: Int) extends Module {
  |  val io = IO(new Bundle {
  |    val count = Output(UInt(width.W))
  |  })
  |  val reg = RegInit(0.U(width.W))
  |  reg := reg + 1.U
  |  io.count := reg
  |}
""".stripMargin, moduleName = "Counter")

assert(result.success)
assert(result.firrtl.isDefined)
println(s"Generated ${result.firrtl.get.lines.size} lines of FIRRTL")
```

---

### Phase 1: Core Tools (Weeks 5-10)

**Objective:** Build the foundational tool suite that agents will use.

#### Tool Specifications

**1. Elaboration Tool (Enhanced)**

```scala
case class ElaborationInput(
  sourceCode: String,
  moduleName: String,
  dependencies: List[String] = Nil,
  chiselOptions: ChiselOptions = ChiselOptions.default
)

case class ElaborationOutput(
  success: Boolean,
  firrtl: Option[FirrtlModule],
  errors: List[StructuredError],
  warnings: List[StructuredWarning],
  elaborationTimeMs: Long,
  hierarchy: Option[ModuleHierarchy],
  ports: Option[List[PortInfo]]
)

case class StructuredError(
  errorType: ErrorType,
  message: String,
  location: Option[SourceLocation],
  context: Option[String],           // Surrounding code
  suggestion: Option[String]         // LLM-friendly fix suggestion
)

enum ErrorType:
  case SyntaxError
  case TypeMismatch
  case WidthMismatch
  case UnconnectedWire
  case UnusedSignal
  case MultipleDrivers
  case ClockDomainCrossing
  case ElaborationException
  case Unknown
```

**2. Simulation Tool**

```scala
case class SimulationInput(
  firrtl: String,
  testbench: String,                 // ChiselTest or raw Verilator TB
  simulationCycles: Int = 10000,
  enableWaveform: Boolean = false,
  coverageEnabled: Boolean = true
)

case class SimulationOutput(
  success: Boolean,
  passed: Boolean,
  cyclesExecuted: Int,
  simulationTimeMs: Long,
  assertions: List[AssertionResult],
  coverage: Option[CoverageReport],
  waveformPath: Option[String],
  stdout: String,
  stderr: String
)

case class AssertionResult(
  name: String,
  passed: Boolean,
  failureCycle: Option[Int],
  failureMessage: Option[String]
)
```

**3. Synthesis Tool**

```scala
case class SynthesisInput(
  firrtl: String,
  targetPlatform: SynthesisTarget,
  constraints: SynthesisConstraints,
  optimizationLevel: OptLevel = OptLevel.Default
)

enum SynthesisTarget:
  case Yosys(techLib: String)                    // Generic synthesis
  case XilinxVivado(part: String)                // e.g., "xc7a100t-csg324-1"
  case IntelQuartus(part: String)
  case ASIC(techNode: String, liberty: String)

case class SynthesisOutput(
  success: Boolean,
  area: AreaMetrics,
  timing: TimingMetrics,
  power: Option[PowerMetrics],
  criticalPaths: List[CriticalPath],
  utilizationReport: String,
  synthesisLog: String
)

case class AreaMetrics(
  luts: Int,
  registers: Int,
  brams: Int,
  dsps: Int,
  ios: Int,
  // Normalized metric for comparison
  normalizedArea: Double
)

case class TimingMetrics(
  targetFrequencyMhz: Double,
  achievedFrequencyMhz: Double,
  worstNegativeSlackNs: Double,
  totalNegativeSlackNs: Double,
  criticalPathNs: Double,
  metTiming: Boolean
)
```

**4. Formal Verification Tool**

```scala
case class FormalInput(
  firrtl: String,
  properties: List[FormalProperty],
  engine: FormalEngine = FormalEngine.SymbiYosys,
  depth: Int = 20,
  timeout: Duration = 5.minutes
)

case class FormalProperty(
  name: String,
  propertyType: PropertyType,
  expression: String,           // SVA or FIRRTL assertion syntax
  description: String
)

enum PropertyType:
  case Assert                   // Must always hold
  case Assume                   // Constrain inputs
  case Cover                    // Should be reachable
  case Live                     // Eventually holds

case class FormalOutput(
  success: Boolean,
  results: List[PropertyResult],
  counterexamples: List[Counterexample],
  proofTimeMs: Long
)

case class PropertyResult(
  property: String,
  status: PropertyStatus,
  depth: Int,
  counterexample: Option[Counterexample]
)

enum PropertyStatus:
  case Proven
  case Failed
  case Unknown
  case Timeout
```

**5. Waveform Analysis Tool**

```scala
case class WaveformInput(
  vcdPath: String,
  signals: List[String],         // Signals to extract (supports glob)
  timeRange: Option[(Long, Long)],
  format: WaveformFormat = WaveformFormat.Structured
)

case class WaveformOutput(
  signals: Map[String, SignalTrace],
  timeRange: (Long, Long),
  transitionCount: Map[String, Int]
)

case class SignalTrace(
  name: String,
  width: Int,
  changes: List[SignalChange]    // Only transitions, not every cycle
)

case class SignalChange(
  time: Long,
  value: BigInt,
  valueHex: String
)
```

#### Deliverables

| Deliverable | Description | Week |
|-------------|-------------|------|
| Elaboration Tool v1 | Full error parsing, hierarchy extraction | 6 |
| Simulation Tool | Verilator integration, coverage parsing | 7 |
| Synthesis Tool (Yosys) | Open-source synthesis path | 8 |
| Formal Tool | SymbiYosys integration | 9 |
| Waveform Tool | VCD parsing and analysis | 9 |
| Tool test suite | Comprehensive tool tests | 10 |
| Documentation | Tool usage documentation | 10 |

#### Milestone: M1 — "Tool Suite Complete"

All five tools working with a demonstration workflow:

```scala
// Complete flow: elaborate → simulate → synthesize → formal check
for {
  elab <- elaborationTool.run(chiselSource)
  sim <- simulationTool.run(elab.firrtl.get, testbench)
  synth <- synthesisTool.run(elab.firrtl.get, XilinxVivado("xc7a100t"))
  formal <- formalTool.run(elab.firrtl.get, properties)
} yield DesignReport(elab, sim, synth, formal)
```

---

### Phase 2: Schema Library (Weeks 11-14)

**Objective:** Define structured types for hardware specifications that enable reliable LLM generation.

#### Schema Definitions

**Core Hardware Types**

```scala
package llm4s.hardware.schemas

import llm4s.schema._

// Direction enumeration
enum Direction derives Schema:
  case Input, Output, Inout

// Clock and reset specification  
case class ClockSpec(
  name: String,
  frequencyHz: Option[Long],
  description: String
) derives Schema

case class ResetSpec(
  name: String,
  activeLow: Boolean,
  asynchronous: Boolean
) derives Schema

// Port specification with full metadata
case class PortSpec(
  name: String,
  direction: Direction,
  width: Int,
  description: String,
  signedness: Signedness = Signedness.Unsigned,
  protocol: Option[String] = None,
  clockDomain: Option[String] = None
) derives Schema

enum Signedness derives Schema:
  case Signed, Unsigned

// Standard interface protocols
case class AXI4LiteInterface(
  name: String,
  dataWidth: Int,           // 32 or 64
  addrWidth: Int,
  role: InterfaceRole
) derives Schema

case class AXI4StreamInterface(
  name: String,
  dataWidth: Int,
  hasKeep: Boolean = false,
  hasLast: Boolean = true,
  userId: Int = 0,
  destId: Int = 0
) derives Schema

case class ValidReadyInterface(
  name: String,
  dataType: String,
  dataWidth: Int
) derives Schema

enum InterfaceRole derives Schema:
  case Manager, Subordinate
```

**Module Specification Schema**

```scala
// Complete module interface specification
case class ModuleInterfaceSpec(
  moduleName: String,
  description: String,
  parameters: List[ParameterSpec],
  clocks: List[ClockSpec],
  resets: List[ResetSpec],
  ports: List[PortSpec],
  standardInterfaces: List[StandardInterface]
) derives Schema

case class ParameterSpec(
  name: String,
  paramType: ParameterType,
  defaultValue: Option[String],
  validRange: Option[String],      // e.g., "4 to 256", "power of 2"
  description: String
) derives Schema

enum ParameterType derives Schema:
  case IntParam, BoolParam, StringParam

// Behavioural specification
case class BehaviourSpec(
  summary: String,
  ioRelationships: List[IORelationship],
  latency: LatencySpec,
  throughput: Option[ThroughputSpec],
  stateDescription: Option[String],
  resetBehaviour: String
) derives Schema

case class IORelationship(
  inputs: List[String],
  outputs: List[String],
  relationship: String,         // Natural language description
  latencyCycles: Int,
  conditions: Option[String]    // When this relationship holds
) derives Schema

case class LatencySpec(
  typical: Int,
  minimum: Int,
  maximum: Option[Int],
  variableLatency: Boolean,
  description: String
) derives Schema

// Complete hardware module specification
case class HardwareModuleSpec(
  interface: ModuleInterfaceSpec,
  behaviour: BehaviourSpec,
  constraints: DesignConstraints,
  verificationHints: List[String],
  implementationNotes: Option[String]
) derives Schema

case class DesignConstraints(
  targetFrequencyMhz: Option[Double],
  maxArea: Option[AreaBudget],
  maxPower: Option[Double],
  mustSupportBackpressure: Boolean,
  pipelined: Boolean
) derives Schema
```

**Verification Schema**

```scala
// Test scenario specification
case class TestScenario(
  name: String,
  description: String,
  category: TestCategory,
  setup: TestSetup,
  stimulus: List[StimulusStep],
  expected: List[ExpectedBehaviour],
  coverageTargets: List[String]
) derives Schema

enum TestCategory derives Schema:
  case Functional
  case Boundary
  case ErrorHandling
  case Performance
  case Protocol
  case Random

case class TestSetup(
  resetCycles: Int,
  initialState: Map[String, String],
  configuration: Map[String, String]
) derives Schema

case class StimulusStep(
  cycle: Int,                      // Relative or absolute
  signals: Map[String, String],
  description: String,
  waitCondition: Option[String]    // Optional condition to wait for
) derives Schema

case class ExpectedBehaviour(
  cycle: Int,
  signals: Map[String, String],
  tolerance: Option[Int],          // Cycles of tolerance
  description: String
) derives Schema

// Coverage specification
case class CoverageSpec(
  functionalPoints: List[FunctionalCoverpoint],
  fsmCoverage: List[FSMCoverageSpec],
  crossCoverage: List[CrossCoverageSpec]
) derives Schema

case class FunctionalCoverpoint(
  name: String,
  signal: String,
  bins: List[CoverageBin],
  description: String
) derives Schema

case class CoverageBin(
  name: String,
  condition: String
) derives Schema
```

#### Schema Validation

Schemas should validate hardware-specific constraints:

```scala
object HardwareSchemaValidation {
  
  def validatePortSpec(port: PortSpec): List[ValidationError] = {
    val errors = ListBuffer[ValidationError]()
    
    if (port.width < 1 || port.width > 4096)
      errors += ValidationError(s"Port width ${port.width} out of reasonable range")
    
    if (!port.name.matches("[a-zA-Z_][a-zA-Z0-9_]*"))
      errors += ValidationError(s"Invalid port name: ${port.name}")
    
    errors.toList
  }
  
  def validateModuleSpec(spec: HardwareModuleSpec): List[ValidationError] = {
    val errors = ListBuffer[ValidationError]()
    
    // Check for clock domain consistency
    val declaredDomains = spec.interface.clocks.map(_.name).toSet
    val usedDomains = spec.interface.ports.flatMap(_.clockDomain).toSet
    val undeclaredDomains = usedDomains -- declaredDomains
    if (undeclaredDomains.nonEmpty)
      errors += ValidationError(s"Undeclared clock domains: $undeclaredDomains")
    
    // Check port names are unique
    val portNames = spec.interface.ports.map(_.name)
    if (portNames.size != portNames.distinct.size)
      errors += ValidationError("Duplicate port names")
    
    // Validate IO relationships reference valid ports
    val allPorts = portNames.toSet
    for (rel <- spec.behaviour.ioRelationships) {
      val unknownInputs = rel.inputs.toSet -- allPorts
      val unknownOutputs = rel.outputs.toSet -- allPorts
      if (unknownInputs.nonEmpty)
        errors += ValidationError(s"Unknown inputs in relationship: $unknownInputs")
      if (unknownOutputs.nonEmpty)
        errors += ValidationError(s"Unknown outputs in relationship: $unknownOutputs")
    }
    
    errors.toList
  }
}
```

#### Deliverables

| Deliverable | Week |
|-------------|------|
| Core hardware type schemas | 11 |
| Module specification schema | 12 |
| Verification schemas | 13 |
| Schema validation logic | 13 |
| Schema documentation & examples | 14 |

#### Milestone: M2 — "Structured Hardware Specs"

LLM can generate valid, parseable hardware specifications:

```scala
val spec = llm.generate[HardwareModuleSpec]("""
  Design a synchronous FIFO with:
  - Parameterizable depth (4-256, power of 2)
  - Parameterizable width (8-64 bits)
  - Valid/ready handshaking on both ports
  - Full, empty, and almost-full flags
  - Single clock domain
""")

// spec is guaranteed to parse and validate
val errors = HardwareSchemaValidation.validateModuleSpec(spec)
assert(errors.isEmpty)
```

---

### Phase 3: Agent Development (Weeks 15-24)

**Objective:** Build specialised agents for key hardware design tasks.

#### Agent 1: Design Agent

**Purpose:** Generate Chisel implementations from specifications or natural language descriptions.

```scala
class DesignAgent(
  llm: LLMClient,
  tools: DesignToolkit,
  knowledgeBase: ChiselKnowledgeBase
) {
  
  val systemPrompt = """You are an expert Chisel hardware design engineer. Your role is to 
    generate correct, idiomatic, synthesizable Chisel code from specifications.
    
    Design Principles:
    - Always use Chisel 6.x idioms and best practices
    - Prefer parameterized, reusable designs
    - Handle reset explicitly and correctly
    - Use meaningful signal names that reflect function
    - Add comments for non-obvious logic
    - Consider timing and area implications
    - Always handle backpressure correctly in streaming designs
    
    Code Style:
    - Use camelCase for signals, PascalCase for modules
    - Group related signals in Bundles
    - Use Scala's type system to prevent errors
    - Prefer functional patterns over imperative
    
    When you generate code:
    1. First understand the specification completely
    2. Identify the key interfaces and protocols
    3. Plan the internal architecture
    4. Generate the code incrementally
    5. Validate by elaboration after each major component
    6. Iterate on errors until elaboration succeeds"""
  
  def generateFromSpec(spec: HardwareModuleSpec): Task[GeneratedDesign] = {
    // Retrieve relevant patterns and examples
    val context = knowledgeBase.retrievePatterns(spec)
    
    // Multi-step generation with validation
    for {
      // Step 1: Generate module skeleton
      skeleton <- generateSkeleton(spec)
      _ <- validateElaboration(skeleton)
      
      // Step 2: Implement core logic
      withLogic <- implementLogic(skeleton, spec)
      _ <- validateElaboration(withLogic)
      
      // Step 3: Add protocol handling
      withProtocol <- addProtocolLogic(withLogic, spec)
      _ <- validateElaboration(withProtocol)
      
      // Step 4: Final polish and comments
      final <- polishCode(withProtocol, spec)
      elaborationResult <- tools.elaborate(final)
      
    } yield GeneratedDesign(
      spec = spec,
      code = final,
      elaboration = elaborationResult
    )
  }
  
  def iterativeRefinement(
    code: String,
    feedback: DesignFeedback,
    maxIterations: Int = 5
  ): Task[String] = {
    // Agent loop: modify code based on feedback until success
    def loop(current: String, iteration: Int): Task[String] = {
      if (iteration >= maxIterations) 
        Task.raiseError(new Exception("Max iterations exceeded"))
      else
        applyFeedback(current, feedback).flatMap { modified =>
          tools.elaborate(modified).flatMap {
            case result if result.success => Task.pure(modified)
            case result => loop(modified, iteration + 1)
          }
        }
    }
    loop(code, 0)
  }
}

case class GeneratedDesign(
  spec: HardwareModuleSpec,
  code: String,
  elaboration: ElaborationOutput,
  generationTrace: List[GenerationStep] = Nil
)

case class GenerationStep(
  description: String,
  codeSnapshot: String,
  elaborationResult: Option[ElaborationOutput]
)
```

#### Agent 2: Verification Agent

**Purpose:** Generate comprehensive test suites and close coverage gaps.

```scala
class VerificationAgent(
  llm: LLMClient,
  tools: VerificationToolkit,
  knowledgeBase: ChiselKnowledgeBase
) {
  
  val systemPrompt = """You are an expert hardware verification engineer specializing in 
    functional verification, coverage analysis, and formal methods.
    
    Your Verification Philosophy:
    - Think adversarially: what inputs would break this design?
    - Cover corner cases: boundaries, empty/full, overflow/underflow
    - Test protocol compliance rigorously
    - Verify reset behaviour
    - Consider concurrent operations and race conditions
    - Use constrained random where appropriate
    
    Test Categories to Consider:
    1. Directed tests for specific functionality
    2. Boundary condition tests
    3. Error injection and recovery tests  
    4. Protocol compliance tests
    5. Performance and throughput tests
    6. Reset and initialisation tests
    7. Constrained random stress tests
    
    Coverage Strategy:
    - Identify functional coverage points from the spec
    - Create covergroups for FSM states and transitions
    - Cross-coverage for interacting signals
    - Toggle coverage for critical signals
    
    When analyzing coverage gaps:
    1. Understand what condition is uncovered
    2. Determine what input sequence would exercise it
    3. Generate a targeted test
    4. Verify the test hits the coverage point"""
  
  def generateTestSuite(
    spec: HardwareModuleSpec,
    implementation: String
  ): Task[TestSuite] = {
    for {
      // Analyze design structure
      analysis <- analyzeDesign(implementation)
      
      // Generate tests by category
      functionalTests <- generateFunctionalTests(spec, analysis)
      boundaryTests <- generateBoundaryTests(spec, analysis)
      protocolTests <- generateProtocolTests(spec, analysis)
      randomTests <- generateRandomTests(spec, analysis)
      
      // Combine into suite
      suite = TestSuite(
        functional = functionalTests,
        boundary = boundaryTests,
        protocol = protocolTests,
        random = randomTests
      )
      
      // Validate tests compile
      validated <- validateTestSuite(suite, implementation)
      
    } yield validated
  }
  
  def closeCoverageGaps(
    coverage: CoverageReport,
    spec: HardwareModuleSpec,
    implementation: String,
    existingTests: TestSuite
  ): Task[List[TestScenario]] = {
    
    val uncoveredPoints = coverage.uncoveredConditions
    
    // Generate targeted tests for each gap
    uncoveredPoints.traverse { gap =>
      for {
        analysis <- analyzeGap(gap, implementation)
        scenario <- generateTargetedTest(gap, analysis, spec)
        validated <- validateScenario(scenario, implementation)
      } yield validated
    }
  }
  
  def generateFormalProperties(
    spec: HardwareModuleSpec,
    implementation: String
  ): Task[List[FormalProperty]] = {
    // Extract invariants, liveness properties, protocol rules
    for {
      invariants <- extractInvariants(spec)
      liveness <- extractLivenessProperties(spec)
      protocol <- extractProtocolProperties(spec)
    } yield invariants ++ liveness ++ protocol
  }
}

case class TestSuite(
  functional: List[TestCase],
  boundary: List[TestCase],
  protocol: List[TestCase],
  random: List[TestCase]
) {
  def toChiselTest: String = {
    // Generate ChiselTest Scala code
    ???
  }
}
```

#### Agent 3: DSE (Design Space Exploration) Agent

**Purpose:** Explore parameter space to find optimal designs.

```scala
class DSEAgent(
  llm: LLMClient,
  tools: DSEToolkit
) {
  
  val systemPrompt = """You are a hardware design space exploration agent. Your goal is to 
    efficiently explore parameterized designs to find Pareto-optimal configurations.
    
    Exploration Strategy:
    - Start with extreme points to understand the design space bounds
    - Use domain knowledge to form hypotheses about parameter interactions
    - Test hypotheses with targeted experiments
    - Use binary search to find Pareto boundaries
    - Don't exhaustively enumerate—be intelligent
    
    Hardware Trade-off Knowledge:
    - Pipeline depth vs latency vs frequency vs registers
    - Parallelism vs area vs throughput
    - Memory depth vs BRAM count
    - Operator sharing vs area vs throughput
    - Fixed-point width vs accuracy vs area
    
    When reporting results:
    - Clearly identify Pareto-optimal points
    - Explain trade-offs in accessible terms
    - Recommend based on user's priorities
    - Note any surprising findings"""
  
  def explore(
    design: ParameterizedDesign,
    constraints: DSEConstraints,
    objectives: List[Objective]
  ): Task[DSEResult] = {
    
    for {
      // Phase 1: Bound the space
      bounds <- exploreBounds(design)
      
      // Phase 2: Sample strategically
      samples <- strategicSampling(design, bounds, objectives)
      
      // Phase 3: Refine Pareto frontier
      refined <- refineParetoFrontier(samples, objectives)
      
      // Phase 4: Generate recommendations
      recommendations <- generateRecommendations(refined, constraints)
      
    } yield DSEResult(
      paretoFrontier = refined,
      allPoints = samples,
      recommendations = recommendations
    )
  }
  
  private def strategicSampling(
    design: ParameterizedDesign,
    bounds: DesignBounds,
    objectives: List[Objective]
  ): Task[List[DesignPoint]] = {
    // LLM-guided sampling: ask agent which points to try next
    // based on results so far
    def sampleLoop(
      explored: List[DesignPoint],
      remaining: Int
    ): Task[List[DesignPoint]] = {
      if (remaining <= 0) Task.pure(explored)
      else {
        for {
          nextParams <- suggestNextPoint(explored, bounds, objectives)
          result <- synthesizeAndMeasure(design, nextParams)
          updated = explored :+ result
          continue <- sampleLoop(updated, remaining - 1)
        } yield continue
      }
    }
    
    sampleLoop(Nil, maxSamples)
  }
}

case class DSEResult(
  paretoFrontier: List[DesignPoint],
  allPoints: List[DesignPoint],
  recommendations: List[Recommendation]
)

case class Recommendation(
  point: DesignPoint,
  rationale: String,
  tradeoffs: String
)
```

#### Agent 4: Documentation Agent

**Purpose:** Generate and maintain hardware documentation.

```scala
class DocumentationAgent(
  llm: LLMClient,
  tools: DocumentationToolkit
) {
  
  def generateModuleDocumentation(
    chiselSource: String,
    spec: Option[HardwareModuleSpec]
  ): Task[ModuleDocumentation] = {
    for {
      // Parse Chisel to extract structure
      structure <- parseChiselStructure(chiselSource)
      
      // Generate sections
      overview <- generateOverview(structure, spec)
      portDocs <- generatePortDocumentation(structure)
      paramDocs <- generateParameterDocumentation(structure)
      usageExamples <- generateUsageExamples(structure)
      timingDiagram <- generateTimingDiagram(structure, spec)
      
    } yield ModuleDocumentation(
      overview = overview,
      ports = portDocs,
      parameters = paramDocs,
      usage = usageExamples,
      timing = timingDiagram
    )
  }
  
  def generateRegisterMap(
    registers: List[RegisterSpec]
  ): Task[RegisterMapDocumentation] = {
    // Generate register documentation with bit-field diagrams
    ???
  }
  
  def generateWavedromDiagram(
    scenario: TestScenario,
    signals: List[String]
  ): Task[String] = {
    // Generate WaveDrom JSON for timing diagrams
    ???
  }
}
```

#### Deliverables

| Deliverable | Week |
|-------------|------|
| Design Agent v1 | 17 |
| Verification Agent v1 | 20 |
| DSE Agent v1 | 22 |
| Documentation Agent v1 | 23 |
| Agent integration tests | 24 |
| Multi-agent orchestration | 24 |

#### Milestone: M3 — "Agentic Hardware Design"

Complete agent-driven workflow:

```scala
val orchestrator = HardwareOrchestrator(designAgent, verificationAgent, dseAgent, docAgent)

val result = orchestrator.run("""
  Create a parameterized arbiter:
  - Round-robin or priority-based (configurable)
  - 2-16 requestors (parameterized)
  - Single-cycle grant for priority, fair scheduling for RR
  - Generate full test suite
  - Find optimal implementation for Artix-7 at 200MHz
  - Generate documentation
""")

// Result includes:
// - Validated Chisel implementation
// - Comprehensive test suite
// - DSE results with Pareto frontier  
// - Complete documentation
```

---

### Phase 4: RAG & Knowledge Base (Weeks 25-30)

**Objective:** Build a comprehensive knowledge base for hardware design assistance.

#### Knowledge Sources

| Source | Content Type | Chunking Strategy |
|--------|--------------|-------------------|
| Chisel API docs | Method/class documentation | Per-method with context |
| Chisel Cookbook | Recipes and patterns | Per-recipe |
| FIRRTL Spec | Language specification | Per-section |
| Rocket Chip | Reference designs | Per-module with hierarchy |
| Stack Overflow | Q&A pairs | Per-question |
| Chisel Discourse | Community discussions | Per-thread |
| IEEE standards | Protocol specifications | Per-section |
| Design patterns | HDL patterns | Per-pattern |

#### Implementation

```scala
class ChiselKnowledgeBase(
  vectorStore: VectorStore,
  llm: LLMClient
) {
  
  // Specialized chunking for different document types
  val chunkers = Map(
    "scaladoc" -> ScaladocChunker,
    "markdown" -> MarkdownRecipeChunker,
    "protocol" -> ProtocolSpecChunker,
    "qa" -> QAChunker
  )
  
  def index(): Task[IndexStats] = {
    for {
      // Index official documentation
      chiselDocs <- indexChiselDocs()
      firrtlSpec <- indexFirrtlSpec()
      
      // Index community content
      cookbook <- indexCookbook()
      discourse <- indexDiscourse()
      
      // Index reference designs
      rocketChip <- indexRocketChip()
      
      // Index protocol specs
      protocols <- indexProtocolSpecs()
      
    } yield IndexStats(
      documents = chiselDocs + firrtlSpec + cookbook + discourse + rocketChip + protocols
    )
  }
  
  def query(
    question: String,
    context: DesignContext
  ): Task[RAGResult] = {
    // Enhance query with design context
    val enhancedQuery = enhanceQuery(question, context)
    
    for {
      // Multi-source retrieval
      apiResults <- queryApiDocs(enhancedQuery)
      patternResults <- queryPatterns(enhancedQuery)
      qaResults <- queryQA(enhancedQuery)
      
      // Merge and rerank
      merged = apiResults ++ patternResults ++ qaResults
      reranked <- rerank(merged, question)
      
      // Generate answer
      answer <- synthesize(question, reranked.take(5))
      
    } yield RAGResult(answer, reranked)
  }
  
  // Specialized retrievers
  def retrieveProtocolInfo(protocol: Protocol): Task[ProtocolInfo]
  def retrieveDesignPattern(patternType: PatternType): Task[List[Pattern]]
  def retrieveErrorFix(error: StructuredError): Task[List[FixSuggestion]]
}

// Context-aware query enhancement
def enhanceQuery(question: String, context: DesignContext): String = {
  s"""Question: $question
     |
     |Context:
     |  Target: ${context.targetPlatform}
     |  Chisel version: ${context.chiselVersion}
     |  Protocols in use: ${context.protocols.mkString(", ")}
     |  Module type: ${context.moduleType}
   """.stripMargin
}
```

#### Design Pattern Library

```scala
case class DesignPattern(
  name: String,
  category: PatternCategory,
  description: String,
  problem: String,
  solution: String,
  chiselExample: String,
  variants: List[PatternVariant],
  tradeoffs: String,
  relatedPatterns: List[String]
)

enum PatternCategory:
  case Dataflow      // FIFOs, pipelines, arbiters
  case StateMachine  // FSM patterns
  case Memory        // Memory interfaces, caches
  case Arithmetic    // Adders, multipliers, dividers
  case Synchronizer  // CDC patterns
  case Protocol      // Bus interfaces
  case Testing       // Verification patterns

// Example patterns to include
val patterns = List(
  DesignPattern(
    name = "Decoupled FIFO",
    category = PatternCategory.Dataflow,
    description = "A synchronous FIFO with valid/ready handshaking",
    problem = "Need to buffer data between producer and consumer with flow control",
    solution = "Use circular buffer with read/write pointers...",
    chiselExample = """
      class DecoupledFIFO[T <: Data](gen: T, depth: Int) extends Module {
        val io = IO(new Bundle {
          val enq = Flipped(Decoupled(gen))
          val deq = Decoupled(gen)
        })
        // ... implementation
      }
    """,
    variants = List(
      PatternVariant("First-word-fall-through", "Output valid before read"),
      PatternVariant("Almost-full threshold", "Early warning signal")
    ),
    tradeoffs = "FWFT adds output register, increases latency for empty FIFO",
    relatedPatterns = List("Async FIFO", "Credit-based flow control")
  ),
  // ... many more patterns
)
```

#### Deliverables

| Deliverable | Week |
|-------------|------|
| Vector store integration | 26 |
| Document indexers | 27 |
| Query enhancement | 28 |
| Reranking pipeline | 28 |
| Pattern library (20+ patterns) | 29 |
| RAG integration with agents | 30 |

#### Milestone: M4 — "Knowledge-Enhanced Agents"

Agents use RAG for informed responses:

```scala
val answer = designAgent.askWithRAG("""
  I need to implement an AXI4-Lite slave. What's the recommended 
  pattern for handling the write address and write data channels?
  Should I buffer them independently?
""")

// Agent retrieves:
// - AXI4-Lite spec sections on write channels
// - Chisel AXI4 library examples
// - Community Q&A on common pitfalls
// Then synthesizes an informed answer with code examples
```

---

### Phase 5: Pipelines & Integration (Weeks 31-36)

**Objective:** Build end-to-end pipelines and integrate with external tools.

#### NL2Chisel Pipeline

```scala
class NL2ChiselPipeline(
  designAgent: DesignAgent,
  verificationAgent: VerificationAgent,
  tools: HardwareToolkit
) {
  
  def generate(
    naturalLanguage: String,
    options: GenerationOptions = GenerationOptions.default
  ): Task[NL2ChiselResult] = {
    
    for {
      // Stage 1: Extract structured specification
      spec <- extractSpec(naturalLanguage)
      _ <- validateSpec(spec)
      
      // Stage 2: Generate implementation  
      design <- designAgent.generateFromSpec(spec)
      
      // Stage 3: Validate elaboration
      elaboration <- tools.elaborate(design.code)
      _ <- require(elaboration.success, s"Elaboration failed: ${elaboration.errors}")
      
      // Stage 4: Generate tests
      tests <- verificationAgent.generateTestSuite(spec, design.code)
      
      // Stage 5: Run tests
      testResults <- runTests(design.code, tests)
      
      // Stage 6: Optionally synthesize
      synthesis <- options.synthesize.traverse(_ => tools.synthesize(elaboration.firrtl.get))
      
    } yield NL2ChiselResult(
      specification = spec,
      implementation = design,
      tests = tests,
      testResults = testResults,
      synthesis = synthesis
    )
  }
  
  private def extractSpec(nl: String): Task[HardwareModuleSpec] = {
    designAgent.llm.generate[HardwareModuleSpec](
      prompt = s"""Extract a formal hardware specification from this description:
        
        $nl
        
        Be precise about:
        - All I/O ports with exact widths and directions
        - Parameters with valid ranges
        - Timing relationships (latency in cycles)
        - Protocol details (handshaking, flow control)
        - Edge cases and error conditions
        - Reset behaviour""",
      validate = HardwareSchemaValidation.validateModuleSpec
    )
  }
}

case class NL2ChiselResult(
  specification: HardwareModuleSpec,
  implementation: GeneratedDesign,
  tests: TestSuite,
  testResults: TestResults,
  synthesis: Option[SynthesisOutput]
) {
  def toReport: String = {
    // Generate human-readable report
    ???
  }
}
```

#### IDE Integration

```scala
// LSP-compatible interface for IDE integration
trait HardwareLanguageServer {
  
  // Real-time assistance as user types
  def onHover(position: Position): Task[HoverResult]
  def onCompletion(position: Position): Task[List[CompletionItem]]
  def onDiagnostic(document: Document): Task[List[Diagnostic]]
  
  // Agent-powered features
  def generateFromComment(comment: String): Task[String]
  def explainError(error: Diagnostic): Task[String]  
  def suggestFix(error: Diagnostic): Task[List[CodeAction]]
  def generateTest(selection: Range): Task[String]
}

class ChiselLanguageServer(
  agents: HardwareAgents,
  kb: ChiselKnowledgeBase
) extends HardwareLanguageServer {
  
  def generateFromComment(comment: String): Task[String] = {
    // User writes: // TODO: implement round-robin arbiter
    // Agent generates the implementation
    agents.design.generateFromSpec(
      extractSpecFromComment(comment)
    ).map(_.code)
  }
  
  def explainError(error: Diagnostic): Task[String] = {
    for {
      // Retrieve similar errors from KB
      similar <- kb.retrieveErrorFix(error.toStructured)
      
      // Generate explanation
      explanation <- agents.design.llm.complete(
        prompt = s"""Explain this Chisel error in simple terms and suggest a fix:
          
          Error: ${error.message}
          Location: ${error.location}
          Code: ${error.context}
          
          Similar issues and fixes: ${similar.mkString("\n")}
        """
      )
    } yield explanation
  }
}
```

#### CI/CD Integration

```scala
// GitHub Actions / GitLab CI integration
object CIIntegration {
  
  def generateWorkflow(config: CIConfig): String = {
    s"""
    name: Hardware CI
    on: [push, pull_request]
    
    jobs:
      elaborate:
        runs-on: ubuntu-latest
        steps:
          - uses: actions/checkout@v3
          - uses: llm4s/hardware-ci@v1
            with:
              action: elaborate
              top-module: ${config.topModule}
              
      test:
        needs: elaborate
        runs-on: ubuntu-latest  
        steps:
          - uses: llm4s/hardware-ci@v1
            with:
              action: test
              coverage-threshold: ${config.coverageThreshold}
              
      synthesize:
        needs: test
        runs-on: ubuntu-latest
        steps:
          - uses: llm4s/hardware-ci@v1
            with:
              action: synthesize
              target: ${config.synthesisTarget}
              timing-constraint: ${config.timingConstraintMhz}MHz
    """
  }
  
  // PR review automation
  def reviewPR(pr: PullRequest): Task[ReviewResult] = {
    for {
      // Analyze changed Chisel files
      changes <- analyzeChanges(pr.diff)
      
      // Check for common issues
      issues <- detectIssues(changes)
      
      // Suggest additional tests
      testSuggestions <- suggestTests(changes)
      
      // Estimate synthesis impact
      synthesisImpact <- estimateImpact(changes)
      
    } yield ReviewResult(issues, testSuggestions, synthesisImpact)
  }
}
```

#### Deliverables

| Deliverable | Week |
|-------------|------|
| NL2Chisel pipeline | 32 |
| Verification pipeline | 33 |
| DSE pipeline | 34 |
| IDE integration (basic LSP) | 35 |
| CI/CD integration | 36 |

#### Milestone: M5 — "Production Ready"

Complete, documented, integrated system:

```bash
# Command line usage
$ llm4s-hw generate "16-bit RISC-V ALU with add, sub, and, or, xor, slt operations"
✓ Specification extracted
✓ Chisel generated (124 lines)
✓ Elaboration successful
✓ 12 tests generated
✓ All tests passing
✓ Synthesis: 89 LUTs, 250MHz on xc7a100t

Output written to: alu/
  ├── ALU.scala
  ├── ALUSpec.json
  ├── ALUTest.scala
  └── synthesis_report.txt
```

---

## GSoC 2026 Project Mapping

The phases map well to potential GSoC projects:

### Project Idea 1: Chisel Elaboration & Error Intelligence
**Phase:** 0-1  
**Scope:** Build the elaboration tool with intelligent error parsing and suggestions

**Deliverables:**
- Elaboration tool with structured error output
- Error classifier (10+ error types)
- LLM-powered fix suggestions
- Integration test suite

### Project Idea 2: Hardware Verification Agent
**Phase:** 3 (Verification Agent)  
**Scope:** Build an agent that generates comprehensive test suites

**Deliverables:**
- Test scenario generation from specs
- Coverage gap analysis and closure
- ChiselTest code generation
- Formal property extraction

### Project Idea 3: Chisel Knowledge Base & RAG
**Phase:** 4  
**Scope:** Build a comprehensive Chisel knowledge base with RAG integration

**Deliverables:**
- Multi-source document indexing
- Context-aware retrieval
- Design pattern library
- Agent integration

### Project Idea 4: NL2Chisel Pipeline
**Phase:** 5  
**Scope:** Natural language to validated Chisel implementation

**Deliverables:**
- Specification extraction
- Iterative code generation
- Validation loop
- Example library

---

## Success Metrics

### Quantitative Metrics

| Metric | Target | Measurement |
|--------|--------|-------------|
| Elaboration success rate | >90% | Generated code that elaborates first try |
| Test coverage | >80% | Line coverage on generated tests |
| Synthesis success | >95% | Designs that synthesize without errors |
| Error fix accuracy | >70% | Suggested fixes that resolve the error |
| RAG relevance | >85% | Top-3 retrieval contains answer |

### Qualitative Metrics

- Hardware engineers find the tools useful in real workflows
- Reduction in time spent on routine verification tasks
- Successful adoption by at least 2 Chisel projects
- Positive feedback from Chisel community

---

## Community Engagement Plan

### Phase 1: Awareness (Months 1-3)
- Announce project on Chisel Discourse and Gitter
- Present at Chisel Community Conference (if timing aligns)
- Publish introductory blog post
- Create demo videos

### Phase 2: Early Adopters (Months 4-6)
- Identify 3-5 early adopter projects
- Weekly office hours for feedback
- Rapid iteration based on real usage
- Build contributor community

### Phase 3: Broader Adoption (Months 7+)
- Workshop at RISC-V Summit
- Integration with popular Chisel templates
- University partnerships for teaching
- Case studies and success stories

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| LLM generates incorrect hardware | High | High | Mandatory elaboration + simulation validation |
| Chisel API changes | Medium | Medium | Version-specific adapters, CI against multiple versions |
| Limited community adoption | Medium | High | Early engagement, focus on real pain points |
| Performance issues (long synthesis) | Medium | Medium | Caching, incremental synthesis, async pipelines |
| External tool availability | Low | High | Graceful degradation, multiple backend support |

---

## Timeline Summary

| Phase | Duration | Key Milestone |
|-------|----------|---------------|
| Phase 0: Foundation | Weeks 1-4 | M0: Hello Hardware |
| Phase 1: Core Tools | Weeks 5-10 | M1: Tool Suite Complete |
| Phase 2: Schemas | Weeks 11-14 | M2: Structured Hardware Specs |
| Phase 3: Agents | Weeks 15-24 | M3: Agentic Hardware Design |
| Phase 4: RAG | Weeks 25-30 | M4: Knowledge-Enhanced Agents |
| Phase 5: Integration | Weeks 31-36 | M5: Production Ready |

**Total Duration:** ~9 months for full implementation

---

## Appendix A: Technology Stack

| Component | Technology | Rationale |
|-----------|------------|-----------|
| Language | Scala 3 | Native Chisel integration |
| Build | SBT | Chisel ecosystem standard |
| LLM Client | LLM4S core | Foundation for agents |
| Vector Store | Qdrant / Milvus | Scalable similarity search |
| Synthesis | Yosys + Vivado | Open source + commercial |
| Simulation | Verilator | Fast, open source |
| Formal | SymbiYosys | Open source formal suite |
| CI | GitHub Actions | Chisel community standard |

## Appendix B: Example Workflows

### Workflow 1: New Module Development
```
User: "I need a parameterized barrel shifter"
  ↓
Design Agent: Extracts spec, generates Chisel
  ↓
Elaboration Tool: Validates compilation
  ↓
Verification Agent: Generates test suite
  ↓
Simulation Tool: Runs tests
  ↓
User: Reviews, requests modifications
  ↓
Design Agent: Iterates based on feedback
```

### Workflow 2: Coverage Closure
```
User: Uploads coverage report
  ↓
Verification Agent: Analyzes gaps
  ↓
Verification Agent: Generates targeted tests
  ↓
Simulation Tool: Runs new tests
  ↓
Coverage Tool: Reports improvement
  ↓
Loop until coverage target met
```

### Workflow 3: Design Space Exploration
```
User: "Optimize my FFT for minimum area at 100MHz"
  ↓
DSE Agent: Identifies parameter space
  ↓
Synthesis Tool: Evaluates design points
  ↓
DSE Agent: Analyzes results, suggests next points
  ↓
Loop until Pareto frontier identified
  ↓
DSE Agent: Recommends optimal configuration
```

---

## Document History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | December 2024 | Initial proposal |

---

*This document is a living proposal. Feedback welcome via GitHub issues or Chisel Discourse.*
