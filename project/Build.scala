import sbt.Keys._
import sbt._

object Build extends Build {
  lazy val buildSettings = Seq(
    organization := "com.okumin",
    version      := "0.2",
    scalaVersion := "2.11.8"
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
      Dependencies.parserCombinators,
      Dependencies.scalaReflect(scalaVersion.value),
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
  ).settings(buildSettings: _*).dependsOn(
    ainterface,
    ainterface % "test->test"
  ).settings(
    libraryDependencies ++= Seq(
      Dependencies.scalaCheck,
      Dependencies.scalaTest
    )
  )

  lazy val ainterfacePerformance = Project(
    id = "ainterface-performance",
    base = file("./ainterface-performance")
  ).settings(buildSettings: _*).dependsOn(
    ainterface,
    ainterface % "test->test"
  ).settings(
    resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
    testFrameworks += new TestFramework("org.scalameter.ScalaMeterFramework"),
    parallelExecution in Test := false,
    libraryDependencies ++= Seq(
      Dependencies.scalaCheck,
      Dependencies.scalaMeter
    )
  )
}
