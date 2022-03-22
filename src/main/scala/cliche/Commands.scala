package cliche

import org.json4s._
import org.json4s.native.JsonMethods.{parse}
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.eclair.channel.Commitments
import fr.acinq.eclair.{MilliSatoshi, MilliSatoshiLong, randomBytes32}
import fr.acinq.eclair.wire.NodeAddress
import immortan.fsm.{HCOpenHandler, SendMultiPart}
import immortan.{
  ChannelHosted,
  ChannelMaster,
  CommitsAndMax,
  LNParams,
  PathFinder,
  PaymentDescription,
  RemoteNodeInfo
}
import immortan.utils.ImplicitJsonFormats._
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey, sha256}
import fr.acinq.bitcoin.ByteVector32
import immortan.utils.ImplicitJsonFormats.to
import immortan.utils.PaymentRequestExt
import scodec.bits.ByteVector

import scala.util.Try
import util.control.Breaks._

trait Command

case class NoCommand() extends Command
case class RequestHostedChannel(pubkey: String, host: String, port: Int)
    extends Command
case class Exit() extends Command

object Commands {
  implicit val formats: Formats = DefaultFormats

  def decode(input: String): Command = {
    try {
      val parsed: JValue = parse(input)

      (parsed \ "method").extract[String] match {
        case "request-hosted-channel" => parsed.extract[RequestHostedChannel]
        case "exit"                   => parsed.extract[Exit]
        case _                        => NoCommand()
      }
    } catch {
      case _ => NoCommand()
    }
  }

  def handle(command: Command): Unit = command match {
    case Exit() => {
      println("Shutting down...")
      LNParams.system.terminate()
      System.exit(0)
    }
    case params: RequestHostedChannel => Commands.requestHostedChannel(params)
    case _                            => {}
  }

  def requestHostedChannel(params: RequestHostedChannel): Unit = {
    val localParams = LNParams.makeChannelParams(
      LNParams.chainWallets,
      isFunder = false,
      LNParams.minChanDustLimit
    )

    ByteVector.fromHex(params.pubkey) match {
      case None => {}
      case Some(pubkey) => {
        val target: RemoteNodeInfo = RemoteNodeInfo(
          PublicKey(pubkey),
          NodeAddress.fromParts(host = params.host, port = params.port),
          "unnamed"
        )

        new HCOpenHandler(
          target,
          randomBytes32,
          localParams.defaultFinalScriptPubKey,
          LNParams.cm
        ) {
          println("Creating new HC handler")

          def onException: Unit = {
            println("onMessage onException")
          }

          // Stop automatic HC opening attempts on getting any kind of local/remote error, this won't be triggered on disconnect
          def onFailure(reason: Throwable) = {
            println("Failed to open HC channel")
            println(reason)
          }

          def onEstablished(cs: Commitments, freshChannel: ChannelHosted) = {
            println("HC established")
            // WalletApp.app.prefs.edit.putBoolean(WalletApp.OPEN_HC, false).commit
            // WalletApp.backupSaveWorker.replaceWork(false)
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
    }
  }

  def receivePayment(msg: String): Unit = {
    var userStr: String = msg.split(" ").last
    var userInput: Option[Int] = Try(userStr.toInt).toOption

    if (userInput.isEmpty) {
      println("Enter receive INTEGER in sat")
      return
    }

    val into = LNParams.cm.all.values
    val CommitsAndMax(cs, maxReceivable) =
      LNParams.cm.maxReceivable(LNParams.cm sortedReceivable into).get
    val pd = PaymentDescription(
      split = None,
      label = None,
      semanticOrder = None,
      invoiceText = new String
    )
    // val descriptionJson: String = "{dummy}"
    // val description: PaymentDescription = to[PaymentDescription](descriptionJson)
    val preimage: ByteVector32 = randomBytes32
    val amountMsat: MilliSatoshi = MilliSatoshi(
      userInput.getOrElse(0).asInstanceOf[Int] * 1000L
    )
    val prExt = LNParams.cm.makePrExt(
      toReceive = amountMsat,
      pd,
      allowedChans = cs,
      sha256(preimage),
      randomBytes32
    )
    LNParams.cm.payBag.replaceIncomingPayment(
      prExt,
      preimage,
      pd,
      0L.msat,
      Map.empty
    )
    println(prExt.raw)
  }

  def sendPayment(msg: String): Unit = {
    var invoiceStr: String = msg.split(" ").last
    val prOpt: Option[PaymentRequestExt] = Try(
      PaymentRequestExt.fromUri(invoiceStr)
    ).toOption // Will throw if invoice is invalid
    if (prOpt.isEmpty) {
      println("Incorrect invoice")
      return
    }
    val prExt = prOpt.get

    println("Valid invoice received. Sending...")
    val amount: MilliSatoshi =
      prExt.pr.amount.get // Will throw if invoice has no amount

    // val feeReserve: MilliSatoshi = LNParams.cm.feeReserve(amount, typicalChainFee = MilliSatoshi(0L), capLNFeeToChain = false, LNParams.maxOffChainFeeAboveRatio)

    val cmd: SendMultiPart = LNParams.cm
      .makeSendCmd(
        prExt,
        amount,
        LNParams.cm.all.values.toList,
        MilliSatoshi(0L),
        false
      )
      .modify(_.split.totalSum)
      .setTo(amount)

    val pd = PaymentDescription(
      split = None,
      label = None,
      semanticOrder = None,
      invoiceText = prExt.descriptionOpt getOrElse new String
    )

    LNParams.cm.payBag.replaceOutgoingPayment(
      prExt,
      pd,
      action = None,
      amount,
      MilliSatoshi(0L),
      LNParams.fiatRates.info.rates,
      MilliSatoshi(0L),
      System.currentTimeMillis
    )

    LNParams.cm.localSend(cmd)
    println("Sent")
  }
}
