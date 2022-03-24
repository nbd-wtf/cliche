name             := "cliche"

ThisBuild / organization     := "fiatjaf"
ThisBuild / scalaVersion     := "2.11.12"
ThisBuild / version          := "0.1.0-SNAPSHOT"

assembly / mainClass := Some("cliche.Main")

ThisBuild / libraryDependencies ++= Seq(
  compilerPlugin("com.github.ghik" % "silencer-plugin" % "1.7.7" cross CrossVersion.full),
  "com.github.ghik" % "silencer-lib" % "1.7.7" % Provided cross CrossVersion.full
)

ThisBuild / scalacOptions += "-P:silencer:pathFilters=src"

lazy val root = (project in file("."))
  .settings(
    licenses += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt")),
    libraryDependencies ++= Seq(
      "org.scodec" % "scodec-core_2.11" % "1.11.3",
      "commons-codec" % "commons-codec" % "1.10",
      "io.reactivex" % "rxscala_2.11" % "0.27.0",
      "org.json4s" % "json4s-native_2.11" % "3.6.7", // Electrum,
      "io.spray" % "spray-json_2.11" % "1.3.5", // Immortan,
      "com.typesafe.akka" % "akka-actor_2.11" % "2.3.14",
      "io.netty" % "netty-all" % "4.1.42.Final",
      "com.softwaremill.quicklens" % "quicklens_2.11" % "1.6.1",
      "org.bouncycastle" % "bcprov-jdk15to18" % "1.68",
      "com.google.guava" % "guava" % "29.0-android",
      "org.scala-lang.modules" %% "scala-parser-combinators" % "2.1.0",
      "com.sparrowwallet" % "hummingbird" % "1.6.2",
      "com.squareup.okhttp3" % "okhttp" % "4.9.0",
      "fr.acinq.secp256k1" % "secp256k1-kmp-jni-android" % "0.5.2",
      // Config
      "com.iheart" % "ficus_2.11" % "1.5.0",
      // Testing
      "org.scalatest" % "scalatest_2.11" % "3.1.1",
      "com.typesafe.akka" % "akka-testkit_2.11" % "2.5.32",
      "org.xerial" % "sqlite-jdbc" % "3.27.2.1",
    )
  )
