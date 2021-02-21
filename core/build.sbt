
libraryDependencies ++= Seq(
  "io.argonaut"                %% "argonaut"          % "6.3.0",
  "org.typelevel"              %% "cats-free"         % "2.2.0",
  "org.typelevel"              %% "cats-effect"       % "2.2.0"
)

//addCompilerPlugin("org.spire-math" % "kind-projector" % "0.9.5" cross CrossVersion.binary)
addCompilerPlugin("org.typelevel" % "kind-projector" % "0.11.0" cross CrossVersion.full)