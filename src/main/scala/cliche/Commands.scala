package cliche

import org.json4s._
import org.json4s.native.JsonMethods.{parse}
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.eclair.channel.Commitments
import fr.acinq.eclair.{MilliSatoshi, MilliSatoshiLong, randomBytes32}
import fr.acinq.eclair.wire.NodeAddress
import fr.acinq.eclair.payment.{PaymentRequest}
import immortan.fsm.{HCOpenHandler, SendMultiPart}
import immortan.{
  ChannelHosted,
  ChannelMaster,
  CommitsAndMax,
  ChanAndCommits,
  LNParams,
  PathFinder,
  PaymentDescription,
  RemoteNodeInfo
}
import immortan.utils.ImplicitJsonFormats._
import immortan.crypto.Tools.{~}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey, sha256}
import fr.acinq.bitcoin.ByteVector32
import immortan.utils.ImplicitJsonFormats.to
import immortan.utils.PaymentRequestExt
import scodec.bits.ByteVector

import scala.util.Try
import util.control.Breaks._

trait Command

case class NoCommand() extends Command
case class GetInfo() extends Command
case class RequestHostedChannel(pubkey: String, host: String, port: Int)
    extends Command
case class CreateInvoice(
    description: Option[String],
    description_hash: Option[String],
    msatoshi: Option[Long],
    preimage: Option[String]
) extends Command
case class PayInvoice(bolt11: String, msatoshi: Option[Long]) extends Command
case class CheckInvoice(id: String) extends Command
case class CheckPayment(payment_hash: String) extends Command

object Commands {
  implicit val formats: Formats = DefaultFormats

  def decode(input: String): Command = {
    try {
      val parsed: JValue = parse(input)
      val method = parsed \ "method"
      val params = parsed \ "params"

      (parsed \ "method").extract[String] match {
        case "get-info"               => params.extract[GetInfo]
        case "request-hosted-channel" => params.extract[RequestHostedChannel]
        case "create-invoice"         => params.extract[CreateInvoice]
        case "pay-invoice"            => params.extract[PayInvoice]
        case "check-invoice"          => params.extract[CheckInvoice]
        case "check-payment"          => params.extract[CheckPayment]
        case _                        => NoCommand()
      }
    } catch {
      case _ => NoCommand()
    }
  }

  def handle(command: Command): Unit = command match {
    case GetInfo()                    => Commands.getInfo()
    case params: RequestHostedChannel => Commands.requestHostedChannel(params)
    case params: CreateInvoice        => Commands.createInvoice(params)
    case params: PayInvoice           => Commands.payInvoice(params)
    case _                            => println("unhandled command", command)
  }

  def getInfo(): Unit = {
    println(LNParams.cm.all)
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

  def createInvoice(params: CreateInvoice): Unit = {
    // gather params
    val preimage =
      params.preimage
        .flatMap(ByteVector.fromHex(_))
        .map(new ByteVector32(_))
        .getOrElse(randomBytes32)
    val msatoshi = params.msatoshi.map(MilliSatoshi(_))
    val descriptionTag =
      params.description
        .map(PaymentRequest.Description(_))
        .getOrElse(
          params.description_hash
            .flatMap(ByteVector.fromHex(_))
            .map(new ByteVector32(_))
            .map(PaymentRequest.DescriptionHash(_))
            .getOrElse(PaymentRequest.Description(""))
        )

    // get our route hints
    val CommitsAndMax(allowedChans, maxReceivable) =
      LNParams.cm
        .maxReceivable(LNParams.cm sortedReceivable LNParams.cm.all.values)
        .get
    val hops = allowedChans.map(_.commits.updateOpt).zip(allowedChans).collect {
      case Some(usableUpdate) ~ ChanAndCommits(_, commits) =>
        usableUpdate.extraHop(commits.remoteInfo.nodeId) :: Nil
    }

    // invoice secret and fake invoice private key
    val secret = randomBytes32
    val privateKey = LNParams.secret.keys.fakeInvoiceKey(secret)

    // build invoice
    val pr = new PaymentRequest(
      PaymentRequest.prefixes(LNParams.chainHash),
      msatoshi,
      System.currentTimeMillis / 1000L,
      privateKey.publicKey, {
        val defaultTags = List(
          Some(PaymentRequest.PaymentHash(sha256(preimage))),
          Some(descriptionTag),
          Some(PaymentRequest.PaymentSecret(secret)),
          Some(PaymentRequest.Expiry(3600 * 24 * 2 /* 2 days */ )),
          Some(
            PaymentRequest.MinFinalCltvExpiry(
              LNParams.incomingFinalCltvExpiry.toInt
            )
          ),
          Some(PaymentRequest.basicFeatures)
        ).flatten
        defaultTags ++ hops.map(PaymentRequest.RoutingInfo)
      },
      ByteVector.empty
    ).sign(privateKey)

    // store invoice on database
    val prExt = PaymentRequestExt.from(pr)
    LNParams.cm.payBag.replaceIncomingPayment(
      prex = prExt,
      preimage = preimage,
      description = PaymentDescription(
        split = None,
        label = None,
        semanticOrder = None,
        invoiceText = ""
      ),
      balanceSnap = MilliSatoshi(0L),
      fiatRateSnap = Map.empty
    )

    println(prExt.raw)
  }

  def payInvoice(params: PayInvoice): Unit = {
    Try(PaymentRequestExt.fromUri(params.bolt11)).toOption match {
      case None => println("invalid invoice")
      case _    => println("missing amount")
      case Some(prExt)
          if prExt.pr.amount.isDefined || params.msatoshi.isDefined => {

        val amount = prExt.pr.amount getOrElse params.msatoshi.get
        val cmd = LNParams.cm
          .makeSendCmd(
            prExt,
            amount,
            LNParams.cm.all.values.toList,
            MilliSatoshi(2000L),
            true
          )
          .modify(_.split.totalSum)
          .setTo(amount)

        LNParams.cm.payBag.replaceOutgoingPayment(
          prExt,
          PaymentDescription(
            split = None,
            label = None,
            semanticOrder = None,
            invoiceText = prExt.descriptionOpt getOrElse ""
          ),
          action = None,
          amount,
          MilliSatoshi(0L),
          LNParams.fiatRates.info.rates,
          MilliSatoshi(0L),
          System.currentTimeMillis
        )

        LNParams.cm.localSend(cmd)
        println("sent!")
      }
    }
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
