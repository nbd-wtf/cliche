package cliche

import com.typesafe.config.{ConfigFactory, Config => TypesafeConfig}
import net.ceedubs.ficus.Ficus._
import java.io.File

object Config {
  def load(): Config = {
    val datadir = new File(
      System.getProperty(
        "cliche.datadir",
        System.getProperty("user.home") + "/.config/cliche"
      )
    )

    val c: TypesafeConfig = ConfigFactory
      .systemProperties()
      .withFallback(ConfigFactory.parseFile(new File(datadir, "cliche.conf")))
      .withFallback(ConfigFactory.load())

    Config(
      datadir = c.as[String]("cliche.datadir"),
      network = c.as[String]("cliche.network"),
      seed = c.as[String]("cliche.seed").split(" ").toList
    )
  }
}

case class Config(datadir: String, network: String, seed: List[String])
