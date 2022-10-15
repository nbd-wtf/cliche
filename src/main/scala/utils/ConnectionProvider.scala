package utils

import java.net.InetSocketAddress
import requests.get
import scoin.ln.{NodeAddress, IPv4, IPv6, Tor2, Tor3}
import immortan.ConnectionProvider

class RequestsConnectionProvider extends ConnectionProvider {
  override val proxyAddress: Option[InetSocketAddress] = Option.empty
  override def doWhenReady(action: => Unit): Unit = action
  override def getSocket: Socket = new Socket
  override def get(url: String): String = try {
    requests.get(url).text()
  } catch {
    case exc: requests.RequestFailedException => exc.response.data.toString
  }
}

class Socket extends immortan.Socket {
  private val s = new java.net.Socket

  def connect(address: NodeAddress, timeout: Int): Unit = {
    val socketAddress = address match {
      case IPv4(ipv4, port) => new InetSocketAddress(ipv4.toString(), port)
      case IPv6(ipv6, port) => new InetSocketAddress(ipv6.toString(), port)
      case Tor2(tor2, port) => new InetSocketAddress(tor2 + ".onion", port)
      case Tor3(tor3, port) => new InetSocketAddress(tor3 + ".onion", port)
    }
    s.connect(socketAddress, timeout)
  }

  def write(data: Array[Byte]): Unit =
    s.getOutputStream.write(data)

  def read(buffer: Array[Byte], offset: Int, len: Int): Int =
    s.getInputStream.read(buffer, 0, buffer.length)

  def close(): Unit =
    s.close()
}
