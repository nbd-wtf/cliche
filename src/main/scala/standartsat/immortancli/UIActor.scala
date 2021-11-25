package standartsat.immortancli

import akka.actor.Actor
import fr.acinq.eclair.channel.Commitments
import fr.acinq.eclair.{MilliSatoshi, randomBytes32, MilliSatoshiLong}
import fr.acinq.eclair.wire.NodeAddress
import immortan.fsm.HCOpenHandler
import immortan.{ChannelHosted, CommitsAndMax, LNParams, PaymentDescription, RemoteNodeInfo}
import immortan.utils.ImplicitJsonFormats._
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey, sha256}
import fr.acinq.bitcoin.ByteVector32
import immortan.utils.ImplicitJsonFormats.to
import scodec.bits.HexStringSyntax

import util.control.Breaks._

class UIActor extends Actor {

  val localParams = LNParams.makeChannelParams(LNParams.chainWallets, isFunder = false, LNParams.minChanDustLimit)

  val eclair: RemoteNodeInfo = RemoteNodeInfo(
    PublicKey(hex"02312a1db948a9edacbac5cbe7d5127cd83153ab7d6d2e77ee8c1bb9ece8412216"),
    NodeAddress.unresolved(9735, host = 0, 0, 0, 0), "Eclair")

  def requestHostedChannel: Unit = {
    val localParams = LNParams.makeChannelParams(LNParams.chainWallets, isFunder = false, LNParams.minChanDustLimit)
    new HCOpenHandler(eclair, randomBytes32, localParams.defaultFinalScriptPubKey, LNParams.cm) {
      println("Creating new HC handler")

      // Stop automatic HC opening attempts on getting any kind of local/remote error, this won't be triggered on disconnect
      def onFailure(reason: Throwable) = {
        println("Failed to open HC channel")
      }

      def onEstablished(cs: Commitments, channel: ChannelHosted) = {
        println("HC established")
        implant(cs, channel)
      }

      def implant(cs: Commitments, channel: ChannelHosted): Unit = {
        println("implanted")
        //WalletApp.app.prefs.edit.putBoolean(WalletApp.OPEN_HC, false).commit
        //RemotePeerActivity.implantNewChannel(cs, channel)
      }
    }
  }

  def receivePayment(): Unit = {
    var userInput: Int = 100
    /*
    breakable {
      while (true) {
        try {
          userInput = scala.io.StdIn.readInt()
          break
        } catch {
          case ex: java.lang.NumberFormatException => {
            println("Enter integer number")
          }
          case _ => {
            println("Some other exception. Just put some INTEGER number here")
          }
        }
      }
    }
    */
    val into = LNParams.cm.all.values
    val CommitsAndMax(cs, maxReceivable) = LNParams.cm.maxReceivable(LNParams.cm sortedReceivable into).get
    val pd = PaymentDescription(split = None, label = None, semanticOrder = None, invoiceText = new String)
    //val descriptionJson: String = "{dummy}"
    //val description: PaymentDescription = to[PaymentDescription](descriptionJson)
    val preimage: ByteVector32 = randomBytes32
    val amountMsat: MilliSatoshi = MilliSatoshi(userInput*1000)
    val prExt = LNParams.cm.makePrExt(toReceive=amountMsat, pd, allowedChans = cs, sha256(preimage), randomBytes32)
    LNParams.cm.payBag.replaceIncomingPayment(prExt, preimage, pd, 0L.msat, Map.empty)
    print(prExt.raw)
  }


  def receive = {
    case "help" => println("this is IMMORTAN demo app")
    case "open" => requestHostedChannel
    case "receive"=> receivePayment
    case _      => println("unknown command")
  }
}
