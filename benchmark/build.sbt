lazy val benchmark = project
  .in(file("."))
  .settings(
    addCompilerPlugin("org.typelevel" %% "kind-projector"     % "0.10.3"),
    addCompilerPlugin("com.olegpy"    %% "better-monadic-for" % "0.3.1"),
    scalaVersion := "2.13.4",
    scalacOptions ++= Seq(
      "-Ybackend-parallelism",
      "16",
      "-P:bm4:no-filtering:y",
      "-P:bm4:no-tupling:y",
      "-P:bm4:no-map-id:y",
      "-Ymacro-annotations"
    ),
    scalacOptions ~= filterConsoleScalacOptions,
    resolvers ++= Seq(
      Resolver.mavenLocal,
      Resolver.jcenterRepo
    ),
    name := "benchmark",
    mainClass in (Compile, run) := Some("Main"),
    mainClass in assembly := Some("Main"),
    assemblyOutputPath in assembly := baseDirectory.value/ "benchmark.jar",
    libraryDependencies ++= Seq(
      "org.scala-lang.modules" %% "scala-parallel-collections" % "1.0.0",
      "com.github.pathikrit"   %% "better-files"               % "3.9.1",
      "org.apache.commons"      % "commons-compress"           % "1.20"
    )
  )
