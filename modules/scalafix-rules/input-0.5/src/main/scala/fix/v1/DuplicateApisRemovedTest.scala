/*
rule = DuplicateApisRemoved
*/
package fix.v1

import org.llm4s.config.Llm4sConfig
import org.llm4s.error.ServiceError
import org.llm4s.error.ServiceError.ServiceErrorOps
import org.llm4s.llmconnect.spi.ProviderRegistry

object DuplicateApisRemovedTest {
  def retryable(error: ServiceError): Boolean = error.isRecoverableStatus

  def model(using ProviderRegistry) = Llm4sConfig.loadTextEmbeddingModel()
}
