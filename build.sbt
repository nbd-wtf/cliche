import java.nio.file.Paths

val cliche = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .settings(
    scalaVersion := "3.2.0",
    libraryDependencies ++= Seq(
      "com.fiatjaf" %%% "immortan" % "0.8.0-SNAPSHOT",
      "org.ekrich" %%% "sconfig" % "1.5.0",
      "com.lihaoyi" %%% "requests" % "0.7.0",
      "com.monovore" %%% "decline" % "2.3.1",
      "org.http4s" %%% "http4s-ember-server" % "1.0.0-M33",
      "org.http4s" %%% "http4s-dsl" % "1.0.0-M33",
    ),
    scalacOptions ++= Seq("-deprecation", "-feature")
  )
  .jsSettings(
    libraryDependencies ++= Seq(
      "org.ekrich" %%% "sjavatime" % "1.1.9"
    ),
    scalaJSUseMainModuleInitializer := true
  )
  .jvmEnablePlugins(NativeImagePlugin)
  .jvmSettings(
    libraryDependencies ++= Seq(
      "org.xerial" % "sqlite-jdbc" % "3.27.2.1"
    ),
    assemblyMergeStrategy := {
      case x if x.endsWith("module-info.class") => MergeStrategy.discard
      case x => (assembly / assemblyMergeStrategy).value(x)
    },
    nativeImageInstalled := true,
    // instead of uncommenting this line, export GRAALVM_HOME=/usr/lib/etc/graalvm-jvm...
    // nativeImageGraalHome := Paths.get("/usr/lib/jvm/graalvm-svm-java11-linux-gluon-22.0.0.3-Final/")
    nativeImageOptions ++= Seq(
      "-H:+ReportUnsupportedElementsAtRuntime",
      "--initialize-at-build-time=scala.Symbol$",
      "--initialize-at-build-time=org.slf4j.LoggerFactory",
      "--allow-incomplete-classpath",

      s"-H:ConfigurationFileDirectories=${target.value / ".." / "native-image-configs" }",
      "-H:+JNI",
      "--no-fallback",

      "--enable-http",
      "--enable-https"
    ),
    nativeImageAgentOutputDir := target.value / ".." / "native-image-configs",
    nativeImageRunAgent / run / javaOptions ++= Seq(
      "-DnativeImageAgent=true",
      "-Dcliche.seed=message fine plug recipe stomach vivid shell skirt ten mystery lemon treat"
    ),
  )
