
libraryDependencies ++= Seq(
  "io.argonaut"                %% "argonaut"          % "6.2.2",
  "org.typelevel"              %% "cats-free"         % "2.1.1",
  "org.typelevel"              %% "cats-effect"       % "2.1.3"
)

addCompilerPlugin("org.spire-math" % "kind-projector" % "0.9.5" cross CrossVersion.binary)

enablePlugins(ScalaTestPlugin, ScalaCheckPlugin)

scalaTestVersion := "3.0.5"

scalaCheckVersion := "1.13.5"
