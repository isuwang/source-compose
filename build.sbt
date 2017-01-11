name := "source-compose"

version := "1.0"

scalaVersion := "2.12.1"

mainClass in assembly := Some("compose.Main")

libraryDependencies ++= Seq(
  "org.yaml" % "snakeyaml" % "1.17"
)
