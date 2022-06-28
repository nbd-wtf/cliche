import cats.effect._
import cats.effect.std.Queue
import cats.syntax.all._
import fs2.{Stream, Pipe}
import fs2.concurrent.Topic
import org.http4s._
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits._
import org.http4s.server.websocket._
import org.http4s.websocket.WebSocketFrame
import org.http4s.websocket.WebSocketFrame._

import scala.concurrent.duration._

class ServerApp()(
    implicit F: Async[IO],
    implicit val topic: Topic[IO, JSONRPCMessage]
) extends Http4sDsl[IO] {
  def routes(wsb: WebSocketBuilder[IO]): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case POST -> Root => Ok("~")
      case GET -> Root => {
        def process(input: WebSocketFrame): IO[Unit] = input match {
          case Text(msg, _) => Handler.handle(msg)
          case other => IO.println(s"# unexpected websocket message: $other")
        }

        val out: Stream[IO, WebSocketFrame] =
          topic.subscribe(25).map(_.render(forceCompact = true)).map(Text(_))

        val in: Pipe[IO, WebSocketFrame, Unit] =
          _.foreach(process)

        wsb.build(out, in)
      }
    }

  def stream: Stream[IO, ExitCode] =
    BlazeServerBuilder[IO]
      .bindHttp(12000, "127.0.0.1")
      .withHttpWebSocketApp(routes(_).orNotFound)
      .serve
}
