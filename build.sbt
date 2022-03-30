name                  := "cliche"
organization          := "fiatjaf"
scalaVersion          := "2.13.8"
version               := "0.1.0"
scalacOptions         += "-language:postfixOps"
libraryDependencies   ++= Seq(
  "fr.acinq.secp256k1" % "secp256k1-kmp-jni-jvm" % "0.6.3",
  "org.scodec" % "scodec-core_2.13" % "1.11.9",
  "commons-codec" % "commons-codec" % "1.10",
  "io.reactivex" % "rxscala_2.13" % "0.27.0",
  "org.json4s" % "json4s-native_2.13" % "3.6.7", // electrum,
  "io.spray" % "spray-json_2.13" % "1.3.5", // immortan,
  "com.typesafe.akka" % "akka-actor_2.13" % "2.6.9",
  "io.netty" % "netty-all" % "4.1.42.Final",
  "com.softwaremill.quicklens" % "quicklens_2.13" % "1.8.4",
  "org.bouncycastle" % "bcprov-jdk15to18" % "1.68",
  "com.google.guava" % "guava" % "31.1-jre",
  "org.scala-lang.modules" % "scala-parser-combinators_2.13" % "2.1.0",
  "com.sparrowwallet" % "hummingbird" % "1.6.2",
  "com.github.alexarchambault" % "case-app_2.13" % "2.1.0-M13", // cliche
  "com.lihaoyi" % "requests_2.13" % "0.7.0", // cliche

  // config
  "com.iheart" % "ficus_2.13" % "1.5.0",

  // testing
  "org.scalatest" % "scalatest_2.13" % "3.1.1",
  "com.typesafe.akka" % "akka-testkit_2.13" % "2.6.9",
  "org.xerial" % "sqlite-jdbc" % "3.27.2.1"
)
