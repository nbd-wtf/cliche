import cats.effect._
import cats.effect.std.Queue
import cats.syntax.all._
import fs2._
import org.http4s._
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits._
import org.http4s.server.websocket._
import org.http4s.websocket.WebSocketFrame
import org.http4s.websocket.WebSocketFrame._

import scala.concurrent.duration._

class ServerApp[F[_]](implicit F: Async[F]) extends Http4sDsl[F] {
  def routes(wsb: WebSocketBuilder[F]): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case POST -> Root => Ok("~")
      case GET -> Root =>
        val handleCommand: Pipe[F, WebSocketFrame, WebSocketFrame] =
          _.collect {
            case Text(msg, _) => Text(Handler.handle(msg))
            case other        => Text(s"unexpected websocket message: $other")
          }

        Queue
          .unbounded[F, Option[WebSocketFrame]]
          .flatMap { q =>
            val out: Stream[F, WebSocketFrame] =
              Stream.fromQueueNoneTerminated(q).through(handleCommand)
            val in: Pipe[F, WebSocketFrame, Unit] = _.enqueueNoneTerminated(q)
            wsb.build(out, in)
          }
    }

  def stream: Stream[F, ExitCode] =
    BlazeServerBuilder[F]
      .bindHttp(12000, "127.0.0.1")
      .withHttpWebSocketApp(routes(_).orNotFound)
      .serve
}
