enablePlugins(NativeImagePlugin)

name                  := "cliche"
organization          := "fiatjaf"
scalaVersion          := "2.13.8"
version               := "0.5.0"
libraryDependencies   ++= Seq(
  "com.fiatjaf" % "immortan_2.13" % "0.7.2-SNAPSHOT",
  "com.github.alexarchambault" % "case-app_2.13" % "2.1.0-M13",
  "com.lihaoyi" % "requests_2.13" % "0.7.0",
  "com.iheart" % "ficus_2.13" % "1.5.0",
  "org.http4s" % "http4s-blaze-server_2.13" % "1.0.0-M33",
  "org.http4s" % "http4s-dsl_2.13" % "1.0.0-M33",
  "org.xerial" % "sqlite-jdbc" % "3.27.2.1"
)
scalacOptions        ++= Seq("-deprecation", "-feature")
assemblyMergeStrategy := {
  case x if x.endsWith("module-info.class") => MergeStrategy.discard
  case x => (assembly / assemblyMergeStrategy).value(x)
}

mainClass := Some("Main")

import java.nio.file.Paths
nativeImageInstalled := true
// instead of uncommenting this line, export GRAALVM_HOME=/usr/lib/etc/graalvm-jvm...
// nativeImageGraalHome := Paths.get("/usr/lib/jvm/graalvm-svm-java11-linux-gluon-22.0.0.3-Final/")

nativeImageOptions ++= Seq(
  "-H:+ReportUnsupportedElementsAtRuntime",
  "--initialize-at-build-time=scala.Symbol$",
  "--initialize-at-build-time=org.slf4j.LoggerFactory",
  "--allow-incomplete-classpath",

  s"-H:ConfigurationFileDirectories=${target.value / ".." / "native-image-configs" }",
  "-H:+JNI",
  "--no-fallback"
)
nativeImageAgentOutputDir := target.value / ".." / "native-image-configs"

nativeImageRunAgent / run / javaOptions ++= Seq(
  "-DnativeImageAgent=true",
  "-Dcliche.seed=message fine plug recipe stomach vivid shell skirt ten mystery lemon treat"
)
