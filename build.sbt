name := "source-compose"

version := "1.0"

scalaVersion := "2.12.1"

mainClass in assembly := Some("compose.Main")

libraryDependencies ++= Seq(
  "org.yaml" % "snakeyaml" % "1.17"
  // , "org.eclipse.jgit" % "org.eclipse.jgit" % "4.4.0.201606070830-r"
  // , "org.eclipse.jgit" % "org.eclipse.jgit.pgm" % "4.4.0.201606070830-r" excludeAll(
  //    ExclusionRule(organization = "javax.jms"), ExclusionRule(organization = "com.sun.jdmk"), ExclusionRule(organization = "com.sun.jmx")
  //  )
)
