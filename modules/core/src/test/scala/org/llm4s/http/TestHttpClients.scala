package org.llm4s.http

class MockHttpClient(
  var nextResponseBody: String = "",
  var nextStatus: Int = 200
) extends Llm4sHttpClient {

  private def _lastUrl: Option[String]                  = None
  private def _lastHeaders: Option[Map[String, String]] = None
  private def _lastParams: Option[Map[String, String]]  = None
  private def _lastBody: Option[String]                 = None
  private def _lastTimeout: Option[Int]                 = None

  var lastUrl: Option[String]                  = _lastUrl
  var lastHeaders: Option[Map[String, String]] = _lastHeaders
  var lastParams: Option[Map[String, String]]  = _lastParams
  var lastBody: Option[String]                 = _lastBody
  var lastTimeout: Option[Int]                 = _lastTimeout

  var postCallCount: Int = 0

  private def response: HttpResponse =
    HttpResponse(
      statusCode = nextStatus,
      body = nextResponseBody
    )

  override def get(
    url: String,
    headers: Map[String, String],
    params: Map[String, String],
    timeout: Int
  ): HttpResponse = {
    lastUrl = Some(url)
    lastHeaders = Some(headers)
    lastParams = Some(params)
    lastTimeout = Some(timeout)
    response
  }

  override def post(url: String, headers: Map[String, String], body: String, timeout: Int): HttpResponse = {
    lastUrl = Some(url)
    lastHeaders = Some(headers)
    lastBody = Some(body)
    lastTimeout = Some(timeout)
    postCallCount += 1
    response
  }

  override def postBytes(url: String, headers: Map[String, String], data: Array[Byte], timeout: Int): HttpResponse = {
    lastUrl = Some(url)
    lastHeaders = Some(headers)
    lastTimeout = Some(timeout)
    response
  }

  override def postMultipart(
    url: String,
    headers: Map[String, String],
    parts: Seq[MultipartPart],
    timeout: Int
  ): HttpResponse = {
    lastUrl = Some(url)
    lastHeaders = Some(headers)
    lastTimeout = Some(timeout)
    response
  }

  override def put(url: String, headers: Map[String, String], body: String, timeout: Int): HttpResponse = {
    lastUrl = Some(url)
    lastHeaders = Some(headers)
    lastBody = Some(body)
    lastTimeout = Some(timeout)
    response
  }

  override def delete(url: String, headers: Map[String, String], timeout: Int): HttpResponse = {
    lastUrl = Some(url)
    lastHeaders = Some(headers)
    lastTimeout = Some(timeout)
    response
  }
}

class FailingHttpClient(exception: Throwable) extends Llm4sHttpClient {
  private def fail: Nothing = throw exception

  override def get(
    url: String,
    headers: Map[String, String],
    params: Map[String, String],
    timeout: Int
  ): HttpResponse = fail

  override def post(url: String, headers: Map[String, String], body: String, timeout: Int): HttpResponse =
    fail

  override def postBytes(url: String, headers: Map[String, String], data: Array[Byte], timeout: Int): HttpResponse =
    fail

  override def postMultipart(
    url: String,
    headers: Map[String, String],
    parts: Seq[MultipartPart],
    timeout: Int
  ): HttpResponse = fail

  override def put(url: String, headers: Map[String, String], body: String, timeout: Int): HttpResponse =
    fail

  override def delete(url: String, headers: Map[String, String], timeout: Int): HttpResponse =
    fail
}
