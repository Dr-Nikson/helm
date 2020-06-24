
libraryDependencies ++= Seq(
  "io.argonaut"                %% "argonaut"          % "6.3.0",
  "org.typelevel"              %% "cats-free"         % "2.1.1",
  "org.typelevel"              %% "cats-effect"       % "2.1.3"
)

//addCompilerPlugin("org.spire-math" % "kind-projector" % "0.9.5" cross CrossVersion.binary)
addCompilerPlugin("org.typelevel" % "kind-projector" % "0.11.0" cross CrossVersion.full)

enablePlugins(ScalaTestPlugin, ScalaCheckPlugin)

scalaTestVersion := "3.2.0"

scalaCheckVersion := "1.14.3"
