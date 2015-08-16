import sbt._

object Dependencies {

  val akkaVersion = "2.3.12"

  val akkaActor    = "com.typesafe.akka" %% "akka-actor"                     % akkaVersion
  val scodec       = "org.scodec"        %% "scodec-core"                    % "1.8.1"

  val akkaTestKit  = "com.typesafe.akka" %% "akka-testkit"                   % akkaVersion % "test"
  val scalaCheck   = "org.scalacheck"    %% "scalacheck"                     % "1.12.4"    % "test"
  val scalaTest    = "org.scalatest"     %% "scalatest"                      % "2.2.5"     % "test"
  val mockito      = "org.mockito"       %  "mockito-core"                   % "1.10.19"   % "test"
}
