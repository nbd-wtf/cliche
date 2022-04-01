package cliche

import scala.util.Try
import org.json4s._
import org.json4s.native.JsonMethods
import org.json4s.JsonDSL.WithDouble._
import org.json4s.JsonAST.JObject
import caseapp.core
import caseapp.CaseApp
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.eclair.channel.Commitments
import fr.acinq.eclair.{MilliSatoshi, randomBytes32}
import fr.acinq.eclair.wire.{NodeAddress}
import fr.acinq.eclair.payment.{Bolt11Invoice}
import fr.acinq.eclair.transactions.RemoteFulfill
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey, sha256}
import fr.acinq.bitcoin.ByteVector32
import immortan.fsm.{HCOpenHandler, OutgoingPaymentSenderData, IncomingRevealed}
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

import cliche.Config

sealed trait Command
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

  def printjson(x: JObject): Unit =
    println(
      Config.compactJSON match {
        case true  => JsonMethods.compact(JsonMethods.render(x))
        case false => JsonMethods.pretty(JsonMethods.render(x))
      }
    )

  def handle(input: String): Unit = {
    val (id: String, command: Command) =
      try {
        val parsed: JValue = JsonMethods.parse(input)
        val id =
          (
            try { (parsed \ "id").extract[String] }
            catch { case _: Throwable => "" }
          )
        val method = parsed \ "method"
        val params = parsed \ "params"

        (parsed \ "method").extract[String] match {
          case "get-info"       => (id, params.extract[GetInfo])
          case "request-hc"     => (id, params.extract[RequestHostedChannel])
          case "create-invoice" => (id, params.extract[CreateInvoice])
          case "pay-invoice"    => (id, params.extract[PayInvoice])
          case "check-invoice"  => (id, params.extract[CheckInvoice])
          case "check-payment"  => (id, params.extract[CheckPayment])
          case _                => (id, UnknownCommand())
        }
      } catch {
        case _: Throwable => {
          val spl = input.split(" ")
          val res = spl(0) match {
            case "get-info"   => CaseApp.parse[GetInfo](spl.tail)
            case "request-hc" => CaseApp.parse[RequestHostedChannel](spl.tail)
            case "create-invoice" => CaseApp.parse[CreateInvoice](spl.tail)
            case "pay-invoice"    => CaseApp.parse[PayInvoice](spl.tail)
            case "check-invoice"  => CaseApp.parse[CheckInvoice](spl.tail)
            case "check-payment"  => CaseApp.parse[CheckPayment](spl.tail)
            case _                => Right(UnknownCommand(), Seq.empty[String])
          }
          res match {
            case Left(err)       => ("", ShowError(err))
            case Right((cmd, _)) => ("", cmd)
          }
        }
      }

    val response = command match {
      case params: ShowError            => Left(params.err.message)
      case _: GetInfo                   => Commands.getInfo()
      case params: RequestHostedChannel => Commands.requestHC(params)
      case params: CreateInvoice        => Commands.createInvoice(params)
      case params: PayInvoice           => Commands.payInvoice(params)
      case _                            => Left(s"unhandled command $command")
    }

    response match {
      case Left(err) =>
        printjson(
          // @formatter:off
          ("id" -> id) ~~
          ("error" ->
            (("message" -> err) ~~
             ("code" -> 1))
          )
          // @formatter:on
        )
      case Right(result) => printjson(("id" -> id) ~~ ("result" -> result))
    }
  }

  def getInfo(): Either[String, JObject] = {
    Right(
      // @formatter:off
      ("keys" ->
        (("pub" -> LNParams.secret.keys.ourNodePrivateKey.publicKey.toString) ~~
         ("priv" -> LNParams.secret.keys.ourNodePrivateKey.value.toHex) ~~
         ("mnemonics" -> LNParams.secret.mnemonic.mkString(" ")))
      ) ~~
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
      ("known_channels" ->
        (("normal" -> Main.normalBag.getRoutingData.size) ~~
         ("hosted" -> Main.hostedBag.getRoutingData.size))
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
      ) ~~
      ("fiat_rates" -> LNParams.fiatRates.info.rates.filter {
        case (currency: String, _) => currency == "usd"
      }) ~~
      ("fee_rates" ->
        (("1" -> LNParams.feeRates.info.smoothed.feePerBlock(1).toLong) ~~
         ("10" -> LNParams.feeRates.info.smoothed.feePerBlock(10).toLong) ~~
         ("100" -> LNParams.feeRates.info.smoothed.feePerBlock(100).toLong))
      )
      // @formatter:on
    )
  }

  def requestHC(params: RequestHostedChannel): Either[String, JObject] = {
    val localParams =
      LNParams.makeChannelParams(isFunder = false, LNParams.minChanDustLimit)

    ByteVector.fromHex(params.pubkey) match {
      case None => Left("invalid pubkey hex")
      case Some(pubkey) if pubkey.length != 33 =>
        Left("pubkey must be 33 bytes hex")
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
          def onException: Unit = {
            printjson(("event" -> "hc_creation_exception"))
          }

          // Stop automatic HC opening attempts on getting any kind of local/remote error, this won't be triggered on disconnect
          def onFailure(reason: Throwable) = {
            printjson(
              // @formatter:off
              ("event" -> "hc_creation_failed") ~~
              ("reason" -> reason.toString())
              // @formatter:on
            )
          }

          def onEstablished(cs: Commitments, freshChannel: ChannelHosted) = {
            printjson(
              // @formatter:off
              ("event" -> "hc_creation_succeeded") ~~
              ("channel_id" -> cs.channelId.toHex) ~~
              ("remote_peer" -> cs.remoteInfo.nodeId.toString)
              // @formatter:on
            )

            LNParams.cm.pf process PathFinder.CMDStartPeriodicResync
            LNParams.cm.all += Tuple2(cs.channelId, freshChannel)

            // this removes all previous channel listeners
            freshChannel.listeners = Set(LNParams.cm)
            LNParams.cm.initConnect

            // update view on hub activity and finalize local stuff
            ChannelMaster.next(ChannelMaster.statusUpdateStream)
          }
        }

        Right(("channel_being_created" -> true))
      }
    }
  }

  def createInvoice(params: CreateInvoice): Either[String, JObject] = {
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

    if (hops.size == 0) {
      return Left(
        "can't create invoice since you don't have any channels available for receiving"
      )
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

    Right(
      // @formatter:off
      ("invoice" -> prExt.raw) ~~
      ("msatoshi" -> prExt.pr.amountOpt.map(_.toLong)) ~~
      ("payment_hash" -> prExt.pr.paymentHash.toHex) ~~
      ("hints_count" -> prExt.pr.routingInfo.size)
      // @formatter:on
    )
  }

  def payInvoice(params: PayInvoice): Either[String, JObject] = {
    Try(PaymentRequestExt.fromUri(params.invoice)).toOption match {
      case None => Left("invalid invoice")
      case Some(prExt)
          if prExt.pr.amountOpt.isEmpty && params.msatoshi.isEmpty =>
        Left("missing amount")
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
            LNParams.cm.feeReserve(amount),
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
        Right(
          // @formatter:off
          ("sent" -> true) ~~
          ("payee" -> cmd.targetNodeId.toString) ~~
          ("fee_reserve" -> cmd.totalFeeReserve.toLong) ~~
          ("payment_hash" -> cmd.fullTag.paymentHash.toHex)
          // @formatter:on
        )
      }
    }
  }

  def onPaymentFailed(data: OutgoingPaymentSenderData): Unit =
    printjson(
      // @formatter:off
      ("event" -> "payment_failed") ~~
      ("payment_hash" -> data.cmd.fullTag.paymentHash.toHex) ~~
      ("parts" -> data.parts.size) ~~
      ("failure" -> data.failures.map(_.asString))
      // @formatter:on
    )

  def onPaymentSucceeded(
      data: OutgoingPaymentSenderData,
      fulfill: RemoteFulfill
  ): Unit =
    printjson(
      // @formatter:off
      ("event" -> "payment_succeeded") ~~
      ("payment_hash" -> data.cmd.fullTag.paymentHash.toHex) ~~
      ("parts" -> data.parts.size) ~~
      ("fee_msatoshi" -> data.usedFee.toLong)
      // @formatter:on
    )

  def onPaymentReceived(r: IncomingRevealed): Unit =
    printjson(
      // @formatter:off
      ("event" -> "payment_received") ~~
      ("payment_hash" -> r.fullTag.paymentHash.toHex)
      // @formatter:on
    )

  def onReady(): Unit = printjson(("event" -> "ready"))
}
