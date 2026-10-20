package org.llm4s.agent.harness

import org.llm4s.agent.delegation.{ DelegationConfig, Inheritance, TaskRequest, ToolGrant, TypedAgent }
import org.llm4s.annotation.Experimental
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.CompletionOptions

/**
 * The subagents a [[HarnessProfile]] gives its agent (design 4.48), offered through the `task` tools, and the bounds on
 * their tasks. [[SubagentConfig.none]], the minimal profile's, gives none: delegation is never granted silently.
 * [[HarnessProfile.deepAgents]] has [[SubagentConfig.deepAgents]], the general-purpose subagent alone.
 *
 * `AgentBuilder.build()` binds these before any the builder adds with `withSubagents`, and `delegation` bounds them all;
 * `withDelegation` beside a harness is refused, so the bounds have one source.
 *
 * @param generalPurpose the general-purpose subagent, `general-purpose`; `None` disables it
 * @param specialists the profile's own subagents, after the general-purpose one, in order
 * @param delegation the bounds on the agent's delegated tasks
 */
@Experimental
final case class SubagentConfig private (
  generalPurpose: Option[GeneralPurposeConfig],
  specialists: Vector[TypedAgent[?, ?]],
  delegation: DelegationConfig
):
  def withGeneralPurpose(c: GeneralPurposeConfig): SubagentConfig         = copy(generalPurpose = Some(c))
  def withGeneralPurpose(c: Option[GeneralPurposeConfig]): SubagentConfig = copy(generalPurpose = c)

  /** The specialists, replacing any set before. */
  def withSpecialists(agents: TypedAgent[?, ?]*): SubagentConfig = copy(specialists = agents.toVector)

  def withDelegation(c: DelegationConfig): SubagentConfig = copy(delegation = c)

  /** Whether any subagent is offered: the general-purpose one or a specialist. */
  def offersAny: Boolean = generalPurpose.isDefined || specialists.nonEmpty

  /** The subagents for an agent whose tools are named `parentTools`: the general-purpose one first, then the specialists. */
  private[llm4s] def agents(parentTools: Set[String]): Vector[TypedAgent[?, ?]] =
    generalPurpose.map(_.agent(parentTools)).toVector ++ specialists

@Experimental
object SubagentConfig:
  def apply(
    generalPurpose: Option[GeneralPurposeConfig] = None,
    specialists: Vector[TypedAgent[?, ?]] = Vector.empty,
    delegation: DelegationConfig = DelegationConfig.default
  ): SubagentConfig = new SubagentConfig(generalPurpose, specialists, delegation)

  /** No subagents: the minimal profile's. */
  val none: SubagentConfig = SubagentConfig()

  /** The general-purpose subagent at its defaults, under the default bounds: the `deepAgents` preset's. */
  def deepAgents: SubagentConfig = SubagentConfig(generalPurpose = Some(GeneralPurposeConfig()))

/**
 * The general-purpose subagent of a [[SubagentConfig]] (design 4.48): a `TypedAgent.text("general-purpose", ...)` that
 * takes a task description and reports its answer as text, through the shared `task` tool. Its own harness is the
 * minimal profile (the file tools over its own `StateFileBackend`) with `systemPrompt`.
 *
 * Its grant is written here, not implied: [[GeneralPurposeConfig.DefaultInheritance]] grants every tool of the parent
 * except the file tools and `execute` (its own harness has file tools over its own backend) and no delegation. For this
 * subagent a `ToolGrant.AllExcept` withholds the names it lists that the parent has, and ignores the rest - withholding
 * an absent tool is harmless - so the default holds for a parent with or without a sandbox. Any other grant is checked
 * as for every subagent. `Agent.delegation` shows the grant as computed.
 *
 * @param description what the parent model reads about it, in the `task` tool and the prompt; must not be blank
 * @param systemPrompt its harness's prompt
 * @param model the client and options it calls; `None` for the parent's
 * @param inheritance what it takes from the parent
 */
@Experimental
final case class GeneralPurposeConfig private (
  description: String,
  systemPrompt: PromptMode,
  model: Option[(LLMClient, CompletionOptions)],
  inheritance: Inheritance
):
  def withDescription(d: String): GeneralPurposeConfig      = copy(description = d)
  def withSystemPrompt(m: PromptMode): GeneralPurposeConfig = copy(systemPrompt = m)
  def withModel(client: LLMClient, options: CompletionOptions): GeneralPurposeConfig =
    copy(model = Some(client -> options))
  def withModel(m: Option[(LLMClient, CompletionOptions)]): GeneralPurposeConfig = copy(model = m)
  def withInheritance(i: Inheritance): GeneralPurposeConfig                      = copy(inheritance = i)

  /** The subagent for a parent whose tools are named `parentTools`; see the class's Scaladoc for its grant. */
  private[harness] def agent(parentTools: Set[String]): TypedAgent[TaskRequest, String] =
    val grant = inheritance.tools match
      case ToolGrant.AllExcept(names) => ToolGrant.AllExcept(names.intersect(parentTools))
      case other                      => other
    val base = TypedAgent
      .text(GeneralPurposeConfig.Id, description)
      .withHarness(HarnessProfile().withSystemPrompt(systemPrompt))
      .withInheritance(inheritance.withTools(grant))
    model.fold(base)((client, options) => base.withModel(client, options))

@Experimental
object GeneralPurposeConfig:

  /** The general-purpose subagent's id: its `subagent_type` in a `task` call. */
  val Id: String = "general-purpose"

  /** Its default description. */
  val DefaultDescription: String =
    "A general-purpose agent for a self-contained task of several steps: researching a question, working through " +
      "files, or any bounded piece of work that can run apart from this conversation. It has its own scratch files " +
      "and your tools, except the file tools and execute, and reports back once."

  /** What its default prompt adds to its harness's. */
  val DefaultPromptSuffix: String =
    "You are a subagent: another agent gave you this one task, and sees nothing of your work but your final answer. " +
      "Make that answer a complete, self-contained report: what you found or did, how you checked it, and what you " +
      "could not do. Your files are your own scratch space; the caller cannot read them."

  /** The default grant: every parent tool except the file tools and `execute`, and no delegation. */
  val DefaultInheritance: Inheritance =
    Inheritance(tools = ToolGrant.AllExcept(FileTools.Names.toSet + ExecuteTool.Name))

  def apply(
    description: String = DefaultDescription,
    systemPrompt: PromptMode = PromptMode.Suffix(DefaultPromptSuffix),
    model: Option[(LLMClient, CompletionOptions)] = None,
    inheritance: Inheritance = DefaultInheritance
  ): GeneralPurposeConfig = new GeneralPurposeConfig(description, systemPrompt, model, inheritance)
