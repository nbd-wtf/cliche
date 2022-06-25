import cats.effect._
import fs2.Pipe
import fs2.concurrent.Topic

class StdoutApp(topic: Topic[IO, String])(implicit F: Async[IO]) {
  def run(): IO[Unit] = {
    topic
      .subscribe(25)
      .foreach(IO.println(_))
      .compile
      .drain
  }
}
