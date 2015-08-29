import sbt.Keys._
import sbt._

object Build extends Build {
  lazy val buildSettings = Seq(
    organization := "com.okumin",
    version      := "0.1",
    scalaVersion := "2.11.7"
  )

  lazy val root = Project(
    id = "ainterface-root",
    base = file("./")
  ).aggregate(ainterface)

  lazy val ainterface = Project(
    id = "ainterface",
    base = file("./ainterface")
  ).settings(buildSettings ++ Publish.projectSettings: _*).settings(
    name := "ainterface",
    scalacOptions ++= Seq("-Ypatmat-exhaust-depth", "off"),
    libraryDependencies ++= Seq(
      Dependencies.akkaActor,
      Dependencies.scodec,
      Dependencies.akkaTestKit,
      Dependencies.scalaCheck,
      Dependencies.scalaTest,
      Dependencies.mockito
    )
  )

  lazy val ainterfaceSample = Project(
    id = "ainterface-sample",
    base = file("./ainterface-sample")
  ).settings(buildSettings: _*).dependsOn(ainterface)

  lazy val ainterfaceIntegrationTest = Project(
    id = "ainterface-integration-test",
    base = file("./ainterface-integration-test")
  ).settings(buildSettings: _*).dependsOn(ainterface).settings(
    libraryDependencies ++= Seq(
      Dependencies.scalaCheck,
      Dependencies.scalaTest
    )
  )
}
