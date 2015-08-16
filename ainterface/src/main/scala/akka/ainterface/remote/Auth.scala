package akka.ainterface.remote

import akka.ainterface.NodeName
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.{Files, Path}
import java.security.SecureRandom
import java.util
import java.util.concurrent.{ConcurrentHashMap, ConcurrentMap}
import scala.util.Random

/**
 * Represents a cookie of Erlang.
 */
private[ainterface] final case class ErlCookie(value: String)

private[ainterface] object ErlCookie {
  val NoCookie: ErlCookie = ErlCookie("nocookie")
}

/**
 * Manages cookies.
 */
private[ainterface] class Auth(localNodeName: NodeName, cookie: ErlCookie) {
  @volatile private[remote] var ourCookie: ErlCookie = cookie
  private[remote] val cookies: ConcurrentMap[NodeName, ErlCookie] = new ConcurrentHashMap()

  def getCookie: ErlCookie = getCookie(localNodeName)

  def getCookie(node: NodeName): ErlCookie = localNodeName match {
    case NodeName.NoNode => ErlCookie.NoCookie
    case `node` => ourCookie
    case _ => Option(cookies.get(node)).getOrElse(ourCookie)
  }

  def setCookie(cookie: ErlCookie): Unit = setCookie(localNodeName, cookie)

  def setCookie(node: NodeName, cookie: ErlCookie): Unit = localNodeName match {
    case NodeName.NoNode => sys.error("This node is not a distributed node.")
    case `node` => ourCookie = cookie
    case _ => cookies.put(node, cookie)
  }
}

private[ainterface] object Auth {
  private val DefaultCookieLength = 20
  private val CookieFileName = ".erlang.cookie"

  def apply(localNodeName: NodeName, setCookie: ErlCookie): Auth = {
    new Auth(localNodeName, setCookie)
  }

  def apply(localNodeName: NodeName, home: Path): Auth = {
    new Auth(localNodeName, readCookie(home))
  }

  private[this] def readCookie(home: Path): ErlCookie = {
    assert(home.isAbsolute)
    assert(Files.isDirectory(home))
    val cookieFile = home.resolve(Auth.CookieFileName)
    if (Files.exists(cookieFile)) {
      val bytes = Files.readAllBytes(cookieFile)
      ErlCookie(new String(bytes, StandardCharsets.ISO_8859_1))
    } else {
      createCookie(cookieFile)
      readCookie(home)
    }
  }

  private[this] def createCookie(file: Path): Unit = {
    val random = new Random(new SecureRandom())
    def randomCookie(): Array[Byte] = {
      val letters = ('A' to 'Z').toArray
      (1 to Auth.DefaultCookieLength).map { _ =>
        letters(random.nextInt(letters.length))
      }.map(_.toByte).toArray
    }

    val cookie = randomCookie()
    Files.createFile(file)
    Files.write(file, cookie)
    val permission = util.EnumSet.of(PosixFilePermission.OWNER_READ)
    Files.setPosixFilePermissions(file, permission)
  }
}
