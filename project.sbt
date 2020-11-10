val scalaTestVersion = "3.2.2"
val scalaCheckVersion = "1.14.1"

organization in Global := "dr.nikson.helm"

crossScalaVersions in Global := Seq("2.13.3", "2.12.12")

scalaVersion in Global := crossScalaVersions.value.head

//scalacOptions in Global := {
//  val old = scalacOptions.value
//
//  scalaVersion.value match {
//    case sv if sv.startsWith("2.13") => old diff List("-Yno-adapted-args")
//    case _                           => old
//  }
//}


lazy val commonSettings = Seq(
//  scalacOptions ~= (_.filterNot(Set("-Yno-adapted-args", "-Ywarn-inaccessible", "-Ywarn-nullary-override"))),
  libraryDependencies ++= Seq(
    "org.scalactic" %% "scalactic" % scalaTestVersion,
    "org.scalatest" %% "scalatest" % scalaTestVersion % "test",
    "org.scalatestplus" %% "scalacheck-1-14" % "3.2.2.0" % "test",
    "org.scalacheck" %% "scalacheck" % scalaCheckVersion % "test",
    "ch.qos.logback" % "logback-classic" % "1.2.3" % "test",
  ),
  scalacOptions := {
      val old = scalacOptions.value

      scalaVersion.value match {
        case sv if sv.startsWith("2.13") => old diff List(
          "-Yno-adapted-args",
          "-Ywarn-inaccessible",
          "-Ywarn-nullary-override",
          "-Yno-adapted-args",
          "-Xstrict-inference",
          "-Xfull-lubs",
          "-Yoverride-objects",
          "-Yoverride-vars",
          "-Yinfer-argument-types",
          "-Yvirtpatmat",
          "-Ywarn-nullary-unit",
          "-Ywarn-infer-any",
        )
        case _                           => old
      }
    }
)

lazy val helm = project.in(file(".")).settings(commonSettings).aggregate(core, http4s)

lazy val core = project.settings(commonSettings)

lazy val http4s = (project dependsOn core).settings(commonSettings)

//enablePlugins(DisablePublishingPlugin)
