import cats.effect._
import fs2.Pipe
import fs2.concurrent.Topic

class StdoutApp()(implicit
    F: Async[IO],
    val topic: Topic[IO, JSONRPCMessage]
) {
  def run(): IO[Unit] = {
    topic
      .subscribe(25)
      .map(_.render())
      .foreach(IO.println(_))
      .compile
      .drain
  }
}
