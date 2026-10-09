package org.llm4s.config

/**
 * The environment variables `llm4s-bedrock` suggests, and the names of the provider-specific
 * keys of a `provider = "bedrock"` section.
 *
 * Named sections read no environment variable by themselves, so the variables here are the
 * conventional AWS ones a user binds in the section (`region = ${?AWS_REGION}`); they appear in
 * the missing-key error message as that binding. A key belongs with the code that reads it, so
 * they are not in `ConfigKeys`.
 */
object BedrockConfigKeys {

  /** Section key naming the AWS region that hosts the model. Required. */
  val REGION_KEY = "region"

  /** Section key naming an AWS shared-config profile to authenticate with. */
  val PROFILE_KEY = "profile"

  /** Section key for an explicit access key id; with [[SECRET_ACCESS_KEY_KEY]]. */
  val ACCESS_KEY_ID_KEY = "accessKeyId"

  /** Section key for an explicit secret access key; with [[ACCESS_KEY_ID_KEY]]. */
  val SECRET_ACCESS_KEY_KEY = "secretAccessKey"

  /** Section key for the session token that goes with temporary credentials. */
  val SESSION_TOKEN_KEY = "sessionToken"

  /** The AWS region variable. */
  val AWS_REGION = "AWS_REGION"

  /** The AWS profile variable. */
  val AWS_PROFILE = "AWS_PROFILE"

  /** The AWS access key id variable. */
  val AWS_ACCESS_KEY_ID = "AWS_ACCESS_KEY_ID"

  /** The AWS secret access key variable. */
  val AWS_SECRET_ACCESS_KEY = "AWS_SECRET_ACCESS_KEY"

  /** The AWS session token variable. */
  val AWS_SESSION_TOKEN = "AWS_SESSION_TOKEN"
}
