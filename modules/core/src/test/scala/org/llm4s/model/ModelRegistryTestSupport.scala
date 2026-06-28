package org.llm4s.model

import org.llm4s.types.Result

object ModelRegistryTestSupport {
  def defaultServiceResult(): Result[ModelRegistryService] =
    ModelRegistryService.fromConfig(ModelRegistryConfig.default)

  def defaultService(): ModelRegistryService =
    defaultServiceResult().toOption.get
}
