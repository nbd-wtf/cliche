import cats.effect._
import fs2.concurrent.Topic

class StdinApp(topic: Topic[IO, String])(implicit F: Async[IO]) {
  def run(): IO[Unit] = {
    val loop = for {
      input <- IO.readLine.map(_.trim())
      result = Handler.handle(input)
      _ <- topic.publish1(result)
    } yield IO.unit

    loop.foreverM
  }
}
