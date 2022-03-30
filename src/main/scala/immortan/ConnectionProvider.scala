package immortan

import java.net.{InetSocketAddress, Socket}

trait ConnectionProvider {
  val proxyAddress: Option[InetSocketAddress]
  def getSocket: Socket
  def doWhenReady(action: => Unit): Unit
  def get(url: String): String
}
