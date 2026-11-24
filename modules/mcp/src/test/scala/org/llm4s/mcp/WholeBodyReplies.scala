package org.llm4s.mcp

import org.llm4s.http.*
import org.llm4s.types.Result

/**
 * Lets a spec that stubs `post` with a whole reply drive [[StreamableHTTPTransportImpl]], which reads its reply
 * through `postStream` so that the reply's body is bounded by the timeout too (#1785).
 *
 * Every POST of text is answered by the stub's `onPost` with the same arguments, its body handed back as a
 * stream, so the stubs, their argument matchers and any verification of `onPost` keep their meaning. Every other
 * request goes to the stub unchanged.
 */
private[mcp] object WholeBodyReplies {

  def apply(stub: StubbedHttpCalls): Llm4sHttpClient = new Llm4sHttpClient {
    override def send(request: HttpRequest): Result[StreamingHttpResponse] =
      (request.method, request.body) match {
        case (HttpMethod.Post, HttpBody.Text(body)) =>
          stub
            .onPost(request.url, request.headers, body, request.responseTimeout)
            .map(response => response.withBody(Option(response.body).getOrElse("")).toStreaming)
        case _ => stub.send(request)
      }
  }
}
