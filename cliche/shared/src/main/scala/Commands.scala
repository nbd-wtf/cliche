import scala.util.chaining._
import scala.util.{Try, Random, Success, Failure}
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.FiniteDuration
import com.softwaremill.quicklens._
import scodec.bits.ByteVector
import fs2.concurrent.Topic
import io.circe._
import io.circe.syntax._
import io.circe.generic.auto._
import cats.effect.IO
import cats.effect.std.{Dispatcher, CountDownLatch}
import scoin.{
  ByteVector32,
  Satoshi,
  ShortChannelId,
  MilliSatoshi,
  TimestampSecond,
  randomBytes32
}
import scoin.Crypto.{PrivateKey, PublicKey, sha256}
import scoin.ln.{Bolt11Invoice, NodeAddress}
import immortan._
import immortan.channel.{
  Commitments,
  NormalCommits,
  DATA_NORMAL,
  RemoteFulfill,
  HostedCommits
}
import immortan.fsm.{
  HCOpenHandler,
  OutgoingPaymentListener,
  OutgoingPaymentSenderData,
  IncomingRevealed
}
import immortan.utils.{
  InputParser,
  PayRequest,
  PaymentRequestExt,
  BitcoinUri,
  LNUrl
}
import immortan.LNParams.ec

sealed trait JSONRPCMessage {
  def render(forceCompact: Boolean = false): String = {
    val wrapped = this match {
      case JSONRPCError(id, err) =>
        Json.obj(
          "jsonrpc" := "2.0",
          "id" := id,
          "error" := Json.obj(
            "message" := err,
            "code" := 1
          )
        )
      case JSONRPCResponse(id, result) =>
        Json.obj("jsonrpc" := "2.0", "id" := id, "result" := result)
      case JSONRPCNotification(method, params) =>
        Json.obj("jsonrpc" := "2.0", "method" := method, "params" := params)
      case _ =>
        Json.arr("should".asJson, "never".asJson, "happen".asJson).asJson
    }

    (forceCompact || Config.compactJSON) match {
      case true  => wrapped.noSpaces
      case false => wrapped.toString
    }
  }
}
case class JSONRPCResponse(id: String, response: Json) extends JSONRPCMessage
case class JSONRPCError(id: String, error: String) extends JSONRPCMessage
case class JSONRPCNotification(method: String, params: Json)
    extends JSONRPCMessage

// special for CLI
case class RawCLIString(str: String) extends JSONRPCMessage {
  override def render(irrelevant: Boolean): String = str
}

object Commands {
  def ping(params: Ping)(implicit
      id: String,
      topic: Topic[IO, JSONRPCMessage]
  ): IO[Unit] =
    topic.publish1(JSONRPCResponse(id, Json.obj("ping" := "pong"))) >> IO.unit

