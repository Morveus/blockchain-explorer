name := """blockchain-explorer"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.7"

resolvers += "pk11 repository" at "http://pk11-scratch.googlecode.com/svn/trunk"

resolvers += "mandubian maven bintray" at "http://dl.bintray.com/mandubian/maven"


resolvers ++= Seq(
  "anormcypher" at "http://repo.anormcypher.org/",
  "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases/"
)


libraryDependencies ++= Seq(
  "org.neo4j" % "neo4j-enterprise" % "2.3.3",
  "org.scala-lang.modules" %% "scala-async" % "0.9.2"
)


libraryDependencies ++= Seq(
  cache,
  jdbc,
  ws,
  anorm,
  filters
)


scalacOptions ++= Seq(
    "-encoding",
    "UTF-8",
    "-deprecation",
    "-unchecked",
    "-feature",
    "-language:postfixOps",
    "-language:implicitConversions",
    "-language:reflectiveCalls"
)