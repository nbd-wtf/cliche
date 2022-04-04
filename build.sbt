name                  := "cliche"
organization          := "fiatjaf"
scalaVersion          := "2.13.8"
version               := "0.1.0"
scalacOptions         += "-language:postfixOps"
libraryDependencies   ++= Seq(
  "com.google.guava" % "guava" % "31.1-jre", // eclair
  "org.scala-lang.modules" % "scala-parser-combinators_2.13" % "2.1.0", // immortan
  "fr.acinq.secp256k1" % "secp256k1-kmp-jni-jvm" % "0.6.3", // eclair
  "org.scodec" % "scodec-core_2.13" % "1.11.9", // immortan + eclair
  "commons-codec" % "commons-codec" % "1.10", // immortan + eclair
  "io.reactivex" % "rxscala_2.13" % "0.27.0", // immortan
  "org.json4s" % "json4s-native_2.13" % "3.6.7", // electrum,
  "io.spray" % "spray-json_2.13" % "1.3.5", // immortan,
  "com.typesafe.akka" % "akka-actor_2.13" % "2.6.9", // immortan + eclair
  "io.netty" % "netty-all" % "4.1.42.Final", // electrum
  "com.softwaremill.quicklens" % "quicklens_2.13" % "1.8.4", // immortan
  "org.bouncycastle" % "bcprov-jdk15to18" % "1.68", // eclair
  "com.sparrowwallet" % "hummingbird" % "1.6.2", // immortan
  "com.github.alexarchambault" % "case-app_2.13" % "2.1.0-M13", // cliche
  "com.lihaoyi" % "requests_2.13" % "0.7.0", // cliche
  "com.iheart" % "ficus_2.13" % "1.5.0", // cliche

  // testing
  "org.scalatest" % "scalatest_2.13" % "3.1.1",
  "com.typesafe.akka" % "akka-testkit_2.13" % "2.6.9",
  "org.xerial" % "sqlite-jdbc" % "3.27.2.1"
)
assemblyMergeStrategy := {
  case x if x.endsWith("module-info.class") => MergeStrategy.discard
  case x => (assembly / assemblyMergeStrategy).value(x)
}
