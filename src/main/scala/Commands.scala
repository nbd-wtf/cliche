import scala.util.{Try, Random, Success, Failure}
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.FiniteDuration
import org.json4s.native.JsonMethods
import org.json4s.JsonDSL.WithDouble._
import org.json4s.JsonAST.{JValue, JObject, JArray}
import com.softwaremill.quicklens.ModifyPimp
import fs2.concurrent.Topic
import cats.effect.IO
import cats.effect.std.{Dispatcher, CountDownLatch}
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
import immortan.utils.{PaymentRequestExt, BitcoinUri}
import immortan.crypto.Tools.{~}
import scodec.bits.ByteVector

sealed trait JSONRPCMessage {
  def render(forceCompact: Boolean = false): String = {
    val wrapped = this match {
      case JSONRPCError(id, err) =>
        (
          // @formatter:off
          ("jsonrpc" -> "2.0") ~~
          ("id" -> id) ~~
          ("error" ->
            (("message" -> err) ~~
             ("code" -> 1))
          )
          // @formatter:on
        )
      case JSONRPCResponse(id, result) =>
        (("jsonrpc" -> "2.0") ~~ ("id" -> id) ~~ ("result" -> result))
      case JSONRPCNotification(method, params) =>
        (("jsonrpc" -> "2.0") ~~ ("method" -> method) ~~ ("params" -> params))
    }

    (forceCompact || Config.compactJSON) match {
      case true  => JsonMethods.compact(JsonMethods.render(wrapped))
      case false => JsonMethods.pretty(JsonMethods.render(wrapped))
    }
  }
}
case class JSONRPCResponse(id: String, response: JValue) extends JSONRPCMessage
case class JSONRPCError(id: String, error: String) extends JSONRPCMessage
case class JSONRPCNotification(method: String, params: JValue)
    extends JSONRPCMessage

object Commands {
  def ping(params: Ping)(implicit
      id: String,
      topic: Topic[IO, JSONRPCMessage]
  ): IO[Unit] =
    topic.publish1(JSONRPCResponse(id, ("ping" -> "pong"))) >> IO.unit

  def getInfo(params: GetInfo)(implicit
      id: String,
      topic: Topic[IO, JSONRPCMessage]
  ): IO[Unit] = {
    topic.publish1(
      JSONRPCResponse(
        id,
        (
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
          ("fee_rates" ->
            (("1" -> LNParams.feeRates.info.smoothed.feePerBlock(1).toLong) ~~
             ("10" -> LNParams.feeRates.info.smoothed.feePerBlock(10).toLong) ~~
             ("100" -> LNParams.feeRates.info.smoothed.feePerBlock(100).toLong))
          )
          // @formatter:on
        )
      )
    ) >> IO.unit
  }

  def requestHC(params: RequestHostedChannel)(implicit
      id: String,
      topic: Topic[IO, JSONRPCMessage]
  ): IO[Unit] = {
    val localParams =
      LNParams.makeChannelParams(isFunder = false, LNParams.minChanDustLimit)

    ((
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
        topic.publish1(JSONRPCError(id, "pubkey must be 33 bytes hex"))
      case (None, _) => topic.publish1(JSONRPCError(id, ("invalid pubkey hex")))
      case (_, Left(_)) =>
        topic.publish1(JSONRPCError(id, ("invalid node address or port")))
      case (Some(pubkey), Right(target)) =>
        Dispatcher[IO].use { dispatcher =>
          for {
            blocker <- CountDownLatch[IO](1)
            _ <- IO.delay {
              val cancel = dispatcher.unsafeRunCancelable(
                IO.sleep(FiniteDuration(5, "seconds")) >>
                  topic.publish1(
                    JSONRPCError(id, "taking too long")
                  ) >> blocker.release
              )

              new HCOpenHandler(
                target,
                randomBytes32,
                localParams.defaultFinalScriptPubKey,
                LNParams.cm
              ) {
                def onException: Unit = {
                  dispatcher.unsafeRunAndForget(
                    topic.publish1(JSONRPCError(id, "exception"))
                  )
                  dispatcher.unsafeRunAndForget(blocker.release)
                  cancel()
                }

                // stop automatic HC opening attempts on getting any kind of local/remote error,
                // this won't be triggered on disconnect
                def onFailure(reason: Throwable) = {
                  dispatcher.unsafeRunAndForget(
                    topic.publish1(JSONRPCError(id, reason.toString()))
                  )
                  dispatcher.unsafeRunAndForget(blocker.release)
                  cancel()
                }

                def onEstablished(
                    cs: Commitments,
                    freshChannel: ChannelHosted
                ) = {
                  cancel()
                  dispatcher.unsafeRunAndForget(
                    topic.publish1(
                      JSONRPCResponse(
                        id,
                        (
                          // @formatter:off
                          ("channel_id" -> cs.channelId.toHex) ~~
                          ("peer" ->
                            (("pubkey" -> cs.remoteInfo.nodeId.toString)) ~~
                             ("our_pubkey" -> cs.remoteInfo.nodeSpecificPubKey.toString) ~~
                             ("addr" -> cs.remoteInfo.address.toString())
                          )
                          // @formatter:on
                        )
                      )
                    )
                  )
                  dispatcher.unsafeRunAndForget(blocker.release)

                  LNParams.cm.pf process PathFinder.CMDStartPeriodicResync
                  LNParams.cm.all += Tuple2(cs.channelId, freshChannel)

                  // this removes all previous channel listeners
                  freshChannel.listeners = Set(LNParams.cm)
                  LNParams.cm.initConnect()

                  // update view on hub activity and finalize local stuff
                  ChannelMaster.next(ChannelMaster.statusUpdateStream)
                }
              }
            }
            _ <- blocker.await
          } yield ()
        }
    }) >> IO.unit
  }

