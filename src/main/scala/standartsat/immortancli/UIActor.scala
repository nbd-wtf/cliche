package standartsat.immortancli

import akka.actor.Actor
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.eclair.channel.Commitments
import fr.acinq.eclair.{MilliSatoshi, MilliSatoshiLong, randomBytes32}
import fr.acinq.eclair.wire.NodeAddress
import immortan.fsm.{HCOpenHandler, SendMultiPart}
import immortan.{ChannelHosted, ChannelMaster, CommitsAndMax, LNParams, PathFinder, PaymentDescription, RemoteNodeInfo}
import immortan.utils.ImplicitJsonFormats._
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey, sha256}
import fr.acinq.bitcoin.ByteVector32
import immortan.utils.ImplicitJsonFormats.to
import immortan.utils.PaymentRequestExt
import scodec.bits.HexStringSyntax

import scala.util.Try
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

      def onException: Unit = {
        println("onMessage onException")
      }

      // Stop automatic HC opening attempts on getting any kind of local/remote error, this won't be triggered on disconnect
      def onFailure(reason: Throwable) = {
        println("Failed to open HC channel")
        println(reason)
      }

      def onEstablished(cs: Commitments, channel: ChannelHosted) = {
        println("HC established")
        implant(cs, channel)
      }

      def implant(cs: Commitments, freshChannel: ChannelHosted): Unit = {
        //WalletApp.app.prefs.edit.putBoolean(WalletApp.OPEN_HC, false).commit
        //WalletApp.backupSaveWorker.replaceWork(false)
        LNParams.cm.pf process PathFinder.CMDStartPeriodicResync
        LNParams.cm.all += Tuple2(cs.channelId, freshChannel)
        // This removes all previous channel listeners
        freshChannel.listeners = Set(LNParams.cm)
        LNParams.cm.initConnect
        // Update view on hub activity and finalize local stuff
        ChannelMaster.next(ChannelMaster.statusUpdateStream)
        println("[DEBUG] HC implanted")
      }
    }
  }

  def receivePayment(msg: String): Unit = {
    var userStr: String = msg.split(" ").last
    var userInput: Option[Int] = Try(userStr.toInt).toOption

    if (userInput.isEmpty){
      println("Enter receive INTEGER in sat")
      return
    }

    val into = LNParams.cm.all.values
    val CommitsAndMax(cs, maxReceivable) = LNParams.cm.maxReceivable(LNParams.cm sortedReceivable into).get
    val pd = PaymentDescription(split = None, label = None, semanticOrder = None, invoiceText = new String)
    //val descriptionJson: String = "{dummy}"
    //val description: PaymentDescription = to[PaymentDescription](descriptionJson)
    val preimage: ByteVector32 = randomBytes32
    val amountMsat: MilliSatoshi = MilliSatoshi(userInput.getOrElse(0).asInstanceOf[Int]*1000L)
    val prExt = LNParams.cm.makePrExt(toReceive=amountMsat, pd, allowedChans = cs, sha256(preimage), randomBytes32)
    LNParams.cm.payBag.replaceIncomingPayment(prExt, preimage, pd, 0L.msat, Map.empty)
    println(prExt.raw)
  }

  def sendPayment(msg: String): Unit = {
    var invoiceStr: String = msg.split(" ").last
    val prOpt: Option[PaymentRequestExt] = Try(PaymentRequestExt.fromUri(invoiceStr)).toOption // Will throw if invoice is invalid
    if (prOpt.isEmpty){
      println("Incorrect invoice")
      return
    }
    val prExt = prOpt.get

    println("Valid invoice received. Sending...")
    val amount: MilliSatoshi = prExt.pr.amount.get // Will throw if invoice has no amount

    //val feeReserve: MilliSatoshi = LNParams.cm.feeReserve(amount, typicalChainFee = MilliSatoshi(0L), capLNFeeToChain = false, LNParams.maxOffChainFeeAboveRatio)

    val cmd: SendMultiPart = LNParams.cm.makeSendCmd(prExt, amount, LNParams.cm.all.values.toList, MilliSatoshi(0L), false).modify(_.split.totalSum).setTo(amount)

    val pd = PaymentDescription(split = None, label = None, semanticOrder = None, invoiceText = prExt.descriptionOpt getOrElse new String)

    LNParams.cm.payBag.replaceOutgoingPayment(prExt,
      pd, action = None, amount, MilliSatoshi(0L), LNParams.fiatRates.info.rates, MilliSatoshi(0L), System.currentTimeMillis)

    LNParams.cm.localSend(cmd)
    println("Sent")
  }

    def receive =  {
    case msg:String if msg.contains("help") => println("this is IMMORTAN demo app")
    case msg:String if msg == "open" => requestHostedChannel
    case msg:String if msg.contains("receive") => receivePayment(msg)
    case msg:String if msg.contains("send") => sendPayment(msg)
    case _      => println("unknown command")
  }
}
