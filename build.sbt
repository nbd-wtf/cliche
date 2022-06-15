enablePlugins(NativeImagePlugin)

name                  := "cliche"
organization          := "fiatjaf"
scalaVersion          := "2.13.8"
version               := "0.4.0"
libraryDependencies   ++= Seq(
  "com.fiatjaf" % "immortan_2.13" % "0.7.1-SNAPSHOT",
  "com.github.alexarchambault" % "case-app_2.13" % "2.1.0-M13",
  "com.lihaoyi" % "requests_2.13" % "0.7.0",
  "com.iheart" % "ficus_2.13" % "1.5.0",
  "org.xerial" % "sqlite-jdbc" % "3.27.2.1"
)
scalacOptions        ++= Seq("-deprecation", "-feature")
assemblyMergeStrategy := {
  case x if x.endsWith("module-info.class") => MergeStrategy.discard
  case x => (assembly / assemblyMergeStrategy).value(x)
}

mainClass := Some("Main")
nativeImageOptions += "-H:IncludeResources=.*.conf|.*.xml|.*/org/sqlite/.*|org/sqlite/.*"
nativeImageOptions += "--initialize-at-build-time=org.sqlite.JDBC"
nativeImageOptions += "--initialize-at-build-time=org.sqlite.core.DB$ProgressObserver"
nativeImageOptions += "--initialize-at-build-time=org.sqlite.core.DB"
nativeImageOptions += "--initialize-at-build-time=org.sqlite.core.NativeDB"
nativeImageOptions += "--initialize-at-build-time=org.sqlite.ProgressHandler"
nativeImageOptions += "--initialize-at-build-time=org.sqlite.Function"
nativeImageOptions += "--initialize-at-build-time=org.sqlite.Function$Aggregate"
nativeImageOptions += "--initialize-at-build-time=org.sqlite.Function$Window"
nativeImageOptions += "--initialize-at-build-time=java.sql.DriverManager"
nativeImageOptions += "-H:+ReportUnsupportedElementsAtRuntime"
nativeImageOptions += "-H:+TraceClassInitialization"
nativeImageOptions += "--initialize-at-build-time=scala.Symbol$"
nativeImageOptions += "--allow-incomplete-classpath"
nativeImageOptions += "--no-fallback"
