import cats.effect._
import fs2.concurrent.Topic

class StdinApp()(implicit
    F: Async[IO],
    val topic: Topic[IO, JSONRPCMessage]
) {
  def run(): IO[Unit] =
    IO.readLine
      .map(_.trim())
      .flatMap(Handler.handle(_).start >> IO.unit)
      .foreverM
}
