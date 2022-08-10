//ThisBuild / version := "0.1.0-SNAPSHOT"
//
//ThisBuild / scalaVersion := "2.13.8"
//
//lazy val root = (project in file("."))
//  .settings(
//    name := "Irrigation"
//  )


name := "Irrigation"

version := "0.1.0-SNAPSHOT"

scalaVersion := "2.13.8"

// From https://tpolecat.github.io/2017/04/25/scalac-flags.html
//scalacOptions ++= Seq(
//  "-deprecation",
//  "-feature",
//  "-Ymacro-annotations",
//  )


val catsVersion = "2.7.0"
val catsEffectVersion = "3.3.12"
val akkaVersion = "2.6.19"
val alpakkaVersion = "3.0.4"
val akkaHTTPVersion = "10.2.9"
val circeVersion = "0.14.1"

libraryDependencies ++= Seq(
  "org.scala-lang.modules" %% "scala-parallel-collections" % "1.0.4",
    "org.typelevel" %% "cats-core" % catsVersion,
    "org.typelevel" %% "cats-effect" % catsEffectVersion,

  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "com.enragedginger" %% "akka-quartz-scheduler" % "1.8.5-akka-2.6.x",
  "org.slf4j" % "slf4j-simple" % "1.7.36",

  //  "com.typesafe.akka" %% "akka-http" % akkaHTTPVersion,
  //  "com.typesafe.akka" %% "akka-http-spray-json" % akkaHTTPVersion,

  "com.lightbend.akka" %% "akka-stream-alpakka-mqtt" % alpakkaVersion,
  //  "com.lightbend.akka" %% "akka-stream-alpakka-mqtt-streaming" % alpakkaVersion,
  //  "net.sigusr" %% "fs2-mqtt" % "1.0.0"

  "io.circe" %% "circe-core" % circeVersion,
  "io.circe" %% "circe-generic" % circeVersion,
  "io.circe" %% "circe-generic-extras" % circeVersion,
  "io.circe" %% "circe-parser" % circeVersion,
  "io.circe" %% "circe-yaml" % circeVersion,
  )

//addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.13.2" cross CrossVersion.full)

//run / fork := true
//run / connectInput := true
//run / outputStrategy := Some(StdoutOutput)
