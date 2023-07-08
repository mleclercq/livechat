addSbtPlugin("org.scalameta"      % "sbt-scalafmt"             % "2.4.6")
addSbtPlugin("org.scala-js"       % "sbt-scalajs"              % "1.13.2")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.0.0")
addSbtPlugin("com.github.sbt"     % "sbt-native-packager"      % "1.9.16")

resolvers ++= Resolver.sonatypeOssRepos("snapshots")