  def removeHC(
      params: RemoveHostedChannel
  )(implicit id: String, topic: Topic[IO, JSONRPCMessage]): IO[Unit] = {
    val maybeCommits = for {
      bytes <- ByteVector.fromHex(params.channelId)
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
        topic.publish1(JSONRPCResponse(id, ("closed" -> true))) >> IO.unit
      }
      case None =>
        topic.publish1(
          JSONRPCError(
            id,
            s"invalid or unknown channel id ${params.id}"
          )
        ) >> IO.unit
    }
  }

  def createInvoice(
      params: CreateInvoice
  )(implicit id: String, topic: Topic[IO, JSONRPCMessage]): IO[Unit] = {
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
      return topic.publish1(
        JSONRPCError(
          id,
          "can't create invoice since you don't have any channels available for receiving"
        )
      ) >> IO.unit
    }

    // invoice secret and fake invoice private key
    val secret = randomBytes32
    val privateKey = LNParams.secret.keys.fakeInvoiceKey(secret)

    (Try {
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
        topic.publish1(
          JSONRPCResponse(
            id,
            (
              // @formatter:off
              ("invoice" -> prExt.raw) ~~
              ("msatoshi" -> prExt.pr.amountOpt.map(_.toLong)) ~~
              ("payment_hash" -> prExt.pr.paymentHash.toHex) ~~
              ("hints_count" -> prExt.pr.routingInfo.size)
              // @formatter:on
            )
          )
        )
      case Failure(_) =>
        topic.publish1(JSONRPCError(id, "failed to create the invoice"))
    }) >> IO.unit
  }

  def payInvoice(params: PayInvoice)(implicit
      id: String,
      topic: Topic[IO, JSONRPCMessage]
  ): IO[Unit] = {
    (Try(PaymentRequestExt.fromUri(params.invoice)).toOption match {
      case None => topic.publish1(JSONRPCError(id, "invalid invoice"))
      case Some(prExt)
          if prExt.pr.amountOpt.isEmpty && params.msatoshi.isEmpty =>
        topic.publish1(JSONRPCError(id, "missing amount"))
      case Some(prExt) if prExt.pr.paymentSecret == None =>
        topic.publish1(JSONRPCError(id, "missing payment secret"))
      case Some(prExt)
          if LNParams.cm.checkIfSendable(
            prExt.pr.paymentHash
          ) != PaymentInfo.Sendable =>
        topic.publish1(JSONRPCError(id, "payment already sent or in flight"))
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
          Map.empty,
          MilliSatoshi(0L),
          System.currentTimeMillis
        )

        LNParams.cm.localSend(cmd)

        topic.publish1(
          JSONRPCResponse(
            id,
            (
              // @formatter:off
              ("sent" -> true) ~~
              ("payee" -> cmd.targetNodeId.toString) ~~
              ("fee_reserve" -> cmd.totalFeeReserve.toLong) ~~
              ("payment_hash" -> cmd.fullTag.paymentHash.toHex)
              // @formatter:on
            )
          )
        )
      }
    }) >> IO.unit
  }

  def checkPayment(params: CheckPayment)(implicit
      id: String,
      topic: Topic[IO, JSONRPCMessage]
  ): IO[Unit] = {
    ((for {
      bytes <- ByteVector.fromHex(params.hash)
      hash <- Try(ByteVector32(bytes)).toOption
      info <- LNParams.cm.payBag
        .getPaymentInfo(hash)
        .toOption
    } yield info) match {
      case Some(info) =>
        topic.publish1(JSONRPCResponse(id, paymentAsJSON(info)))
      case None =>
        topic.publish1(
          JSONRPCError(
            id,
            s"couldn't get payment '${params.hash}' from database"
          )
        )
    }) >> IO.unit
  }

  def listPayments(
      params: ListPayments
  )(implicit id: String, topic: Topic[IO, JSONRPCMessage]): IO[Unit] = {
    topic.publish1(
      JSONRPCResponse(
        id,
        JArray(
          LNParams.cm.payBag
            .listRecentPayments(params.count.getOrElse(5))
            .map(LNParams.cm.payBag.toPaymentInfo)
            .map(paymentAsJSON)
            .toList
        )
      )
    ) >> IO.unit
  }

  def getAddress(params: GetAddress)(implicit
      id: String,
      topic: Topic[IO, JSONRPCMessage]
  ): IO[Unit] = {
    implicit val ec: scala.concurrent.ExecutionContext =
      scala.concurrent.ExecutionContext.global

    Dispatcher[IO].use { dispatcher =>
      for {
        blocker <- CountDownLatch[IO](1)
        _ <- IO.delay {
          LNParams.chainWallets.wallets.head.getReceiveAddresses
            .onComplete {
              case Success(resp) => {
                val address = resp.keys
                  .take(1)
                  .map(resp.ewt.textAddress)
                  .head

                dispatcher.unsafeRunAndForget(
                  topic.publish1(
                    JSONRPCResponse(
                      id,
                      ("address" -> address)
                    )
                  )
                )
                dispatcher.unsafeRunAndForget(blocker.release)
              }
              case Failure(err) => {
                dispatcher.unsafeRunAndForget(
                  topic.publish1(JSONRPCError(id, err.toString()))
                )
                dispatcher.unsafeRunAndForget(blocker.release)
              }
            }
        }
        _ <- blocker.await
      } yield ()
    }
  }

  def sendToAddress(
      params: SendToAddress
  )(implicit id: String, topic: Topic[IO, JSONRPCMessage]): IO[Unit] = IO.stub

  def openNC(
      params: OpenNormalChannel
  )(implicit id: String, topic: Topic[IO, JSONRPCMessage]): IO[Unit] = IO.stub

  def closeNC(
      params: CloseNormalChannel
  )(implicit id: String, topic: Topic[IO, JSONRPCMessage]): IO[Unit] = IO.stub

  def acceptOverride(
      params: AcceptOverride
  )(implicit id: String, topic: Topic[IO, JSONRPCMessage]): IO[Unit] = {
    (for {
      bytes <- ByteVector.fromHex(params.channelId)
      channelId <- Try(ByteVector32(bytes)).toOption
      chan <- LNParams.cm.all.get(channelId)
    } yield chan) match {
      case Some(chan: ChannelHosted) =>
        chan.acceptOverride() match {
          case Right(_) =>
            topic.publish1(JSONRPCResponse(id, ("accepted" -> true))) >> IO.unit
          case Left(err) => topic.publish1(JSONRPCError(id, err)) >> IO.unit
        }
      case _ =>
        topic.publish1(
          JSONRPCError(
            id,
            s"invalid or unknown hosted channel id ${params.channelId}"
          )
        ) >> IO.unit
    }
  }

  private def channelAsJSON(chan: Channel): JValue = {
    val commits = Channel.chanAndCommitsOpt(chan).map(_.commits)

    val specificNormalOrHostedStuff: JObject = commits match {
      case Some(hc: HostedCommits) =>
        // @formatter:off
        ("hosted_channel" ->
          (("override_proposal" ->
            ("their_balance" -> hc.overrideProposal.map(_.localBalanceMsat.toLong)) ~~
            ("our_balance" -> hc.overrideProposal.map(_.remoteBalanceMsat.toLong))
          ) ~~
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

  def onPaymentFailed(data: OutgoingPaymentSenderData): JSONRPCNotification =
    JSONRPCNotification(
      "payment_failed",
      (
        // @formatter:off
        ("payment_hash" -> data.cmd.fullTag.paymentHash.toHex) ~~
        ("parts" -> data.parts.size) ~~
        ("routes" -> data. inFlightParts.map(_.route.asString)) ~~
        ("failure" -> data.failures.map(_.asString))
        // @formatter:on
      )
    )

  def onPaymentSucceeded(
      data: OutgoingPaymentSenderData
  ): JSONRPCNotification = {
    val msatoshi =
      data.inFlightParts
        .map(_.cmd.firstAmount.toLong)
        .fold[Long](0)(_ + _) - data.usedFee.toLong

    JSONRPCNotification(
      "payment_succeeded",
      (
        // @formatter:off
        ("payment_hash" -> data.cmd.fullTag.paymentHash.toHex) ~~
        ("fee_msatoshi" -> data.usedFee.toLong) ~~
        ("msatoshi" -> msatoshi) ~~
        ("preimage" -> data.preimage.get.toHex) ~~
        ("routes" -> data.inFlightParts.map(_.route.asString)) ~~
        ("parts" -> data.parts.size)
        // @formatter:on
      )
    )
  }

  def onPaymentReceived(r: IncomingRevealed): JSONRPCNotification =
    JSONRPCNotification(
      "payment_received",
      LNParams.cm.payBag.getPaymentInfo(r.fullTag.paymentHash).toOption match {
        case Some(info) => (
          // @formatter:off
          ("preimage" -> r.preimage.toHex) ~~
          ("msatoshi" -> info.received.toLong) ~~
          ("payment_hash" -> r.fullTag.paymentHash.toHex)
          // @formatter:on
        )
        case None => (
          // @formatter:off
          ("preimage" -> r.preimage.toHex) ~~
          ("payment_hash" -> r.fullTag.paymentHash.toHex)
          // @formatter:on
        )
      }
    )

  def onReady() = JSONRPCNotification("ready", JObject(List.empty))
}
