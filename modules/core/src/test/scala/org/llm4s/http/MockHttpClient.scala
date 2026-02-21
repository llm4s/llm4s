package org.llm4s.http

final class MockHttpClient(response: HttpResponse) extends Llm4sHttpClient {
  var lastUrl: Option[String]                  = None
  var lastHeaders: Option[Map[String, String]] = None
  var lastParams: Option[Map[String, String]]  = None
  var lastBody: Option[String]                 = None
  var lastTimeout: Option[Int]                 = None
  var postCallCount: Int                       = 0

  private def record(
    url: String,
    headers: Map[String, String],
    params: Option[Map[String, String]],
    body: Option[String],
    timeout: Int,
    countPost: Boolean
  ): HttpResponse = {
    lastUrl = Some(url)
    lastHeaders = Some(headers)
    lastParams = params
    lastBody = body
    lastTimeout = Some(timeout)
    if (countPost) {
      postCallCount += 1
    }
    response
  }

  override def get(
    url: String,
    headers: Map[String, String],
    params: Map[String, String],
    timeout: Int
  ): HttpResponse =
    record(url, headers, Some(params), None, timeout, countPost = false)

  override def post(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: Int
  ): HttpResponse =
    record(url, headers, None, Some(body), timeout, countPost = true)

  override def postBytes(
    url: String,
    headers: Map[String, String],
    data: Array[Byte],
    timeout: Int
  ): HttpResponse =
    record(url, headers, None, None, timeout, countPost = true)

  override def postMultipart(
    url: String,
    headers: Map[String, String],
    parts: Seq[MultipartPart],
    timeout: Int
  ): HttpResponse =
    record(url, headers, None, None, timeout, countPost = true)

  override def put(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: Int
  ): HttpResponse =
    record(url, headers, None, Some(body), timeout, countPost = false)

  override def delete(
    url: String,
    headers: Map[String, String],
    timeout: Int
  ): HttpResponse =
    record(url, headers, None, None, timeout, countPost = false)

  override def postRaw(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: Int
  ): HttpRawResponse = {
    record(url, headers, None, Some(body), timeout, countPost = true)
    HttpRawResponse(response.statusCode, response.body.getBytes())
  }

  override def postStream(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: Int
  ): StreamingHttpResponse = {
    record(url, headers, None, Some(body), timeout, countPost = true)
    StreamingHttpResponse(response.statusCode, new java.io.ByteArrayInputStream(response.body.getBytes()))
  }
}

final class FailingHttpClient(exception: Throwable) extends Llm4sHttpClient {
  private def fail: Nothing = throw exception

  override def get(
    url: String,
    headers: Map[String, String],
    params: Map[String, String],
    timeout: Int
  ): HttpResponse = fail

  override def post(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: Int
  ): HttpResponse = fail

  override def postBytes(
    url: String,
    headers: Map[String, String],
    data: Array[Byte],
    timeout: Int
  ): HttpResponse = fail

  override def postMultipart(
    url: String,
    headers: Map[String, String],
    parts: Seq[MultipartPart],
    timeout: Int
  ): HttpResponse = fail

  override def put(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: Int
  ): HttpResponse = fail

  override def delete(
    url: String,
    headers: Map[String, String],
    timeout: Int
  ): HttpResponse = fail

  override def postRaw(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: Int
  ): HttpRawResponse = fail

  override def postStream(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: Int
  ): StreamingHttpResponse = fail
}
