package cliche

import java.io.{File, FileInputStream}
import java.net.InetSocketAddress
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.SSL
import fr.acinq.eclair.blockchain.electrum.ElectrumClientPool.ElectrumServerAddress
import fr.acinq.eclair.channel.CMD_CHECK_FEERATE
import fr.acinq.eclair.wire.CommonCodecs.nodeaddress
import fr.acinq.bitcoin.{Block, ByteVector32, Satoshi, SatoshiLong}
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.blockchain.{CurrentBlockCount, EclairWallet}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.SSL
import fr.acinq.eclair.blockchain.electrum.ElectrumClientPool.ElectrumServerAddress
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet.{
  TransactionReceived,
  WalletReady
}
import fr.acinq.eclair.blockchain.electrum.db.{
  CompleteChainWalletInfo,
  SigningWallet,
  WatchingWallet
}
import fr.acinq.eclair.blockchain.electrum.{
  CheckPoint,
  ElectrumChainSync,
  ElectrumClientPool,
  ElectrumWatcher,
  WalletParameters
}
import fr.acinq.eclair.channel.{
  CMD_CHECK_FEERATE,
  Commitments,
  PersistentChannelData
}
import fr.acinq.eclair.router.Router.RouterConf
import fr.acinq.eclair.wire.CommonCodecs.nodeaddress
import fr.acinq.eclair.wire.NodeAddress
import com.btcontract.wallet.sqlite.{
  DBInterfaceSQLiteAndroidEssential,
  DBInterfaceSQLiteAndroidGraph,
  DBInterfaceSQLiteAndroidMisc,
  SQLiteDataExtended
}
import fr.acinq.bitcoin.ByteVector32.fromValidHex
import immortan.{
  ChanFundingTxDescription,
  Channel,
  ChannelMaster,
  CommsTower,
  ConnectionListener,
  KeyPairAndPubKey,
  LNParams,
  LightningNodeKeys,
  PathFinder,
  RemoteNodeInfo,
  SyncParams,
  TxDescription,
  WalletExt,
  WalletSecret
}
import immortan.fsm.{OutgoingPaymentListener, OutgoingPaymentSenderData}
import immortan.crypto.Tools.{Any2Some, none, runAnd}
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
  FeeRates,
  FeeRatesInfo,
  FeeRatesListener,
  FiatRates,
  FiatRatesInfo,
  FiatRatesListener,
  Rx,
  WalletEventsCatcher,
  WalletEventsListener
}
import scodec.bits.BitVector

import java.util.concurrent.atomic.AtomicLong
import akka.actor.{Props}
import com.google.common.io.ByteStreams
import com.softwaremill.quicklens._
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin._
import fr.acinq.eclair.Features._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.EclairWallet
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet.WalletReady
import fr.acinq.eclair.blockchain.electrum._
import fr.acinq.eclair.blockchain.electrum.db.{
  CompleteChainWalletInfo,
  SigningWallet,
  WatchingWallet
}
import fr.acinq.eclair.channel.{ChannelKeys, LocalParams, PersistentChannelData}
import fr.acinq.eclair.router.ChannelUpdateExt
import fr.acinq.eclair.router.Router.{PublicChannel, RouterConf}
import fr.acinq.eclair.transactions.{DirectedHtlc, RemoteFulfill}
import fr.acinq.eclair.wire._
import immortan.SyncMaster.ShortChanIdSet
import immortan.crypto.{CanBeShutDown, Tools}
import immortan.crypto.Noise.KeyPair
import immortan.crypto.Tools._
import immortan.sqlite._
import immortan.utils._
import immortan.wire.ExtCodecs
import scodec.bits.{ByteVector, HexStringSyntax}

// local
import cliche.utils.SQLiteUtils
import cliche.{Commands}

object Main extends App {
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
  var totalBalance = 0 sat
  var txDescriptions: Map[ByteVector32, TxDescription] = Map.empty

