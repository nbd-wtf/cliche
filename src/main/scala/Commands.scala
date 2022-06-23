import java.nio.file.{Files, Path, Paths}
import java.text.SimpleDateFormat
import java.util.{Calendar, Date}
import scala.util.{Try, Random, Success, Failure}
import scala.collection.mutable.ArrayBuffer
import org.json4s._
import org.json4s.native.JsonMethods
import org.json4s.JsonDSL.WithDouble._
import org.json4s.JsonAST.{JValue, JObject, JArray}
import caseapp.core
import caseapp.CaseApp
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.eclair.channel.{Commitments, NormalCommits}
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
  HostedCommits,
  ChannelMaster,
  CommitsAndMax,
  ChanAndCommits,
  LNParams,
  PathFinder,
  PaymentDescription,
  RemoteNodeInfo,
  PaymentInfo,
  CommsTower
}
import immortan.utils.PaymentRequestExt
import immortan.crypto.Tools.{~}
import scodec.bits.ByteVector

sealed trait Command
case class UnknownCommand() extends Command
case class ShowError(err: caseapp.core.Error) extends Command
case class Ping() extends Command
case class GetInfo() extends Command
case class RequestHostedChannel(pubkey: String, host: String, port: Int)
    extends Command
case class CreateInvoice(
    description: Option[String],
    description_hash: Option[String],
    msatoshi: Option[Long],
    preimage: Option[String],
    label: Option[String]
) extends Command
case class PayInvoice(invoice: String, msatoshi: Option[Long]) extends Command
case class CheckPayment(hash: String) extends Command
case class ListPayments(count: Option[Int]) extends Command
case class RemoveHostedChannel(id: String) extends Command
case class AcceptOverride(channelId: String) extends Command

object Commands {
  implicit val formats: Formats = DefaultFormats

  def printjson(x: JValue): Unit =
    println(
      Config.compactJSON match {
        case true  => JsonMethods.compact(JsonMethods.render(x))
        case false => JsonMethods.pretty(JsonMethods.render(x))
      }
    )

  val commandLog: scala.collection.mutable.Map[Long, String] =
    scala.collection.mutable.Map.empty

  def writeCommandLog(): Unit = Files.write(
    Paths.get(s"${Config.datadir}/command.log"),
    commandLog
      .map[Tuple2[Long, String]](kv => kv)
      .toList
      .sortBy[Long]({ case (time, _) => time })
      .map({
        case (time, text) => {
          val formattedDate =
            (new SimpleDateFormat("d MMM yyyy HH:mm:ss Z"))
              .format(new Date(time))
          s"[$formattedDate] ${text}"
        }
      })
      .mkString("\n")
      .getBytes()
  )

  def handle(input: String): Unit = {
    val now = Calendar.getInstance().getTime().getTime()

    commandLog(now) = input
    def updateLog(suffix: String): Unit = {
      commandLog.updateWith(now)(pre => pre.map(log => s"$log $suffix"))
    }
    writeCommandLog()

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

        updateLog(method.extract[String])

        method.extract[String] match {
          case "ping"            => (id, params.extract[Ping])
          case "get-info"        => (id, params.extract[GetInfo])
          case "request-hc"      => (id, params.extract[RequestHostedChannel])
          case "create-invoice"  => (id, params.extract[CreateInvoice])
          case "pay-invoice"     => (id, params.extract[PayInvoice])
          case "check-payment"   => (id, params.extract[CheckPayment])
          case "list-payments"   => (id, params.extract[ListPayments])
          case "remove-hc"       => (id, params.extract[RemoveHostedChannel])
          case "accept-override" => (id, params.extract[AcceptOverride])
          case _                 => (id, UnknownCommand())
        }
      } catch {
        case _: Throwable => {
          val spl = input.split(" ")
          val method = spl(0)
          val tail = spl.tail.toIndexedSeq

          updateLog(method)

          val res = method match {
            case "ping"            => CaseApp.parse[Ping](tail)
            case "get-info"        => CaseApp.parse[GetInfo](tail)
            case "request-hc"      => CaseApp.parse[RequestHostedChannel](tail)
            case "create-invoice"  => CaseApp.parse[CreateInvoice](tail)
            case "pay-invoice"     => CaseApp.parse[PayInvoice](tail)
            case "check-payment"   => CaseApp.parse[CheckPayment](tail)
            case "list-payments"   => CaseApp.parse[ListPayments](tail)
            case "remove-hc"       => CaseApp.parse[RemoveHostedChannel](tail)
            case "accept-override" => CaseApp.parse[AcceptOverride](tail)
            case _                 => Right(UnknownCommand(), Seq.empty[String])
          }
          res match {
            case Left(err)       => ("", ShowError(err))
            case Right((cmd, _)) => ("", cmd)
          }
        }
      }

