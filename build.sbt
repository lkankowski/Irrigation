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
val AkkaVersion = "2.6.14"

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-core" % catsVersion,
  "org.typelevel" %% "cats-effect" % catsEffectVersion,
  "com.lightbend.akka" %% "akka-stream-alpakka-mqtt-streaming" % "3.0.4",
  "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
  "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
  "org.scala-lang.modules" %% "scala-parallel-collections" % "1.0.4"
//  "net.sigusr" %% "fs2-mqtt" % "1.0.0"
)

//addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.13.2" cross CrossVersion.full)

//run / fork := true
//run / connectInput := true
//run / outputStrategy := Some(StdoutOutput)