  CommsTower.workers.values.map(_.pair).foreach(CommsTower.forget)

  this.makeAlive()
  println("isAlive %b".format(isAlive))
  val secret = makeSecret()
  this.makeOperational(secret)
  println("LNParams.isOperational %b".format(LNParams.isOperational))
  LNParams.system.log.info("Test IMMORTAN LOG output")

  def isAlive: Boolean =
    null != txDataBag && null != lnUrlPayBag && null != chainWalletBag && null != extDataBag

  def makeAlive(): Unit = {
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
        println(s"Impossible config.network option ${config.network}");
        sys.exit(1)
    }

    LNParams.routerConf = RouterConf(initRouteMaxLength = 6)
    LNParams.ourInit = LNParams.createInit
    LNParams.syncParams = new SyncParams
  }

  final val GRAPH_NAME = "graph.snapshot"
  final val GRAPH_EXTENSION = ".zlib"

  def getNetwork(chainHash: ByteVector32): String = chainHash match {
    case Block.LivenetGenesisBlock.hash => "mainnet"
    case Block.TestnetGenesisBlock.hash => "testnet"
    case _                              => "unknown"
  }

  def getGraphResourceName(chainHash: ByteVector32): String =
    s"$GRAPH_NAME-${this.getNetwork(chainHash)}$GRAPH_EXTENSION"

  def makeSecret(): WalletSecret = {
    val walletSeed =
      MnemonicCode.toSeed(config.mnemonics, passphrase = new String)
    val keys = LightningNodeKeys.makeFromSeed(seed = walletSeed.toArray)
    val secret = WalletSecret(keys, config.mnemonics, walletSeed)

    try {
      // Implant graph into db file from resources
      val snapshotName = getGraphResourceName(LNParams.chainHash)
      val compressedPlainBytes = ByteStreams.toByteArray(
        new FileInputStream(new File(s"${config.assetsDir}/$snapshotName"))
      )
      val plainBytes = ExtCodecs.compressedByteVecCodec.decode(
        BitVector view compressedPlainBytes
      )
//      LocalBackup.copyPlainDataToDbLocation(host, WalletApp.dbFileNameGraph, plainBytes.require.value)
    } catch none

    extDataBag.putSecret(secret)
    secret
  }

  var lastTotalResyncStamp: Long = 0L
  var lastNormalResyncStamp: Long = 0L

  def makeOperational(secret: WalletSecret): Unit = {
    require(
      isAlive,
      "Application is not alive, hence can not become operational"
    )
    val essentialInterface = new DBInterfaceSQLiteAndroidEssential(sqlitedb)
    val graphInterface = new DBInterfaceSQLiteAndroidGraph(sqlitedb)
    LNParams.secret = secret

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
        override def put(data: PersistentChannelData): PersistentChannelData = {
//        backupSaveWorker.replaceWork(true)
          super.put(data)
        }
      }

    extDataBag.db txWrap {
      LNParams.feeRates = new FeeRates(extDataBag)
      LNParams.fiatRates = new FiatRates(extDataBag)
    }

    val pf = new PathFinder(normalBag, hostedBag) {
      override def getLastTotalResyncStamp: Long =
        lastTotalResyncStamp // app.prefs.getLong(LAST_TOTAL_GOSSIP_SYNC, 0L)
      override def getLastNormalResyncStamp: Long =
        lastNormalResyncStamp // app.prefs.getLong(LAST_NORMAL_GOSSIP_SYNC, 0L)
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

    ElectrumClientPool.loadFromChainHash = {
      case Block.LivenetGenesisBlock.hash =>
        ElectrumClientPool.readServerAddresses(
          new FileInputStream(
            new File(s"${config.assetsDir}/servers_mainnet.json")
          ),
          sslEnabled = true
        )
      case Block.TestnetGenesisBlock.hash =>
        ElectrumClientPool.readServerAddresses(
          new FileInputStream(
            new File(s"${config.assetsDir}/servers_testnet.json")
          ),
          sslEnabled = true
        )
      case _ => throw new RuntimeException
    }

    CheckPoint.loadFromChainHash = {
      case Block.LivenetGenesisBlock.hash =>
        CheckPoint.load(
          new FileInputStream(
            new File(s"${config.assetsDir}/checkpoints_mainnet.json")
          )
        )
      case Block.TestnetGenesisBlock.hash =>
        CheckPoint.load(
          new FileInputStream(
            new File(s"${config.assetsDir}/checkpoints_testnet.json")
          )
        )
      case _ => throw new RuntimeException
    }

    LNParams.cm = new ChannelMaster(payBag, chanBag, extDataBag, pf) {
      // There will be a disconnect if VPN (Orbot) suddenly stops working, we then clear everything and restart an app
      override def initConnect: Unit = super.initConnect
    }

    val params =
      WalletParameters(extDataBag, chainWalletBag, dustLimit = 546L.sat)
    val pool = LNParams.loggedActor(
      Props(
        classOf[ElectrumClientPool],
        LNParams.blockCount,
        LNParams.chainHash,
        LNParams.ec
      ),
      "connection-pool"
    )
    val sync = LNParams.loggedActor(
      Props(
        classOf[ElectrumChainSync],
        pool,
        params.headerDb,
        LNParams.chainHash
      ),
      "chain-sync"
    )
    val watcher = LNParams.loggedActor(
      Props(classOf[ElectrumWatcher], LNParams.blockCount, pool),
      "channel-watcher"
    )
    val catcher =
      LNParams.loggedActor(Props(new WalletEventsCatcher), "events-catcher")

    val walletExt: WalletExt =
      (WalletExt(
        wallets = Nil,
        catcher,
        sync,
        pool,
        watcher,
        params
      ) /: chainWalletBag.listWallets) {
        case walletExt1 ~ CompleteChainWalletInfo(
              core: SigningWallet,
              persistentSigningData,
              lastBalance,
              label
            ) =>
          val signingWallet: ElectrumEclairWallet =
            walletExt1.makeSigningWalletParts(core, lastBalance, label)
          signingWallet.walletRef ! persistentSigningData
          walletExt1 + signingWallet

        case walletExt1 ~ CompleteChainWalletInfo(
              core: WatchingWallet,
              persistentWatchingData,
              lastBalance,
              label
            ) =>
          val watchingWallet =
            walletExt1.makeWatchingWalletParts(core, lastBalance, label)
          watchingWallet.walletRef ! persistentWatchingData
          walletExt1 + watchingWallet
      }

    LNParams.chainWallets = if (walletExt.wallets.isEmpty) {
      val params = SigningWallet(EclairWallet.BIP84, isRemovable = false)
      val label = "Bitcoin"
      println(params)
      walletExt.withNewSigning(params, label)
    } else walletExt

    LNParams.feeRates.listeners += new FeeRatesListener {
      def onFeeRates(newRatesInfo: FeeRatesInfo): Unit = {
        // We may get fresh feerates after channels become OPEN
        LNParams.cm.all.values.foreach(_ process CMD_CHECK_FEERATE)
        extDataBag.putFeeRatesInfo(newRatesInfo)
      }
    }

    LNParams.fiatRates.listeners += new FiatRatesListener {
      def onFiatRates(newRatesInfo: FiatRatesInfo): Unit =
        extDataBag.putFiatRatesInfo(newRatesInfo)
    }

    // Guaranteed to fire (and update chainWallets) first
    LNParams.chainWallets.catcher ! new WalletEventsListener {
      override def onChainTipKnown(event: CurrentBlockCount): Unit =
        LNParams.cm.initConnect

      override def onWalletReady(event: WalletReady): Unit =
        LNParams.updateChainWallet(
          LNParams.chainWallets withBalanceUpdated event
        )

      override def onChainMasterSelected(event: InetSocketAddress): Unit =
        currentChainNode = event.asSome

      override def onChainDisconnected: Unit = currentChainNode = None

      override def onTransactionReceived(event: TransactionReceived): Unit = {
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
              MilliSatoshi.toMilliSatoshi(totalBalance - sent)
            )
          case _ =>
            doAddChainTx(
              received,
              sent,
              description,
              isIncoming,
              MilliSatoshi.toMilliSatoshi(totalBalance)
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
            event.tx,
            event.depth,
            received,
            sent,
            event.feeOpt,
            event.xPub,
            description,
            isIncoming,
            balanceSnap = totalBalance,
            LNParams.fiatRates.info.rates
          )
          txDataBag.addSearchableTransaction(
            description.queryText(event.tx.txid),
            event.tx.txid
          )
        }

        val fee = event.feeOpt.getOrElse(0L.sat)
        val defDescription =
          TxDescription.define(LNParams.cm.all.values, Nil, event.tx)
        val sentTxDescription =
          txDescriptions.getOrElse(event.tx.txid, default = defDescription)
        if (event.sent == event.received + fee)
          addChainTx(
            received = 0L.sat,
            sent = fee,
            sentTxDescription,
            isIncoming = 0L
          )
        else if (event.sent > event.received)
          addChainTx(
            received = 0L.sat,
            event.sent - event.received - fee,
            sentTxDescription,
            isIncoming = 0L
          )
        else
          addChainTx(
            event.received - event.sent,
            sent = 0L.sat,
            TxDescription
              .define(LNParams.cm.all.values, event.walletAddreses, event.tx),
            isIncoming = 1L
          )
      }
    }

    pf.listeners += LNParams.cm.opm
    // Get channels and still active FSMs up and running
    LNParams.cm.all = Channel.load(listeners = Set(LNParams.cm), chanBag)
    // Only schedule periodic resync if Lightning channels are being present
    if (LNParams.cm.all.nonEmpty) pf process PathFinder.CMDStartPeriodicResync
    // This inital notification will create all in/routed/out FSMs
    LNParams.cm.notifyResolvers
  }

  object NetworkListener extends ConnectionListener {
    override def onOperational(
        worker: CommsTower.Worker,
        theirInit: Init
    ): Unit = {
      println(
        s"Connected to remote nodeId=${worker.info.nodeId} as local nodeId=${worker.pair.keyPair.pub}"
      )
    }
    override def onDisconnect(worker: CommsTower.Worker): Unit = {
      println(
        s"Disconnected from remote nodeId=${worker.info.nodeId} as local nodeId=${worker.pair.keyPair.pub}"
      )
    }
  }

  // listen for outgoing payments
  LNParams.cm.localPaymentListeners += new OutgoingPaymentListener {
    override def wholePaymentFailed(data: OutgoingPaymentSenderData): Unit = {
      println("payment failed: ", data.cmd.fullTag.paymentHash)
    }

    override def gotFirstPreimage(
        data: OutgoingPaymentSenderData,
        fulfill: RemoteFulfill
    ): Unit = {
      println(
        "payment success: ",
        fulfill.ourAdd.paymentHash,
        " ~ ",
        fulfill.theirPreimage
      )
    }
  }

  val remotePeer: RemoteNodeInfo = RemoteNodeInfo(
    PublicKey(
      hex"03ee58475055820fbfa52e356a8920f62f8316129c39369dbdde3e5d0198a9e315"
    ),
    NodeAddress.unresolved(9734, host = 107, 189, 30, 195),
    "@lntxbot"
  )

  val ourLocalNodeId =
    Tools.randomKeyPair

  CommsTower.listen(
    Set(NetworkListener),
    KeyPairAndPubKey(ourLocalNodeId, remotePeer.nodeId),
    remotePeer
  )

  while (true) {
    Commands.handle(Commands.decode(scala.io.StdIn.readLine()))
  }
}
