import java.net.InetSocketAddress
import scala.annotation.nowarn
import scala.concurrent.Future
import cats.Parallel.parTuple4
import cats.effect.{IO, IOApp}
import cats.effect.std.Dispatcher
import fs2.Stream
import fs2.concurrent.Topic
import com.softwaremill.quicklens._
import castor.Context.Simple.global
import fr.acinq.eclair.{MilliSatoshi}
import fr.acinq.bitcoin.{MnemonicCode, Block, ByteVector32, Satoshi}
import fr.acinq.eclair.blockchain.electrum.db.{
  CompleteChainWalletInfo,
  SigningWallet,
  WatchingWallet
}
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.transactions.RemoteFulfill
import fr.acinq.eclair.blockchain.{CurrentBlockCount, EclairWallet}
import fr.acinq.eclair.blockchain.electrum.{
  ElectrumWallet,
  ElectrumEclairWallet,
  ElectrumChainSync,
  ElectrumClientPool,
  ElectrumWatcher,
  WalletParameters
}
import fr.acinq.eclair.channel.{CMD_CHECK_FEERATE}
import immortan.{
  LNParams,
  ChanFundingTxDescription,
  Channel,
  ChannelMaster,
  CommsTower,
  LightningNodeKeys,
  PathFinder,
  RemoteNodeInfo,
  SyncParams,
  TxDescription,
  WalletExt,
  WalletSecret
}
import immortan.fsm.{
  OutgoingPaymentListener,
  OutgoingPaymentSenderData,
  IncomingRevealed
}
import immortan.utils.{
  Rx,
  FeeRates,
  FeeRatesInfo,
  FeeRatesListener,
  FiatRates,
  FiatRatesInfo,
  FiatRatesListener,
  WalletEventsCatcher,
  WalletEventsListener
}
import immortan.crypto.Tools.{~, none, Any2Some}

import utils.RequestsConnectionProvider
import scala.concurrent.duration.FiniteDuration

