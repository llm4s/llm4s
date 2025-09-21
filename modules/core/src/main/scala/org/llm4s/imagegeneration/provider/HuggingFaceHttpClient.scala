package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration.HuggingFaceConfig
import requests.Response

trait BaseHttpClient {
  def post(payload: String): requests.Response
}

class HttpClient(url: String, headers: Map[String, String], timeout: Int) extends BaseHttpClient {
  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  override def post(payload: String): Response = {
    logger.debug("Making request to: {}", url)
    logger.debug("Payload: {}", payload)

    requests.post( // Note that the post could throw - as per the documentation
      url = url,
      data = payload,
      headers = headers,
      readTimeout = timeout,
      connectTimeout = 10000
    )
  }
}

object HttpClient {
  def createHttpClient(config: HuggingFaceConfig) =
    new HttpClient(
      url = s"https://api-inference.huggingface.co/models/${config.model}",
      headers = Map(
        "Authorization" -> s"Bearer ${config.apiKey}",
        "Content-Type"  -> "application/json"
      ),
      config.timeout
    )
}
