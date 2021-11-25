import Dependencies._
import com.typesafe.sbt.packager.docker._

ThisBuild / scalaVersion     := "2.11.12"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "standartsat"

ThisBuild / libraryDependencies ++= Seq(
  compilerPlugin("com.github.ghik" % "silencer-plugin" % "1.7.7" cross CrossVersion.full),
  "com.github.ghik" % "silencer-lib" % "1.7.7" % Provided cross CrossVersion.full
)

ThisBuild / scalacOptions += "-P:silencer:pathFilters=src"

val nixDockerSettings = List(
  name := "sbt-nix-immortan-cli",
  dockerCommands := Seq(
    Cmd("FROM", "base-jre:latest"),
    Cmd("COPY", "1/opt/docker/lib/*.jar", "/lib/"),
    Cmd("COPY", "2/opt/docker/lib/*.jar", "/app.jar"),
    ExecCmd("ENTRYPOINT", "java", "-cp", "/app.jar:/lib/*", "standartsat.immortan-cli.Hello")
  )
)
lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .enablePlugins(DockerPlugin)
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
      // Config
      "com.iheart" % "ficus_2.11" % "1.5.0",
      // Testing
      "org.scalatest" % "scalatest_2.11" % "3.1.1",
      "com.typesafe.akka" % "akka-testkit_2.11" % "2.5.32",
      "org.xerial" % "sqlite-jdbc" % "3.27.2.1",
    )
  )
  .settings(nixDockerSettings: _*)