object Main extends IOApp.Simple {
  def init(): Unit = {
    // print the configs we read
    Config.print()

    println("# initializing parameters")
    var currentChainNode: Option[InetSocketAddress] = None
    var totalBalance = Satoshi(0L)
    var txDescriptions: Map[ByteVector32, TxDescription] = Map.empty

    var lastTotalResyncStamp: Long = 0L
    var lastNormalResyncStamp: Long = 0L

    LNParams.connectionProvider = new RequestsConnectionProvider
    CommsTower.workers.values.map(_.pair).foreach(CommsTower.forget)
    LNParams.logBag = DB.logBag

    Config.network match {
      case "mainnet" => LNParams.chainHash = Block.LivenetGenesisBlock.hash
      case "testnet" => LNParams.chainHash = Block.TestnetGenesisBlock.hash
      case "regtest" => LNParams.chainHash = Block.RegtestGenesisBlock.hash
      case "signet"  => LNParams.chainHash = Block.SegnetGenesisBlock.hash
      case _ =>
        println(
          s"< impossible config.network option ${Config.network}"
        );
        sys.exit(1)
    }

    LNParams.routerConf =
      Router.RouterConf(initRouteMaxLength = 20, LNParams.maxCltvExpiryDelta)
    LNParams.ourInit = LNParams.createInit
    LNParams.syncParams = new SyncParams {
      override val minPHCCapacity = MilliSatoshi(10000000L)
      override val minNormalChansForPHC = 1
      override val maxPHCPerNode = 50
      override val minCapacity = MilliSatoshi(10000000L)
      override val maxNodesToSyncFrom = 3
    }

    val walletSeed =
      MnemonicCode.toSeed(Config.seed, passphrase = new String)
    val keys = LightningNodeKeys.makeFromSeed(seed = walletSeed.toArray)
    val secret = WalletSecret(keys, Config.seed, walletSeed)
    DB.extDataBag.putSecret(secret)
    LNParams.secret = secret

    DB.extDataBag.db txWrap {
      LNParams.feeRates = new FeeRates(DB.extDataBag)
      LNParams.fiatRates = new FiatRates(DB.extDataBag)
    }

    println("# setting up pathfinder")
    val pf = new PathFinder(DB.normalBag, DB.hostedBag) {
      override def getLastTotalResyncStamp: Long = lastTotalResyncStamp
      override def getLastNormalResyncStamp: Long = lastNormalResyncStamp
      override def updateLastTotalResyncStamp(stamp: Long): Unit =
        lastTotalResyncStamp = stamp
      override def updateLastNormalResyncStamp(stamp: Long): Unit =
        lastNormalResyncStamp = stamp
      override def getExtraNodes: Set[RemoteNodeInfo] = LNParams.cm.all.values
        .flatMap(Channel.chanAndCommitsOpt)
        .map(_.commits.remoteInfo)
        .toSet
      override def getPHCExtraNodes: Set[RemoteNodeInfo] =
        LNParams.cm.allHostedCommits.map(_.remoteInfo).toSet
    }

    println("# instantiating channel master")
    LNParams.cm = new ChannelMaster(DB.payBag, DB.chanBag, DB.extDataBag, pf)

    println("# instantiating electrum actors")
    val pool = new ElectrumClientPool(
      LNParams.blockCount,
      LNParams.chainHash
    )
    val sync = new ElectrumChainSync(
      pool,
      DB.extDataBag,
      LNParams.chainHash
    )
    val watcher = new ElectrumWatcher(LNParams.blockCount, pool)
    val catcher = new WalletEventsCatcher()

    println("# loading onchain wallets")
    val params =
      WalletParameters(
        DB.extDataBag,
        DB.chainWalletBag,
        DB.txDataBag,
        dustLimit = Satoshi(546L)
      )

    @nowarn
    val walletExt: WalletExt =
      DB.chainWalletBag.listWallets
        .foldLeft(
          WalletExt(
            wallets = Nil,
            catcher,
            sync,
            pool,
            watcher,
            params
          )
        ) {
          case ext ~ CompleteChainWalletInfo(
                core: SigningWallet,
                persistentSigningWalletData,
                lastBalance,
                label,
                false
              ) =>
            val signingWallet =
              ext.makeSigningWalletParts(core, lastBalance, label)
            signingWallet.wallet.send(persistentSigningWalletData)
            ext.copy(wallets = signingWallet :: ext.wallets)

          case ext ~ CompleteChainWalletInfo(
                core: WatchingWallet,
                persistentWatchingWalletData,
                lastBalance,
                label,
                false
              ) =>
            val watchingWallet =
              ext.makeWatchingWallet84Parts(core, lastBalance, label)
            watchingWallet.wallet.send(persistentWatchingWalletData)
            ext.copy(wallets = watchingWallet :: ext.wallets)
        }

    LNParams.chainWallets = if (walletExt.wallets.isEmpty) {
      walletExt.withFreshWallet(
        walletExt.makeSigningWalletParts(
          SigningWallet(walletType = EclairWallet.BIP84, isRemovable = false),
          Satoshi(0L),
          "Bitcoin"
        )
      )
    } else walletExt

    LNParams.feeRates.listeners += new FeeRatesListener {
      def onFeeRates(newRatesInfo: FeeRatesInfo): Unit = {
        // we may get fresh feerates after channels become OPEN
        LNParams.cm.all.values.foreach(_ process CMD_CHECK_FEERATE)
        DB.extDataBag.putFeeRatesInfo(newRatesInfo)
      }
    }

    LNParams.fiatRates.listeners += new FiatRatesListener {
      def onFiatRates(newRatesInfo: FiatRatesInfo): Unit =
        DB.extDataBag.putFiatRatesInfo(newRatesInfo)
    }

    // guaranteed to fire (and update chainWallets) first
    LNParams.chainWallets.catcher.add(new WalletEventsListener {
      override def onChainTipKnown(blockCountEvent: CurrentBlockCount): Unit =
        LNParams.cm.initConnect()

      override def onWalletReady(
          blockCountEvent: ElectrumWallet.WalletReady
      ): Unit = {
        LNParams.synchronized {
          val sameXPub: ElectrumEclairWallet => Boolean =
            _.ewt.xPub == blockCountEvent.xPub

          LNParams.chainWallets = LNParams.chainWallets.modify(
            _.wallets.eachWhere(sameXPub).info
          ) using { info =>
            info.copy(
              lastBalance = blockCountEvent.balance,
              isCoinControlOn = blockCountEvent.excludedOutPoints.nonEmpty
            )
          }
        }
      }

      override def onChainMasterSelected(addr: InetSocketAddress): Unit =
        currentChainNode = addr.asSome

      override def onChainDisconnected(): Unit = currentChainNode = None

      override def onTransactionReceived(
          txEvent: ElectrumWallet.TransactionReceived
      ): Unit = {
        def addChainTx(
            received: Satoshi,
            sent: Satoshi,
            description: TxDescription,
            isIncoming: Long
        ): Unit = description match {
          case _: ChanFundingTxDescription =>
            doAddChainTx(
              received,
              sent,
              description,
              isIncoming,
              MilliSatoshi((totalBalance - sent).toLong * 1000)
            )
          case _ =>
            doAddChainTx(
              received,
              sent,
              description,
              isIncoming,
              MilliSatoshi(totalBalance.toLong * 1000)
            )
        }

        def doAddChainTx(
            received: Satoshi,
            sent: Satoshi,
            description: TxDescription,
            isIncoming: Long,
            totalBalance: MilliSatoshi
        ): Unit = DB.txDataBag.db txWrap {
          DB.txDataBag.addTx(
            txEvent.tx,
            txEvent.depth,
            received,
            sent,
            txEvent.feeOpt,
            txEvent.xPub,
            description,
            isIncoming,
            balanceSnap = totalBalance,
            LNParams.fiatRates.info.rates,
            txEvent.stamp
          )
          DB.txDataBag.addSearchableTransaction(
            description.queryText(txEvent.tx.txid),
            txEvent.tx.txid
          )
        }

        val fee = txEvent.feeOpt.getOrElse(Satoshi(0L))
        val defDescription =
          TxDescription.define(LNParams.cm.all.values, Nil, txEvent.tx)
        val sentTxDescription =
          txDescriptions.getOrElse(txEvent.tx.txid, default = defDescription)
        if (txEvent.sent == txEvent.received + fee)
          addChainTx(
            received = Satoshi(0L),
            sent = fee,
            sentTxDescription,
            isIncoming = 0L
          )
        else if (txEvent.sent > txEvent.received)
          addChainTx(
            received = Satoshi(0L),
            txEvent.sent - txEvent.received - fee,
            sentTxDescription,
            isIncoming = 0L
          )
        else
          addChainTx(
            txEvent.received - txEvent.sent,
            sent = Satoshi(0L),
            TxDescription
              .define(
                LNParams.cm.all.values,
                txEvent.walletAddreses,
                txEvent.tx
              ),
            isIncoming = 1L
          )
      }
    })

    pf.listeners += LNParams.cm.opm

    // get channels and still active FSMs up and running
    LNParams.cm.all = Channel.load(listeners = Set(LNParams.cm), DB.chanBag)

    // this inital notification will create all in/routed/out FSMs
    LNParams.cm.notifyResolvers()

    println("# start electrum, fee rate, fiat rate listeners")
    LNParams.connectionProvider doWhenReady {
      pool.initConnect()

      // only schedule periodic resync if Lightning channels are being present
      if (LNParams.cm.all.nonEmpty) pf process PathFinder.CMDStartPeriodicResync

      val feeratePeriodHours = 6
      val rateRetry = Rx.retry(
        Rx.ioQueue.map(_ => LNParams.feeRates.reloadData),
        Rx.incSec,
        3 to 18 by 3
      )
      val rateRepeat = Rx.repeat(
        rateRetry,
        Rx.incHour,
        feeratePeriodHours to Int.MaxValue by feeratePeriodHours
      )
      val feerateObs = Rx.initDelay(
        rateRepeat,
        LNParams.feeRates.info.stamp,
        feeratePeriodHours * 3600 * 1000L
      )
      feerateObs.foreach(LNParams.feeRates.updateInfo, none)

      val fiatPeriodSecs = 60 * 30
      val fiatRetry = Rx.retry(
        Rx.ioQueue.map(_ => LNParams.fiatRates.reloadData),
        Rx.incSec,
        3 to 18 by 3
      )
      val fiatRepeat = Rx.repeat(
        fiatRetry,
        Rx.incSec,
        fiatPeriodSecs to Int.MaxValue by fiatPeriodSecs
      )
      val fiatObs = Rx.initDelay(
        fiatRepeat,
        LNParams.fiatRates.info.stamp,
        fiatPeriodSecs * 1000L
      )
      fiatObs.foreach(LNParams.fiatRates.updateInfo, none)
    }
    println(s"# is operational: ${LNParams.isOperational}")
  }