    writeCommandLog()

    val response = command match {
      case params: ShowError            => Left(params.err.message)
      case _: Ping                      => ping()
      case _: GetInfo                   => getInfo()
      case params: RequestHostedChannel => requestHC(params)
      case params: CreateInvoice        => createInvoice(params)
      case params: PayInvoice           => payInvoice(params)
      case params: CheckPayment         => checkPayment(params)
      case params: ListPayments         => listPayments(params)
      case params: RemoveHostedChannel  => removeHC(params)
      case params: AcceptOverride       => acceptOverride(params)
      case _                            => Left(s"unhandled command $command")
    }

    response match {
      case Left(err) => {
        updateLog(s"error: $err")

        printjson(
          // @formatter:off
          ("id" -> id) ~~
          ("error" ->
            (("message" -> err) ~~
             ("code" -> 1))
          )
          // @formatter:on
        )
      }
      case Right(result) => {
        updateLog("success")
        printjson(("id" -> id) ~~ ("result" -> result))
      }
    }

    // filter out commands older than 10 minutes
    commandLog.filterInPlace((time, _) =>
      time > Calendar.getInstance().getTime().getTime() - 10 * 60 * 1000
    )
    writeCommandLog()
  }

  def ping(): Either[String, JValue] = Right(("ping" -> "pong"))

  def getInfo(): Either[String, JValue] = {
    Right(
      // @formatter:off
      ("main_pubkey" -> LNParams.secret.keys.ourNodePrivateKey.publicKey.toString) ~~
      ("block_height" -> LNParams.blockCount.get()) ~~
      ("wallets" ->
        LNParams.chainWallets.wallets.map { w =>
          (("label" -> w.info.label) ~~
           ("balance" -> w.info.lastBalance.toLong))
        }
      ) ~~
      ("channels_total_balance" ->
        LNParams.cm.all.values.map(_.data.ourBalance.toLong).sum
      ) ~~
      ("channels" ->
        LNParams.cm.all.values.map(channelAsJSON)
      ) ~~
      ("known_channels" ->
        (("normal" -> DB.normalBag.getRoutingData.size) ~~
         ("hosted" -> DB.hostedBag.getRoutingData.size))
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

  def requestHC(params: RequestHostedChannel): Either[String, JValue] = {
    val localParams =
      LNParams.makeChannelParams(isFunder = false, LNParams.minChanDustLimit)

    (
      ByteVector.fromHex(params.pubkey),
      Try(
        RemoteNodeInfo(
          PublicKey(ByteVector.fromValidHex(params.pubkey)),
          NodeAddress.fromParts(host = params.host, port = params.port),
          "unnamed"
        )
      ).toEither
    ) match {
      case (Some(pubkey), _) if pubkey.length != 33 =>
        Left("pubkey must be 33 bytes hex")
      case (None, _)    => Left("invalid pubkey hex")
      case (_, Left(_)) => Left("invalid node address or port")
      case (Some(pubkey), Right(target)) => {
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
              ("peer" ->
                (("pubkey" -> cs.remoteInfo.nodeId.toString)) ~~
                 ("our_pubkey" -> cs.remoteInfo.nodeSpecificPubKey.toString) ~~
                 ("addr" -> cs.remoteInfo.address.toString())
              )
              // @formatter:on
            )

            LNParams.cm.pf process PathFinder.CMDStartPeriodicResync
            LNParams.cm.all += Tuple2(cs.channelId, freshChannel)

            // this removes all previous channel listeners
            freshChannel.listeners = Set(LNParams.cm)
            LNParams.cm.initConnect()

            // update view on hub activity and finalize local stuff
            ChannelMaster.next(ChannelMaster.statusUpdateStream)
          }
        }

        Right(("channel_being_created" -> true))
      }
    }
  }

  def createInvoice(params: CreateInvoice): Either[String, JValue] = {
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
    val hops = Random.shuffle(
      LNParams.cm.all.values
        .flatMap(Channel.chanAndCommitsOpt(_))
        .map(chanAndCommits =>
          (
            chanAndCommits.commits.updateOpt,
            chanAndCommits.commits.remoteInfo.nodeId
          )
        )
        .collect { case Some(usableUpdate) ~ nodeId =>
          usableUpdate.extraHop(nodeId) :: Nil
        }
    )

    if (hops.size == 0) {
      return Left(
        "can't create invoice since you don't have any channels available for receiving"
      )
    }

    // invoice secret and fake invoice private key
    val secret = randomBytes32
    val privateKey = LNParams.secret.keys.fakeInvoiceKey(secret)

    Try {
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
                Bolt11Invoice.defaultFeatures.unscoped()
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
          label = params.label,
          semanticOrder = None,
          invoiceText = params.description.getOrElse("")
        ),
        balanceSnap = MilliSatoshi(0L),
        fiatRateSnap = Map.empty
      )

      prExt
    } match {
      case Success(prExt) =>
        Right(
          // @formatter:off
          ("invoice" -> prExt.raw) ~~
          ("msatoshi" -> prExt.pr.amountOpt.map(_.toLong)) ~~
          ("payment_hash" -> prExt.pr.paymentHash.toHex) ~~
          ("hints_count" -> prExt.pr.routingInfo.size)
          // @formatter:on
        )
      case Failure(_) => Left("failed to create the invoice")
    }
  }

  def payInvoice(params: PayInvoice): Either[String, JValue] = {
    Try(PaymentRequestExt.fromUri(params.invoice)).toOption match {
      case None => Left("invalid invoice")
      case Some(prExt)
          if prExt.pr.amountOpt.isEmpty && params.msatoshi.isEmpty =>
        Left("missing amount")
      case Some(prExt) if prExt.pr.paymentSecret == None =>
        Left("missing payment secret")
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

  def checkPayment(params: CheckPayment): Either[String, JValue] = {
    val maybeInfo = for {
      bytes <- ByteVector.fromHex(params.hash)
      hash <- Try(ByteVector32(bytes)).toOption
      info <- LNParams.cm.payBag
        .getPaymentInfo(hash)
        .toOption
    } yield info

    maybeInfo match {
      case Some(info) => Right(paymentAsJSON(info))
      case None => Left(s"couldn't get payment '${params.hash}' from database")
    }
  }

  def listPayments(params: ListPayments): Either[String, JValue] = {
    Right(
      JArray(
        LNParams.cm.payBag
          .listRecentPayments(params.count.getOrElse(5))
          .map(LNParams.cm.payBag.toPaymentInfo)
          .map(paymentAsJSON)
          .toList
      )
    )
  }

  def removeHC(params: RemoveHostedChannel): Either[String, JValue] = {
    val maybeCommits = for {
      bytes <- ByteVector.fromHex(params.id)
      channelId <- Try(ByteVector32(bytes)).toOption
      chan <- LNParams.cm.all.get(channelId)
      chanAndCommits <- Channel.chanAndCommitsOpt(chan)
      commits = chanAndCommits.commits
    } yield commits

    maybeCommits match {
      case Some(hc) => {
        LNParams.cm.chanBag.delete(hc.channelId)
        LNParams.cm.all -= hc.channelId
        ChannelMaster.next(ChannelMaster.stateUpdateStream)
        CommsTower.disconnectNative(hc.remoteInfo)
        Right(("closed" -> true))
      }
      case None =>
        Left(s"invalid or unknown channel id ${params.id}")
    }
  }

  def acceptOverride(params: AcceptOverride): Either[String, JValue] = {
    (for {
      bytes <- ByteVector.fromHex(params.channelId)
      channelId <- Try(ByteVector32(bytes)).toOption
      chan <- LNParams.cm.all.get(channelId)
    } yield chan) match {
      case Some(chan: ChannelHosted) =>
        chan
          .acceptOverride()
          .flatMap(_ => Right(("accepted" -> true)))
      case _ =>
        Left(s"invalid or unknown hosted channel id ${params.channelId}")
    }
  }

  private def channelAsJSON(chan: Channel): JValue = {
    val commits = Channel.chanAndCommitsOpt(chan).map(_.commits)

    val specificNormalOrHostedStuff: JObject = commits match {
      case Some(hc: HostedCommits) =>
        // @formatter:off
        ("hosted_channel" ->
          (("override_proposal" -> hc.overrideProposal.map(_.localBalanceMsat.toLong)) ~~
           ("resize_proposal" -> hc.resizeProposal.map(_.newCapacity.toLong * 1000)))
        )
        // @formatter:on
      case Some(nc: NormalCommits) => ("normal_channel" -> "work in progress")
      case None =>
        ("pending_channel" -> "we don't have any commitments for this one yet")
      case _ =>
        ("weird_channel" -> "we don't really know what channel type this is")
    }

    // @formatter:off
    (("id" -> commits.map(_.channelId.toHex)) ~~
     ("peer" ->
       (("pubkey" -> commits.map(_.remoteInfo.nodeId.toString)) ~~
        ("our_pubkey" -> commits.map(_.remoteInfo.nodeSpecificPubKey.toString)) ~~
        ("addr" -> commits.map(_.remoteInfo.address.toString())))
     ) ~~
     ("balance" -> chan.data.ourBalance.toLong) ~~
     ("can_send" -> commits.map(_.availableForSend.toLong).getOrElse(0L)) ~~
     ("can_receive" -> commits.map(_.availableForReceive.toLong).getOrElse(0L)) ~~
     ("status" -> (
       if (Channel.isOperationalAndOpen(chan)) { "open" }
       else if (Channel.isOperationalAndSleeping(chan)) { "sleeping" }
       else if (Channel.isWaiting(chan)) { "waiting" }
       else if (Channel.isErrored(chan)) { "error" }
       else "unknown"
     )) ~~
     ("policy" ->
       commits.flatMap(_.updateOpt).map(u =>
        (("base_fee" -> u.feeBaseMsat.toLong) ~~
         ("fee_per_millionth" -> u.feeProportionalMillionths) ~~
         ("cltv_delta" -> u.cltvExpiryDelta.underlying) ~~
         ("htlc_min" -> u.htlcMinimumMsat.toLong) ~~
         ("htlc_max" -> u.htlcMaximumMsat.map(_.toLong)))
       )
     ) ~~
     ("inflight" ->
       commits.map(c =>
         (("outgoing" -> c.allOutgoing.size) ~~
          ("incoming" -> c.crossSignedIncoming.size) ~~
          ("revealed" -> c.revealedFulfills.size))
       )
     ) ~~
     specificNormalOrHostedStuff)
    // @formatter:on
  }

  private def paymentAsJSON(info: PaymentInfo): JValue = {
    val msatoshi = info.isIncoming match {
      case true  => info.received.toLong
      case false => info.sent.toLong
    }

    val status = info.status match {
      case 0 => "initial"
      case 1 => "pending"
      case 2 => "failed"
      case 3 => "complete"
    }

    // @formatter:off
    (("is_incoming" -> info.isIncoming) ~~
     ("status" -> status) ~~
     ("seen_at" -> info.seenAt) ~~
     ("invoice" -> info.prString) ~~
     ("preimage" -> info.preimage.toHex) ~~
     ("msatoshi" -> msatoshi) ~~
     ("updated_at" -> info.updatedAt) ~~
     ("fee_msatoshi" -> info.fee.toLong) ~~
     ("payment_hash" -> info.paymentHash.toHex))
    // @formatter:on
  }

  def onPaymentFailed(data: OutgoingPaymentSenderData): Unit =
    printjson(
      // @formatter:off
      ("event" -> "payment_failed") ~~
      ("payment_hash" -> data.cmd.fullTag.paymentHash.toHex) ~~
      ("parts" -> data.parts.size) ~~
      ("routes" -> data. inFlightParts.map(_.route.asString)) ~~
      ("failure" -> data.failures.map(_.asString))
      // @formatter:on
    )

  def onPaymentSucceeded(
      data: OutgoingPaymentSenderData,
      fulfill: RemoteFulfill
  ): Unit = {
    val msatoshi =
      data.inFlightParts.map(_.cmd.firstAmount.toLong).fold[Long](0)(_ + _)

    printjson(
      // @formatter:off
      ("event" -> "payment_succeeded") ~~
      ("payment_hash" -> data.cmd.fullTag.paymentHash.toHex) ~~
      ("fee_msatoshi" -> data.usedFee.toLong) ~~
      ("msatoshi" -> msatoshi) ~~
      ("preimage" -> fulfill.theirPreimage.toHex) ~~
      ("routes" -> data.inFlightParts.map(_.route.asString)) ~~
      ("parts" -> data.parts.size)
      // @formatter:on
    )
  }

  def onPaymentReceived(r: IncomingRevealed): Unit = {
    LNParams.cm.payBag.getPaymentInfo(r.fullTag.paymentHash).toOption match {
      case Some(info) =>
        printjson(
          // @formatter:off
          ("event" -> "payment_received") ~~
          ("preimage" -> info.preimage.toHex) ~~
          ("msatoshi" -> info.received.toLong) ~~
          ("payment_hash" -> r.fullTag.paymentHash.toHex)
          // @formatter:on
        )
      case None => {}
    }
  }

  def onReady(): Unit = printjson(("event" -> "ready"))
}