  def getInfo(params: GetInfo)(implicit
      id: String,
      topic: Topic[IO, JSONRPCMessage]
  ): IO[Unit] = {
    topic.publish1(
      JSONRPCResponse(
        id,
        Json.obj(
          "chain" := LNParams.chainHash.toHex,
          "main_pubkey" := LNParams.secret.keys.ourNodePrivateKey.publicKey.toString,
          "block_height" := LNParams.blockCount.get(),
          "wallets" := LNParams.chainWallets.wallets.map { w =>
            Json.obj(
              "label" := w.info.label,
              "balance" := w.info.lastBalance.toLong,
              "utxos" := w.getData.utxos.map { utxo =>
                Json.obj(
                  "txid" := utxo.item.txHash.toHex,
                  "output" := utxo.item.txPos,
                  "height" := utxo.item.height,
                  "value" := utxo.item.value
                )
              }
            )
          },
          "channels_total_balance" := LNParams.cm.all.values
            .map(_.data.ourBalance.toLong)
            .sum,
          "channels" := LNParams.cm.all.values.map(channelAsJSON),
          "known_channels" := Json.obj(
            "normal" := DB.normalBag.getRoutingData.size,
            "hosted" := DB.hostedBag.getRoutingData.size
          ),
          "outgoing_payments" := LNParams.cm.allInChannelOutgoing.toList.map {
            kv =>
              Json.obj(
                "hash" := kv._1.paymentHash.toHex,
                "htlcs" := kv._2.map { htlcAdd =>
                  Json.obj(
                    "id" := htlcAdd.id,
                    "msatoshi" := htlcAdd.amountMsat.toLong,
                    "channel" := htlcAdd.channelId.toHex,
                    "expiry" := htlcAdd.cltvExpiry.toLong
                  )
                }
              )
          },
          "fee_rates" := Json.obj(
            "1" := LNParams.feeRates.info.smoothed.feePerBlock(1),
            "10" := LNParams.feeRates.info.smoothed.feePerBlock(10),
            "100" := LNParams.feeRates.info.smoothed.feePerBlock(100)
          )
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

    (
      ByteVector.fromHex(params.pubkey),
      Try(
        RemoteNodeInfo(
          PublicKey(ByteVector.fromValidHex(params.pubkey)),
          NodeAddress.fromParts(host = params.host, port = params.port).get,
          params.label.getOrElse("unnamed")
        )
      ).toEither
    ) match {
      case (Some(pubkey), _) if pubkey.length != 33 =>
        topic.publish1(
          JSONRPCError(id, "pubkey must be 33 bytes hex")
        ) >> IO.unit
      case (None, _) =>
        topic.publish1(JSONRPCError(id, ("invalid pubkey hex"))) >> IO.unit
      case (_, Left(_)) =>
        topic.publish1(
          JSONRPCError(id, ("invalid node address or port"))
        ) >> IO.unit
      case (Some(pubkey), Right(target)) =>
        for {
          response <- IO.async_[JSONRPCMessage] { cb =>
            val t = new java.util.Timer()
            val task = new java.util.TimerTask {
              def run() = cb(
                Right(
                  JSONRPCError(
                    id,
                    "channel establishment is taking too long -- although it may still succeed we'll stop watching"
                  )
                )
              )
            }
            t.schedule(task, 5000L)

            new HCOpenHandler(
              target,
              params.secret
                .flatMap(ByteVector.fromHex(_).map(ByteVector32(_)))
                .getOrElse(randomBytes32()),
              localParams.defaultFinalScriptPubKey,
              LNParams.cm
            ) {
              def onException: Unit = {
                cb(Right(JSONRPCError(id, "exception")))
              }

              // stop automatic HC opening attempts on getting any kind of local/remote error,
              // this won't be triggered on disconnect
              def onFailure(reason: Throwable) = {
                cb(Right(JSONRPCError(id, reason.toString())))
              }

              def onEstablished(
                  cs: Commitments,
                  freshChannel: ChannelHosted
              ) = {
                cb(
                  Right(
                    JSONRPCResponse(
                      id,
                      Json.obj(
                        "channel_id" := cs.channelId.toHex,
                        "peer" := Json.obj(
                          "pubkey" := cs.remoteInfo.nodeId.toString,
                          "our_pubkey" := cs.remoteInfo.nodeSpecificPubKey.toString,
                          "addr" := cs.remoteInfo.address.toString
                        )
                      )
                    )
                  )
                )

                LNParams.cm.pf.process(PathFinder.CMDStartPeriodicResync)
                LNParams.cm.all += Tuple2(cs.channelId, freshChannel)

                // this removes all previous channel listeners
                freshChannel.listeners = Set(LNParams.cm)
                LNParams.cm.initConnect()

                // update view on hub activity and finalize local stuff
                ChannelMaster.statusUpdateStream.fire()
              }
            }
          }
          _ <- topic.publish1(response)
        } yield ()
    }
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
        ChannelMaster.stateUpdateStream.fire()
        CommsTower.disconnectNative(hc.remoteInfo)
        topic.publish1(
          JSONRPCResponse(id, Json.obj("closed" := true))
        ) >> IO.unit
      }
      case None =>
        topic.publish1(
          JSONRPCError(
            id,
            s"invalid or unknown channel id ${params.channelId}"
          )
        ) >> IO.unit
    }
  }

  def createInvoice(
      params: CreateInvoice
  )(implicit id: String, topic: Topic[IO, JSONRPCMessage]): IO[Unit] = {
    // gather params
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
    val hops = LNParams.cm.all.values
      .flatMap(Channel.chanAndCommitsOpt(_))
      .filter { case ChanAndCommits(chan, _) => !Channel.isErrored(chan) }
      .pipe(Random.shuffle(_))
      .toList
      .sortBy {
        case ChanAndCommits(c, _) if Channel.isOperationalAndOpen(c) => 0
        case ChanAndCommits(c, _) if Channel.isOperationalAndSleeping(c) =>
          1
        case _ => 2
      }
      .sortBy(chanAndCommits =>
        chanAndCommits.commits.availableForReceive.toLong * -1
      )
      .map { case ChanAndCommits(_, m) => (m.updateOpt, m.remoteInfo.nodeId) }
      .collect { case Some(upd) ~ nodeId => upd.extraHop(nodeId) :: Nil }
      .take(3)

    if (hops.size == 0) {
      return topic.publish1(
        JSONRPCError(
          id,
          "can't create invoice since you don't have any channels available for receiving"
        )
      ) >> IO.unit
    }

    // invoice secret and fake invoice private key
    val secret = randomBytes32()
    val preimage = LNParams.cm.preimageFromSecret(secret)
    val privateKey = LNParams.secret.keys.fakeInvoiceKey(secret)

    (Try {
      // build invoice
      val pr = new Bolt11Invoice(
        Bolt11Invoice.prefixes.collectFirst {
          case (hash, prefix) if hash == LNParams.chainHash => prefix
        }.get,
        msatoshi,
        TimestampSecond(System.currentTimeMillis / 1000L),
        privateKey.publicKey, {
          val defaultTags = List(
            Some(Bolt11Invoice.PaymentHash(sha256(preimage))),
            Some(descriptionTag),
            Some(Bolt11Invoice.PaymentSecret(secret)),
            Some(Bolt11Invoice.Expiry(3600 * 24 * 2 /* 2 days */ )),
            Some(
              Bolt11Invoice.MinFinalCltvExpiry(
                LNParams.incomingFinalCltvExpiry.toInt
              )
            ),
            Some(
              Bolt11Invoice.InvoiceFeatures(
                Bolt11Invoice.defaultFeatures.unscoped()
              )
            )
          ).flatten
          defaultTags ++ hops.map(Bolt11Invoice.RoutingInfo(_))
        },
        ByteVector.empty
      ).sign(privateKey)

      PaymentRequestExt.from(pr)
    } match {
      case Success(prExt) =>
        topic.publish1(
          JSONRPCResponse(
            id,
            Json.obj(
              "invoice" := prExt.raw,
              "msatoshi" := prExt.pr.amountOpt.map(_.toLong),
              "payment_hash" := prExt.pr.paymentHash.toHex,
              "hints_count" := prExt.pr.routingInfo.size
            )
          )
        )
      case Failure(_) =>
        topic.publish1(JSONRPCError(id, "failed to create the invoice"))
    }) >> IO.unit
  }

  def payInvoice(
      params: PayInvoice,
      extraListeners: Set[OutgoingPaymentListener] = Set.empty
  )(implicit
      id: String,
      topic: Topic[IO, JSONRPCMessage]
  ): IO[Unit] =
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

        LNParams.cm.localSend(cmd, extraListeners)

        topic.publish1(
          JSONRPCResponse(
            id,
            Json.obj(
              "msatoshi" := amount.toLong,
              "fee_reserve" := cmd.totalFeeReserve.toLong,
              "payment_hash" := cmd.fullTag.paymentHash.toHex,
              "payee" := cmd.targetNodeId.toString
            )
          )
        )
      }
    }) >> IO.unit

  def payLnurl(params: PayLnurl)(implicit
      id: String,
      topic: Topic[IO, JSONRPCMessage]
  ): IO[Unit] = {
    Dispatcher[IO].use { dispatcher =>
      for {
        dummyTopic <- Topic[IO, JSONRPCMessage]
        blocker <- CountDownLatch[IO](1)
        _ <- IO.delay {
          def onBadResponse(err: Throwable): Unit = {
            try {
              dispatcher.unsafeRunAndForget(
                topic.publish1(
                  JSONRPCError(id, err.toString)
                ) >> dummyTopic.close >> blocker.release
              )
            } catch {
              case _: Throwable => {}
            }
          }

          Try(InputParser.parse(params.lnurl)) match {
            case Success(lnurl: LNUrl) => {
              lnurl.level1DataResponse.map {
                case lnurlpay: PayRequest =>
                  dispatcher.unsafeRunAndForget(
                    topic.publish1(
                      JSONRPCNotification(
                        "lnurlpay_params",
                        Json.obj(
                          "min" := lnurlpay.minSendable,
                          "max" := lnurlpay.maxSendable,
                          "callback" := lnurlpay.callback,
                          "description" := lnurlpay.meta.textShort,
                          "comment" := lnurlpay.commentAllowed,
                          "payerdata" := lnurlpay.payerData.map(pds =>
                            Json.obj(
                              "auth" := pds.auth.isDefined,
                              "name" := pds.name.isDefined,
                              "pubkey" := pds.pubkey.isDefined
                            )
                          )
                        )
                      )
                    )
                  )

                  if (
                    lnurlpay.maxSendable < params.msatoshi || lnurlpay.minSendable > params.msatoshi
                  ) {
                    dispatcher.unsafeRunAndForget(
                      topic.publish1(
                        JSONRPCError(
                          id,
                          s"amount provided (${params.msatoshi}) is not in the acceptable range ${lnurlpay.minSendable}-${lnurlpay.maxSendable}"
                        )
                      ) >> dummyTopic.close >> blocker.release
                    )
                  } else {
                    lnurlpay
                      .getFinal(
                        amount = MilliSatoshi(params.msatoshi),
                        comment = params.comment,
                        name = params.name,
                        authKeyHost =
                          if (params.attachAuth)
                            Some(lnurl.url.hostOption.get.value)
                          else None
                      )
                      .onComplete {
                        case Success(lnurlpayfinal) =>
                          dispatcher.unsafeRunAndForget(
                            payInvoice(
                              PayInvoice(
                                invoice = lnurlpayfinal.pr,
                                msatoshi = None
                              ),
                              Set(new OutgoingPaymentListener {
                                override def gotFirstPreimage(
                                    data: OutgoingPaymentSenderData,
                                    fulfill: RemoteFulfill
                                ): Unit =
                                  dispatcher.unsafeRunAndForget(
                                    topic.publish1(
                                      JSONRPCNotification(
                                        "lnurlpay_success",
                                        Json.obj(
                                          "success_action" := lnurlpayfinal.successAction
                                            .map(
                                              _.printable(fulfill.theirPreimage)
                                            )
                                        )
                                      )
                                    ) >> blocker.release
                                  )

                                override def wholePaymentFailed(
                                    data: OutgoingPaymentSenderData
                                ): Unit = dispatcher
                                  .unsafeRunAndForget(blocker.release)
                              })
                            )(id, dummyTopic)
                          )
                        case Failure(err) => onBadResponse(err)
                      }
                  }
                case _ =>
                  dispatcher.unsafeRunAndForget(
                    topic.publish1(
                      JSONRPCError(
                        id,
                        "got something that isn't a valid lnurl-pay response"
                      )
                    ) >> dummyTopic.close >> blocker.release
                  )
              }
            }
            case _ => {
              dispatcher.unsafeRunAndForget(
                topic.publish1(JSONRPCError(id, "invalid lnurl"))
                  >> dummyTopic.close >> blocker.release
              )
            }
          }
        }
        _ <- blocker.await
      } yield ()
    }
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
        LNParams.cm.payBag
          .listRecentPayments(params.count.getOrElse(5))
          .map(LNParams.cm.payBag.toPaymentInfo)
          .map(paymentAsJSON)
          .toList
          .asJson
      )
    ) >> IO.unit
  }

  def listTransactions(
      params: ListTransactions
  )(implicit id: String, topic: Topic[IO, JSONRPCMessage]): IO[Unit] = {
    topic.publish1(
      JSONRPCResponse(
        id,
        LNParams.chainWallets.wallets.flatMap { w =>
          w.getData.history.values.toList.flatten
            .sortBy(item => if (item.height <= 0) 9999999 else item.height)
            .map { item =>
              val tx = w.getData.transactions(item.txHash)
              val delta = w.getData.computeTransactionDelta(tx).get

              Json.obj(
                "txid" := tx.txid.toString,
                "block" := item.height,
                "sent" := delta.sent.toLong,
                "received" := delta.received.toLong,
                "fee" := delta.feeOpt.map(_.toLong).getOrElse(0L),
                "utxos_spent" := delta.spentUtxos.map(utxo =>
                  s"${utxo.item.txHash}:${utxo.item.txPos}"
                )
              )
            }
        }.asJson
      )
    ) >> IO.unit
  }

  def getAddress(params: GetAddress)(implicit
      id: String,
      topic: Topic[IO, JSONRPCMessage]
  ): IO[Unit] = {
    implicit val ec: scala.concurrent.ExecutionContext =
      scala.concurrent.ExecutionContext.global

    for {
      response <- IO.async_[JSONRPCMessage] { cb =>
        LNParams.chainWallets.wallets.head.getReceiveAddresses
          .onComplete {
            case Success(resp) => {
              val address = resp.keys
                .take(1)
                .map(resp.ewt.textAddress)
                .head

              cb(
                Right(
                  JSONRPCResponse(
                    id,
                    Json.obj("address" := address)
                  )
                )
              )
            }
            case Failure(err) => {
              cb(Right(JSONRPCError(id, err.toString())))
            }
          }
      }
      _ <- topic.publish1(response)
    } yield ()
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
            topic.publish1(
              JSONRPCResponse(id, Json.obj("accepted" := true))
            ) >> IO.unit
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

  def resizeHC(
      params: ResizeHostedChannel
  )(implicit id: String, topic: Topic[IO, JSONRPCMessage]): IO[Unit] = {
    (for {
      bytes <- ByteVector.fromHex(params.channelId)
      channelId <- Try(ByteVector32(bytes)).toOption
      chan <- LNParams.cm.all.get(channelId)
    } yield chan) match {
      case Some(chan: ChannelHosted) =>
        chan.proposeResize(Satoshi(params.satoshiDelta)) match {
          case Right(_) =>
            topic.publish1(
              JSONRPCResponse(id, Json.obj("proposed" := true))
            ) >> IO.unit
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

  private def channelAsJSON(chan: Channel): Json = {
    val commits = Channel.chanAndCommitsOpt(chan).map(_.commits)

    val specificNormalOrHostedStuff: (String, Json) = commits match {
      case Some(hc: HostedCommits) =>
        "hosted_channel" := Json.obj(
          "override_proposal" := hc.overrideProposal.map { op =>
            Json.obj(
              "their_balance" := op.localBalance.toLong,
              "our_balance" := (hc.lastCrossSignedState.initHostedChannel.channelCapacity.toLong - op.localBalance.toLong)
            )
          },
          "resize_proposal" := hc.resizeProposal.map(
            _.newCapacity.toLong * 1000
          )
        )
      case Some(nc: NormalCommits) =>
        "normal_channel" := "work in progress"
      case None =>
        "pending_channel" := "we don't have any commitments for this one yet"
      case _ =>
        "weird_channel" := "we don't really know what channel type this is"
    }

    val scid = commits match {
      case Some(hc: HostedCommits) => Some(hc.shortChannelId.toString)
      case _                       => None
    }

    Json.obj(
      "id" := commits.map(_.channelId.toHex),
      "short_channel_id" := scid,
      "label" := commits.flatMap(
        _.remoteInfo.alias.pipe(alias =>
          if (alias == "unnamed") None else Some(alias)
        )
      ),
      "peer" := Json.obj(
        "pubkey" := commits.map(_.remoteInfo.nodeId.toString),
        "our_pubkey" := commits.map(_.remoteInfo.nodeSpecificPubKey.toString),
        "addr" := commits.map(_.remoteInfo.address.toString)
      ),
      "capacity" := (chan.data.ourBalance.toLong + commits
        .map(_.availableForReceive.toLong)
        .getOrElse(0L)),
      "can_send" := commits.map(_.availableForSend.toLong).getOrElse(0L),
      "can_receive" := commits.map(_.availableForReceive.toLong).getOrElse(0L),
      "status" := (
        if (Channel.isOperationalAndOpen(chan)) { "open" }
        else if (Channel.isOperationalAndSleeping(chan)) { "sleeping" }
        else if (Channel.isWaiting(chan)) { "waiting" }
        else if (Channel.isErrored(chan)) { "error" }
        else "unknown"
      ),
      "policy" := commits
        .flatMap(_.updateOpt)
        .map(u =>
          Json.obj(
            "base_fee" := u.feeBaseMsat.toLong,
            "fee_per_millionth" := u.feeProportionalMillionths,
            "cltv_delta" := u.cltvExpiryDelta.toInt,
            "htlc_min" := u.htlcMinimumMsat.toLong,
            "htlc_max" := u.htlcMaximumMsat.toLong
          )
        ),
      "inflight" := commits.map(c =>
        Json.obj(
          "outgoing" := c.allOutgoing.size,
          "incoming" := c.crossSignedIncoming.size,
          "revealed" := c.revealedFulfills.size
        )
      ),
      specificNormalOrHostedStuff
    )
  }

  private def paymentAsJSON(info: PaymentInfo): Json = {
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

    Json.obj(
      "is_incoming" := info.isIncoming,
      "status" := status,
      "seen_at" := info.seenAt,
      "invoice" := info.prString,
      "preimage" := info.preimage.toHex,
      "msatoshi" := msatoshi,
      "updated_at" := info.updatedAt,
      "fee_msatoshi" := info.fee.toLong,
      "payment_hash" := info.paymentHash.toHex
    )
  }

  def onPaymentFailed(data: OutgoingPaymentSenderData): JSONRPCNotification =
    JSONRPCNotification(
      "payment_failed",
      Json.obj(
        "payment_hash" := data.cmd.fullTag.paymentHash.toHex,
        "parts" := data.parts.size,
        "routes" := data.inFlightParts.map(_.route.asString),
        "failure" := data.failures.map(_.asString)
      )
    )

  def onPaymentSucceeded(
      data: OutgoingPaymentSenderData
  ): JSONRPCNotification = {
    val info =
      LNParams.cm.payBag.getPaymentInfo(data.cmd.fullTag.paymentHash).get

    JSONRPCNotification(
      "payment_succeeded",
      Json.obj(
        "payment_hash" := info.paymentHash.toHex,
        "fee_msatoshi" := info.fee.toLong,
        "msatoshi" := info.sent.toLong,
        "preimage" := info.preimage.toHex
      )
    )
  }

  def onPaymentReceived(r: IncomingRevealed): JSONRPCNotification =
    JSONRPCNotification(
      "payment_received",
      LNParams.cm.payBag.getPaymentInfo(r.fullTag.paymentHash).toOption match {
        case Some(info) =>
          Json.obj(
            "preimage" := r.preimage.toHex,
            "msatoshi" := info.received.toLong,
            "payment_hash" := r.fullTag.paymentHash.toHex
          )
        case None =>
          Json.obj(
            "preimage" := r.preimage.toHex,
            "payment_hash" := r.fullTag.paymentHash.toHex
          )
      }
    )

  def onReady() = JSONRPCNotification("ready", Json.obj())
}
