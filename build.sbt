import java.io.{FileInputStream, FileOutputStream}

name := "source-compose"

version := "1.0"

scalaVersion := "2.12.1"

mainClass in assembly := Some("compose.Main")

libraryDependencies ++= Seq(
  "org.yaml" % "snakeyaml" % "1.17"
)

lazy val dist = taskKey[File]("make a dist scompose file")

dist := {
  val assemblyJar = assembly.value

  val distJar = new java.io.File(target.value, "scompose")
  val out = new FileOutputStream(distJar)

  out.write(
    """#!/usr/bin/env sh
      |exec java -jar -XX:+UseG1GC "$0" "$@"
      |""".stripMargin.getBytes)

  val inStream = new FileInputStream(assemblyJar)
  val buffer = new Array[Byte](1024)

  while( inStream.available() > 0) {
    val length = inStream.read(buffer)
    out.write(buffer, 0, length)
  }

  out.close

  distJar.setExecutable(true, false)
  println(s"build scompose at ${distJar.getAbsolutePath}" )

  distJar
}
