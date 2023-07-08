val scala3 = "3.3.0"

val shardcakeVersion = "2.0.6+11-e9d97295-SNAPSHOT" // "2.0.6+10-762c95f6-SNAPSHOT"
val zioVersion       = "2.0.15"

inThisBuild(
  List(
    scalaVersion := scala3
  )
)

name                          := "livechat"
addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")
Global / onChangedBuildSource := ReloadOnSourceChanges

lazy val livechat = crossProject(JSPlatform, JVMPlatform)
  .in(file("."))
  .settings(name := "livechat")
  .settings(publish / skip := true)
  .settings(commonSettings)
  .settings(
    libraryDependencies += "io.circe" %%% "circe-core"    % "0.14.5",
    libraryDependencies += "io.circe" %%% "circe-generic" % "0.14.5",
    libraryDependencies += "io.circe" %%% "circe-parser"  % "0.14.5"
  )
  .jsSettings(
    scalaJSUseMainModuleInitializer             := true,
    scalaJSLinkerConfig ~= {
      _.withModuleKind(ModuleKind.ESModule)
        .withModuleSplitStyle(org.scalajs.linker.interface.ModuleSplitStyle.SmallModulesFor(List("livechart")))
    },
    libraryDependencies += "org.scala-js"      %%% "scalajs-dom"                 % "2.4.0",
    libraryDependencies += "com.raquo"         %%% "laminar"                     % "15.0.1",
    libraryDependencies += "io.laminext"       %%% "core"                        % "0.15.0",
    libraryDependencies += "io.laminext"       %%% "websocket-circe"             % "0.15.0",
    libraryDependencies += "io.laminext"       %%% "fetch-circe"                 % "0.15.0",
    libraryDependencies += "io.laminext"       %%% "validation-core"             % "0.15.0",
    libraryDependencies += "io.github.cquiroz" %%% "scala-java-time"             % "2.5.0",
    libraryDependencies += "org.scala-js"      %%% "scala-js-macrotask-executor" % "1.1.1"
  )
  .jvmSettings(
    run / fork := true,
    libraryDependencies ++=
      Seq(
        "com.devsisters" %%% "shardcake-entities"      % shardcakeVersion,
        "com.devsisters" %%% "shardcake-manager"       % shardcakeVersion,
        "com.devsisters" %%% "shardcake-protocol-grpc" % shardcakeVersion,
        "com.devsisters" %%% "shardcake-storage-redis" % shardcakeVersion,
        "dev.zio"        %%% "zio"                     % zioVersion,
        "dev.zio"        %%% "zio-streams"             % zioVersion,
        "dev.zio"        %%% "zio-logging"             % "2.1.13"
      )
  )
  .jvmConfigure(_.enablePlugins(JavaAppPackaging))

lazy val commonSettings = Def.settings(
  resolvers ++= Resolver.sonatypeOssRepos("snapshots"),
  testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework")),
  libraryDependencies ++=
    Seq(
      "dev.zio" %% "zio-test"     % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test
    ),
  Test / fork    := true,
  scalacOptions ++= Seq(
    "-Wunused:all",
    "-deprecation",
    "-feature",
    "-unchecked",
    "-source",
    "future"
  )
)
