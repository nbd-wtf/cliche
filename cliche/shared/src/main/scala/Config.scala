import java.io.File
import org.ekrich.config.ConfigFactory
import scoin.{Crypto, MnemonicCode}

object Config {
  val datadir = System.getProperty(
    "cliche.datadir",
    System.getProperty("user.home") + "/.config/cliche"
  )

  (new File(datadir)).mkdirs()

  val c = ConfigFactory
    .systemProperties()
    .withFallback(ConfigFactory.parseFile(new File(datadir, "cliche.conf")))
    .withFallback(ConfigFactory.load())

  val nativeImageAgent = c.getBoolean("nativeImageAgent")

  val network = c.getString("cliche.network")
  val electrum = Option(
    System.getProperty(
      "cliche.electrum",
      null
    )
  )
  val seed =
    try {
      c.getString("cliche.seed").split(" ").toList
    } catch {
      case _: org.ekrich.config.ConfigException => {
        println(
          "# you must specify your mnemonic seed as `cliche.seed = \"...\"` your `cliche.conf` file."
        )

        val twelveWords = MnemonicCode
          .toMnemonics(Crypto.randomBytes(16))
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

  val compactJSON = c.getBoolean("cliche.json.compact")

  val websocketHost = c.getString("cliche.websocket.host")
  val websocketPort = c.getInt("cliche.websocket.port")

  def print(): Unit = {
    println(
      s"# loaded configs: network=$network electrum=$electrum json.compact=$compactJSON websocket.host=${websocketHost} websocket.port=${websocketPort}"
    )
  }
}