  override final val run: IO[Unit] = {
    init()

    // API outgoing events
    Topic[IO, JSONRPCMessage].flatMap { implicit topic =>
      val d = Dispatcher[IO].use { dispatcher =>
        for {
          _ <- IO.delay {
            dispatcher.unsafeRunAndForget(
              topic.publish1(Commands.onReady()) >> IO.unit
            )
            LNParams.cm.localPaymentListeners += new OutgoingPaymentListener {
              override def wholePaymentFailed(
                  data: OutgoingPaymentSenderData
              ): Unit =
                dispatcher.unsafeRunAndForget(
                  topic.publish1(Commands.onPaymentFailed(data)) >> IO.unit
                )

              override def gotFirstPreimage(
                  data: OutgoingPaymentSenderData,
                  fulfill: RemoteFulfill
              ): Unit =
                dispatcher.unsafeRunAndForget(
                  topic.publish1(
                    Commands.onPaymentSucceeded(data, fulfill)
                  ) >> IO.unit
                )
            }

            ChannelMaster.inFinalized
              .collect { case revealed: IncomingRevealed => revealed }
              .subscribe(r =>
                dispatcher.unsafeRunAndForget(
                  topic.publish1(Commands.onPaymentReceived(r)) >> IO.unit
                )
              )
          }
          _ <- IO.never[Unit]
        } yield ()
      }

      parTuple4[IO, Unit, Unit, Unit, Unit](
        d,
        new ServerApp().stream.compile.drain,
        new StdinApp().run(),
        new StdoutApp().run()
      ).void
    }
  }
}
