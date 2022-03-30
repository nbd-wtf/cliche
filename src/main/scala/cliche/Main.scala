package cliche

import java.io.{File}
import java.net.InetSocketAddress
import scala.io.{Source}
import scala.annotation.nowarn
import scodec.bits.{HexStringSyntax}
import java.util.concurrent.atomic.AtomicLong
import akka.actor
import com.softwaremill.quicklens._
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
import fr.acinq.eclair.transactions.{DirectedHtlc, RemoteFulfill}
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
import fr.acinq.eclair.channel.{CMD_CHECK_FEERATE, PersistentChannelData}
import immortan.{
  ChanFundingTxDescription,
  Channel,
  ChannelMaster,
  CommsTower,
  LNParams,
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
import immortan.sqlite.{
  DBInterfaceSQLiteGeneral,
  HostedChannelAnnouncementTable,
  HostedChannelUpdateTable,
  HostedExcludedChannelTable,
  NormalChannelAnnouncementTable,
  NormalChannelUpdateTable,
  NormalExcludedChannelTable,
  SQLiteChainWallet,
  SQLiteChannel,
  SQLiteLNUrlPay,
  SQLiteLog,
  SQLiteNetwork,
  SQLitePayment,
  SQLiteTx
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
import com.btcontract.wallet.sqlite.{
  DBInterfaceSQLiteAndroidEssential,
  DBInterfaceSQLiteAndroidGraph,
  DBInterfaceSQLiteAndroidMisc,
  SQLiteDataExtended
}

import cliche.utils.{
  SQLiteUtils,
  ConnectionProvider => ClicheConnectionProvider
}
import cliche.{Commands}

object Main {
  println("# initial parameters")
  var userdir: File = new File("./data")
  var config: Config = new Config(userdir)

  val sqlitedb = SQLiteUtils.getConnection
  val dbinterface = DBInterfaceSQLiteGeneral(sqlitedb)
  val miscInterface = new DBInterfaceSQLiteAndroidMisc(sqlitedb)
  var txDataBag: SQLiteTx = null
  var lnUrlPayBag: SQLiteLNUrlPay = null
  var chainWalletBag: SQLiteChainWallet = null
  var extDataBag: SQLiteDataExtended = null
  var currentChainNode: Option[InetSocketAddress] = None
  var totalBalance = Satoshi(0L)
  var txDescriptions: Map[ByteVector32, TxDescription] = Map.empty

  var lastTotalResyncStamp: Long = 0L
  var lastNormalResyncStamp: Long = 0L

  LNParams.connectionProvider = new ClicheConnectionProvider

  CommsTower.workers.values.map(_.pair).foreach(CommsTower.forget)

  dbinterface txWrap {
    txDataBag = new SQLiteTx(dbinterface)
    lnUrlPayBag = new SQLiteLNUrlPay(dbinterface)
    chainWalletBag = new SQLiteChainWallet(dbinterface)
    extDataBag = new SQLiteDataExtended(dbinterface)
  }

  LNParams.logBag = new SQLiteLog(dbinterface)

  config.network match {
    case "testnet" => LNParams.chainHash = Block.TestnetGenesisBlock.hash
    case "mainnet" => LNParams.chainHash = Block.LivenetGenesisBlock.hash
    case _ =>
      println(
        s"< impossible config.network option ${config.network}"
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
    MnemonicCode.toSeed(config.seed, passphrase = new String)
  val keys = LightningNodeKeys.makeFromSeed(seed = walletSeed.toArray)
  val secret = WalletSecret(keys, config.seed, walletSeed)
  extDataBag.putSecret(secret)
  LNParams.secret = secret

  println("# setting up database")
  val essentialInterface = new DBInterfaceSQLiteAndroidEssential(sqlitedb)
  val graphInterface = new DBInterfaceSQLiteAndroidGraph(sqlitedb)

  val normalBag = new SQLiteNetwork(
    dbinterface,
    NormalChannelUpdateTable,
    NormalChannelAnnouncementTable,
    NormalExcludedChannelTable
  )
  val hostedBag = new SQLiteNetwork(
    dbinterface,
    HostedChannelUpdateTable,
    HostedChannelAnnouncementTable,
    HostedExcludedChannelTable
  )
  val payBag = new SQLitePayment(extDataBag.db, preimageDb = dbinterface)

  val chanBag =
    new SQLiteChannel(dbinterface, channelTxFeesDb = extDataBag.db) {
      override def put(
          data: PersistentChannelData
      ): PersistentChannelData = {
        super.put(data)
      }
    }

  extDataBag.db txWrap {
    LNParams.feeRates = new FeeRates(extDataBag)
    LNParams.fiatRates = new FiatRates(extDataBag)
  }

  println("# setting up pathfinder")
  val pf = new PathFinder(normalBag, hostedBag) {
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
  LNParams.cm = new ChannelMaster(payBag, chanBag, extDataBag, pf)

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
      extDataBag,
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
      extDataBag,
      chainWalletBag,
      txDataBag,
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
    ) /: chainWalletBag.listWallets) {
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
      extDataBag.putFeeRatesInfo(newRatesInfo)
    }
  }

  LNParams.fiatRates.listeners += new FiatRatesListener {
    def onFiatRates(newRatesInfo: FiatRatesInfo): Unit =
      extDataBag.putFiatRatesInfo(newRatesInfo)
  }

  // guaranteed to fire (and update chainWallets) first
  LNParams.chainWallets.catcher ! new WalletEventsListener {
    override def onChainTipKnown(blockCountEvent: CurrentBlockCount): Unit =
      LNParams.cm.initConnect

    override def onWalletReady(
        blockCountEvent: ElectrumWallet.WalletReady
    ): Unit =
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
      ): Unit = txDataBag.db txWrap {
        txDataBag.addTx(
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
        txDataBag.addSearchableTransaction(
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
  LNParams.cm.all = Channel.load(listeners = Set(LNParams.cm), chanBag)

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
    override def wholePaymentFailed(data: OutgoingPaymentSenderData): Unit = {
      println(
        s">> payment failed: ${data.cmd.fullTag.paymentHash} parts=${data.parts.size} failure=${data.failuresAsString}"
      )
    }

    override def gotFirstPreimage(
        data: OutgoingPaymentSenderData,
        fulfill: RemoteFulfill
    ): Unit = {
      println(
        s">> payment success: ${fulfill.ourAdd.paymentHash} preimage=${fulfill.theirPreimage} fee=${data.usedFee}"
      )
    }
  }

  println("# listening for incoming payments")
  ChannelMaster.inFinalized
    .collect { case revealed: IncomingRevealed => revealed }
    .subscribe(r => {
      println(s">> received payment: ${r.fullTag.paymentHash.toHex}")
    })

  def main(args: Array[String]): Unit = {
    while (true) {
      Commands.handle(Commands.decode(scala.io.StdIn.readLine()))
    }
  }
}
