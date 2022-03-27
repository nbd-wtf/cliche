package cliche

import org.json4s._
import org.json4s.native.JsonMethods
import org.json4s.JsonDSL.WithDouble._
import caseapp.core
import caseapp.CaseApp
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.eclair.channel.Commitments
import fr.acinq.eclair.{MilliSatoshi, randomBytes32}
import fr.acinq.eclair.wire.{NodeAddress}
import fr.acinq.eclair.payment.{Bolt11Invoice}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey, sha256}
import fr.acinq.bitcoin.ByteVector32
import immortan.fsm.{HCOpenHandler}
import immortan.{
  Channel,
  ChannelHosted,
  ChannelMaster,
  CommitsAndMax,
  ChanAndCommits,
  LNParams,
  PathFinder,
  PaymentDescription,
  RemoteNodeInfo
}
import immortan.utils.PaymentRequestExt
import immortan.crypto.Tools.{~}
import scodec.bits.ByteVector

import scala.util.Try

trait Command

case class UnknownCommand() extends Command
case class ShowError(err: caseapp.core.Error) extends Command
case class GetInfo() extends Command
case class RequestHostedChannel(pubkey: String, host: String, port: Int)
    extends Command
case class CreateInvoice(
    description: Option[String],
    description_hash: Option[String],
    msatoshi: Option[Long],
    preimage: Option[String]
) extends Command
case class PayInvoice(invoice: String, msatoshi: Option[Long]) extends Command
case class CheckInvoice(id: String) extends Command
case class CheckPayment(payment_hash: String) extends Command

object Commands {
  implicit val formats: Formats = DefaultFormats

  def decode(input: String): Command = {
    try {
      val parsed: JValue = JsonMethods.parse(input)
      val method = parsed \ "method"
      val params = parsed \ "params"

      (parsed \ "method").extract[String] match {
        case "get-info"       => params.extract[GetInfo]
        case "request-hc"     => params.extract[RequestHostedChannel]
        case "create-invoice" => params.extract[CreateInvoice]
        case "pay-invoice"    => params.extract[PayInvoice]
        case "check-invoice"  => params.extract[CheckInvoice]
        case "check-payment"  => params.extract[CheckPayment]
        case _                => UnknownCommand()
      }
    } catch {
      case _: Throwable => {
        val spl = input.split(" ")
        val res = spl(0) match {
          case "get-info"       => CaseApp.parse[GetInfo](spl.tail)
          case "request-hc"     => CaseApp.parse[RequestHostedChannel](spl.tail)
          case "create-invoice" => CaseApp.parse[CreateInvoice](spl.tail)
          case "pay-invoice"    => CaseApp.parse[PayInvoice](spl.tail)
          case "check-invoice"  => CaseApp.parse[CheckInvoice](spl.tail)
          case "check-payment"  => CaseApp.parse[CheckPayment](spl.tail)
          case _                => Right(UnknownCommand(), Seq.empty[String])
        }
        res match {
          case Left(err)       => ShowError(err)
          case Right((cmd, _)) => cmd
        }
      }
    }
  }

  def handle(command: Command): Unit = command match {
    case params: ShowError            => Commands.showError(params)
    case _: GetInfo                   => Commands.getInfo()
    case params: RequestHostedChannel => Commands.requestHostedChannel(params)
    case params: CreateInvoice        => Commands.createInvoice(params)
    case params: PayInvoice           => Commands.payInvoice(params)
    case _                            => println("unhandled command", command)
  }

  def showError(params: ShowError): Unit = {
    println(params.err.message)
  }

  def getInfo(): Unit = {
    println(
      JsonMethods.pretty(
        JsonMethods.render(
          // @formatter:off
          ("block_height" -> LNParams.blockCount.get()) ~~
          ("wallets" ->
            LNParams.chainWallets.wallets.map { w =>
              (("label" -> w.info.label) ~~
               ("balance" -> w.info.lastBalance.toLong))
            }
          ) ~~
          ("channels" ->
            LNParams.cm.all.toList.map { kv =>
              (("id" -> kv._1.toHex) ~~
               ("balance" -> kv._2.data.ourBalance.toLong))
            }
          ) ~~
          ("outgoing_payments" ->
            LNParams.cm.allInChannelOutgoing.toList.map { kv =>
              (("hash" -> kv._1.paymentHash.toHex) ~~
               ("htlcs" ->
                 kv._2.map { htlcAdd =>
                   (("id" -> htlcAdd.id) ~~
                    ("msatoshi" -> htlcAdd.amountMsat.toLong) ~~
                    ("channel" -> htlcAdd.channelId.toHex) ~~
                    ("expiry" -> htlcAdd.cltvExpiry.underlying))
                 }
               ))
            }
          )
          // @formatter:on
        )
      )
    )
  }

  def requestHostedChannel(params: RequestHostedChannel): Unit = {
    val localParams =
      LNParams.makeChannelParams(isFunder = false, LNParams.minChanDustLimit)

    ByteVector.fromHex(params.pubkey) match {
      case None                                => {}
      case Some(pubkey) if pubkey.length != 33 => {}
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
        .map(Bolt11Invoice.Description(_))
        .getOrElse(
          params.description_hash
            .flatMap(ByteVector.fromHex(_))
            .map(new ByteVector32(_))
            .map(Bolt11Invoice.DescriptionHash(_))
            .getOrElse(Bolt11Invoice.Description(""))
        )

    // get our route hints
    val hops = LNParams.cm.maxReceivable(
      LNParams.cm sortedReceivable LNParams.cm.all.values
    ) match {
      case None => List()
      case Some(CommitsAndMax(allowedChans, _)) =>
        allowedChans.map(_.commits.updateOpt).zip(allowedChans).collect {
          case Some(usableUpdate) ~ ChanAndCommits(_, commits) =>
            usableUpdate.extraHop(commits.remoteInfo.nodeId) :: Nil
        }
    }

    // invoice secret and fake invoice private key
    val secret = randomBytes32
    val privateKey = LNParams.secret.keys.fakeInvoiceKey(secret)

    // build invoice
    val pr = new Bolt11Invoice(
      Bolt11Invoice.prefixes(LNParams.chainHash),
      msatoshi,
      System.currentTimeMillis / 1000L,
      privateKey.publicKey, {
        val defaultTags = List(
          Some(Bolt11Invoice.PaymentHash(sha256(preimage))),
          Some(descriptionTag),
          Some(Bolt11Invoice.PaymentSecret(secret)),
          Some(Bolt11Invoice.Expiry(3600 * 24 * 2 /* 2 days */ )),
          Some(
            Bolt11Invoice.MinFinalCltvExpiry(
              LNParams.incomingFinalCltvExpiry.underlying
            )
          ),
          Some(
            Bolt11Invoice.InvoiceFeatures(
              Bolt11Invoice.defaultFeatures.unscoped
            )
          )
        ).flatten
        defaultTags ++ hops.map(Bolt11Invoice.RoutingInfo)
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
    Try(PaymentRequestExt.fromUri(params.invoice)).toOption match {
      case None => println("invalid invoice")
      case Some(prExt)
          if prExt.pr.amountOpt.isEmpty && params.msatoshi.isEmpty =>
        println("missing amount")
      case Some(prExt) => {
        val amount =
          params.msatoshi
            .map(MilliSatoshi(_))
            .orElse(prExt.pr.amountOpt)
            .getOrElse(MilliSatoshi(0L))

        val cmd = LNParams.cm
          .makeSendCmd(
            prExt,
            LNParams.cm.all.values.toList,
            MilliSatoshi(2000L),
            amount
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
}
