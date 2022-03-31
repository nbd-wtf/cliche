package cliche

import com.typesafe.config.{ConfigFactory, Config => TypesafeConfig}
import net.ceedubs.ficus.Ficus._
import java.io.File

object Config {
  val datadir = System.getProperty(
    "cliche.datadir",
    System.getProperty("user.home") + "/.config/cliche"
  )

  val c: TypesafeConfig = ConfigFactory
    .systemProperties()
    .withFallback(ConfigFactory.parseFile(new File(datadir, "cliche.conf")))
    .withFallback(ConfigFactory.load())

  val network = c.as[String]("cliche.network")
  val seed = c.as[String]("cliche.seed").split(" ").toList
  val compactJSON = c.as[Boolean]("cliche.json.compact")

  println(s"# configs: network=$network json.compact=$compactJSON")
}
