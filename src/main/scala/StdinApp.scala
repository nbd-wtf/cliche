import cats.effect._

class StdinApp[F[_]](implicit F: Async[F]) {
  def run(): IO[Unit] = {
    val loop =
      IO.readLine.map(_.trim()).flatMap { command =>
        IO.println(Handler.handle(command))
      }

    loop.foreverM
  }
}
