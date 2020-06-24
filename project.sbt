organization in Global := "io.getnelson.helm"

crossScalaVersions in Global := Seq("2.13.2", "2.12.4")

scalaVersion in Global := crossScalaVersions.value.head

lazy val helm = project.in(file(".")).aggregate(core, http4s)

lazy val core = project

lazy val http4s = project dependsOn core

enablePlugins(DisablePublishingPlugin)
