import cats.effect._
import fs2.concurrent.Topic

class StdinApp()(
    implicit F: Async[IO],
    implicit val topic: Topic[IO, JSONRPCMessage]
) {
  def run(): IO[Unit] = {
    val loop = IO.readLine.map(_.trim()).flatMap(Handler.handle(_))
    loop.foreverM
  }
}
