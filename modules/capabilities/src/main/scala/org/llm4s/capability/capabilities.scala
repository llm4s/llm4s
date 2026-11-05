package org.llm4s.capability

import org.llm4s.annotation.Experimental

// The capability markers. Each is a type with no values: it exists only to be written in a capability set, such as
// `Capable[Vision & Tools]`. Each maps to one key of `ModelMetadata.supports` (see `CapabilityCheck`).

/** The model reads images (`ModelMetadata.supports("vision")`). */
@Experimental
sealed trait Vision

/** The model calls tools (`ModelMetadata.supports("function_calling")`). */
@Experimental
sealed trait Tools

/** The model calls several tools in one reply (`ModelMetadata.supports("parallel_function_calling")`). */
@Experimental
sealed trait ParallelTools

/** The model answers in a given JSON schema (`ModelMetadata.supports("response_schema")`). */
@Experimental
sealed trait StructuredOutput

/** The model reasons before it answers (`ModelMetadata.supports("reasoning")`). */
@Experimental
sealed trait Reasoning

/** The provider caches the prompt for this model (`ModelMetadata.supports("prompt_caching")`). */
@Experimental
sealed trait PromptCaching

/** The model takes system messages (`ModelMetadata.supports("system_messages")`). */
@Experimental
sealed trait SystemMessages
