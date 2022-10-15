import java.io.File
import com.typesafe.config.{ConfigFactory, Config => TypesafeConfig}
import net.ceedubs.ficus.Ficus._
import scoin.MnemonicCode
import scoin.Crypto.randomBytes

object Config {
  val datadir = System.getProperty(
    "cliche.datadir",
    System.getProperty("user.home") + "/.config/cliche"
  )

  (new File(datadir)).mkdirs()

  val c: TypesafeConfig = ConfigFactory
    .systemProperties()
    .withFallback(ConfigFactory.parseFile(new File(datadir, "cliche.conf")))
    .withFallback(ConfigFactory.load())

  val nativeImageAgent = c.as[Boolean]("nativeImageAgent")

  val network = c.as[String]("cliche.network")
  val electrum = Option(
    System.getProperty(
      "cliche.electrum",
      null
    )
  )
  val seed =
    try {
      c.as[String]("cliche.seed").split(" ").toList
    } catch {
      case _: com.typesafe.config.ConfigException$Missing => {
        println(
          "# you must specify your mnemonic seed as `cliche.seed = \"...\"` your `cliche.conf` file."
        )

        val twelveWords = MnemonicCode
          .toMnemonics(randomBytes(16))
          .mkString(" ")
        println(
          s"# if you don't have a mnemonic, here's one your computer has just generated: '${twelveWords}'."
        )
        println(
          s"# to run recklessly without writing your seed anywhere, start again with `-Dcliche.seed=\"${twelveWords}\"`."
        )
        scala.sys.exit(1)
      }
    }
  try {
    MnemonicCode.validate(seed)
  } catch {
    case _: Throwable => {
      println(
        s"# mnemonic seed '${seed.take(1).mkString(" ")}...' is not valid."
      )
      scala.sys.exit(1)
    }
  }

  val compactJSON = c.as[Boolean]("cliche.json.compact")

  val websocketHost = c.as[String]("cliche.websocket.host")
  val websocketPort = c.as[Int]("cliche.websocket.port")

  def print(): Unit = {
    println(
      s"# loaded configs: network=$network electrum=$electrum json.compact=$compactJSON websocket.host=${websocketHost} websocket.port=${websocketPort}"
    )
  }
}
