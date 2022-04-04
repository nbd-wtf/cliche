package cliche

import java.io.{File}
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong
import scala.io.{Source}
import scala.annotation.nowarn
import scodec.bits.{HexStringSyntax}
import akka.actor
import com.softwaremill.quicklens._
import io.netty.util.internal.logging.{InternalLoggerFactory, JdkLoggerFactory}
import fr.acinq.eclair.{MilliSatoshi}
import fr.acinq.bitcoin.{MnemonicCode, Block, ByteVector32, Satoshi}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.wire.NodeAddress
import fr.acinq.eclair.blockchain.electrum.db.{
  CompleteChainWalletInfo,
  SigningWallet,
  WatchingWallet
}
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.transactions.RemoteFulfill
import fr.acinq.eclair.wire.{Init}
import fr.acinq.eclair.blockchain.{CurrentBlockCount, EclairWallet}
import fr.acinq.eclair.blockchain.electrum.{
  ElectrumWallet,
  ElectrumEclairWallet,
  CheckPoint,
  ElectrumChainSync,
  ElectrumClientPool,
  ElectrumWatcher,
  WalletParameters
}
import fr.acinq.eclair.blockchain.electrum.db.{
  CompleteChainWalletInfo,
  SigningWallet,
  WatchingWallet
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
import immortan.crypto.Tools
import immortan.crypto.Tools.{~, none, Any2Some}

import cliche.utils.{ConnectionProvider => ClicheConnectionProvider}
import cliche.{Commands, DB, Config}

object Main {
  def init(): Unit = {
    // prevent netty/electrumclient to flood us with logs
    InternalLoggerFactory.setDefaultFactory(JdkLoggerFactory.INSTANCE)

    println("# load objects")

    println("# initializing parameters")
    var currentChainNode: Option[InetSocketAddress] = None
    var totalBalance = Satoshi(0L)
    var txDescriptions: Map[ByteVector32, TxDescription] = Map.empty

    var lastTotalResyncStamp: Long = 0L
    var lastNormalResyncStamp: Long = 0L

    LNParams.connectionProvider = new ClicheConnectionProvider

    CommsTower.workers.values.map(_.pair).foreach(CommsTower.forget)

    LNParams.logBag = DB.logBag

    Config.network match {
      case "testnet" => LNParams.chainHash = Block.TestnetGenesisBlock.hash
      case "mainnet" => LNParams.chainHash = Block.LivenetGenesisBlock.hash
      case _ =>
        println(
          s"< impossible config.network option ${Config.network}"
        );
        sys.exit(1)
    }

    LNParams.routerConf =
      Router.RouterConf(initRouteMaxLength = 10, LNParams.maxCltvExpiryDelta)
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
    val electrumPool = LNParams.loggedActor(
      actor.Props(
        classOf[ElectrumClientPool],
        LNParams.blockCount,
        LNParams.chainHash,
        LNParams.ec
      ),
      "connection-pool"
    )
    val sync = LNParams.loggedActor(
      actor.Props(
        classOf[ElectrumChainSync],
        electrumPool,
        DB.extDataBag,
        LNParams.chainHash
      ),
      "chain-sync"
    )
    val watcher = LNParams.loggedActor(
      actor.Props(classOf[ElectrumWatcher], LNParams.blockCount, electrumPool),
      "channel-watcher"
    )
    val catcher =
      LNParams.loggedActor(
        actor.Props(new WalletEventsCatcher),
        "events-catcher"
      )

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
      (WalletExt(
        wallets = Nil,
        catcher,
        sync,
        electrumPool,
        watcher,
        params
      ) /: DB.chainWalletBag.listWallets) {
        case ext ~ CompleteChainWalletInfo(
              core: SigningWallet,
              persistentSigningWalletData,
              lastBalance,
              label,
              false
            ) =>
          val signingWallet =
            ext.makeSigningWalletParts(core, lastBalance, label)
          signingWallet.walletRef ! persistentSigningWalletData
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
          watchingWallet.walletRef ! persistentWatchingWalletData
          ext.copy(wallets = watchingWallet :: ext.wallets)
      }

    LNParams.chainWallets = if (walletExt.wallets.isEmpty) {
      val core =
        SigningWallet(walletType = EclairWallet.BIP84, isRemovable = false)
      val wallet =
        walletExt.makeSigningWalletParts(core, Satoshi(0L), "Bitcoin")
      walletExt.withFreshWallet(wallet)
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
    LNParams.chainWallets.catcher ! new WalletEventsListener {
      override def onChainTipKnown(blockCountEvent: CurrentBlockCount): Unit =
        LNParams.cm.initConnect

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

      override def onChainDisconnected: Unit = currentChainNode = None

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
    }

    pf.listeners += LNParams.cm.opm

    // get channels and still active FSMs up and running
    LNParams.cm.all = Channel.load(listeners = Set(LNParams.cm), DB.chanBag)

    // this inital notification will create all in/routed/out FSMs
    LNParams.cm.notifyResolvers

    println("# start electrum, fee rate, fiat rate listeners")
    LNParams.connectionProvider doWhenReady {
      electrumPool ! ElectrumClientPool.InitConnect
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

    println("# listening for outgoing payments")
    LNParams.cm.localPaymentListeners += new OutgoingPaymentListener {
      override def wholePaymentFailed(data: OutgoingPaymentSenderData): Unit =
        Commands.onPaymentFailed(data)
      override def gotFirstPreimage(
          data: OutgoingPaymentSenderData,
          fulfill: RemoteFulfill
      ): Unit = Commands.onPaymentSucceeded(data, fulfill)
    }

    println("# listening for incoming payments")
    ChannelMaster.inFinalized
      .collect { case revealed: IncomingRevealed => revealed }
      .subscribe(r => Commands.onPaymentReceived(r))
  }

  def main(args: Array[String]): Unit = {
    init()

    println("# waiting for commands")
    Commands.onReady()

    while (true) {
      val line = scala.io.StdIn.readLine().trim
      if (line.size > 0) {
        Commands.handle(line)
      }
    }
  }
}
