package org.llm4s.examples.http.playframework

import play.api.routing.Router.Routes
import play.api.routing.SimpleRouter
import play.api.routing.sird.*

import javax.inject.Inject

/**
 * The Play app's routes, `play.http.router` in `application.conf`. A string-interpolating router (SIRD) rather than a
 * `conf/routes` file, so the app needs no Play sbt plugin to compile its routes.
 */
final class ChatRouter @Inject() (controller: ChatController) extends SimpleRouter {

  override def routes: Routes = {
    case POST(p"/chat")        => controller.chat
    case POST(p"/chat/stream") => controller.chatStream
  }
}
