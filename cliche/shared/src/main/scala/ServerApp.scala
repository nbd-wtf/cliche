import cats.effect._
import cats.effect.std.Queue
import cats.syntax.all._
import fs2.{Stream, Pipe}
import fs2.concurrent.Topic
import com.comcast.ip4s.{Ipv4Address, Port}
import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.server.websocket._
import org.http4s.websocket.WebSocketFrame
import org.http4s.websocket.WebSocketFrame._

import scala.concurrent.duration._

class ServerApp()(implicit
    F: Async[IO],
    val topic: Topic[IO, JSONRPCMessage]
) extends Http4sDsl[IO] {
  def routes(wsb: WebSocketBuilder[IO]): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case POST -> Root => Ok("~")
      case GET -> Root => {
        def process(input: WebSocketFrame): IO[Unit] = input match {
          case Text(msg, _) => Handler.handle(msg).start >> IO.unit
          case other => IO.println(s"# unexpected websocket message: $other")
        }

        val out: Stream[IO, WebSocketFrame] =
          topic
            .subscribe(25)
            .filterNot(_.isInstanceOf[RawCLIString])
            .map(_.render(forceCompact = true))
            .map(Text(_))

        val in: Pipe[IO, WebSocketFrame, Unit] =
          _.foreach(process)

        wsb.build(out, in)
      }
    }

  val server = EmberServerBuilder
    .default[IO]
    .withHost(Ipv4Address.fromString(Config.websocketHost).get)
    .withPort(Port.fromInt(Config.websocketPort).get)
    .withHttpWebSocketApp(routes(_).orNotFound)
    .build
}
