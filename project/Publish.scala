import sbt.Keys._
import sbt._

object Publish extends AutoPlugin {

  override lazy val projectSettings = Seq(
    pomExtra := ainterfacePomExtra,
    pomIncludeRepository := { _ => false },
    publishArtifact in Test := false,
    publishTo := ainterfacePublishTo.value,
    publishMavenStyle := true
  )

  def ainterfacePomExtra = {
    <url>https://github.com/ainterface/ainterface</url>
    <licenses>
      <license>
        <name>Apache 2 License</name>
        <url>http://www.apache.org/licenses/LICENSE-2.0.html</url>
        <distribution>repo</distribution>
      </license>
    </licenses>
    <scm>
      <url>git@github.com:ainterface/ainterface.git</url>
      <connection>scm:git:git@github.com:ainterface/ainterface.git</connection>
    </scm>
    <developers>
      <developer>
        <id>okumin</id>
        <name>okumin</name>
        <url>http://okumin.com/</url>
      </developer>
    </developers>
  }

  def ainterfacePublishTo = version { (v: String) =>
    val nexus = "https://oss.sonatype.org/"
    if (v.trim.endsWith("SNAPSHOT"))
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases" at nexus + "service/local/staging/deploy/maven2")
  }
}
