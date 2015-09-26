import sbt._

object Dependencies {

  val akkaVersion = "2.3.14"

  val akkaActor                     = "com.typesafe.akka"      %% "akka-actor"               % akkaVersion
  val parserCombinators             = "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.4"
  def scalaReflect(version: String) = "org.scala-lang"         %  "scala-reflect"            % version
  val scodec                        = "org.scodec"             %% "scodec-core"              % "1.8.1"

  val akkaTestKit  = "com.typesafe.akka" %% "akka-testkit"                   % akkaVersion % "test"
  val scalaCheck   = "org.scalacheck"    %% "scalacheck"                     % "1.12.4"    % "test"
  val scalaMeter   = "com.storm-enroute" %% "scalameter"                     % "0.7"       % "test"
  val scalaTest    = "org.scalatest"     %% "scalatest"                      % "2.2.5"     % "test"
  val mockito      = "org.mockito"       %  "mockito-core"                   % "1.10.19"   % "test"
}
