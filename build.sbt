name                  := "cliche"
organization          := "fiatjaf"
scalaVersion          := "2.13.8"
version               := "0.3.0"
libraryDependencies   ++= Seq(
  "com.fiatjaf" % "immortan_2.13" % "0.6.0-SNAPSHOT",
  "com.github.alexarchambault" % "case-app_2.13" % "2.1.0-M13",
  "com.lihaoyi" % "requests_2.13" % "0.7.0",
  "com.iheart" % "ficus_2.13" % "1.5.0",
  "org.xerial" % "sqlite-jdbc" % "3.27.2.1"
)
assemblyMergeStrategy := {
  case x if x.endsWith("module-info.class") => MergeStrategy.discard
  case x => (assembly / assemblyMergeStrategy).value(x)
}
