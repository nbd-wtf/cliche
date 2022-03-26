package cliche

import java.io.{File}
import java.net.InetSocketAddress
import scala.io.{Source}
import scala.annotation.nowarn
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
  ClearnetConnectionProvider,
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

object Main {
  @nowarn
  def main(args: Array[String]): Unit = {
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
    var totalBalance = 0 sat
    var txDescriptions: Map[ByteVector32, TxDescription] = Map.empty

    var lastTotalResyncStamp: Long = 0L
    var lastNormalResyncStamp: Long = 0L

    LNParams.connectionProvider = new ClearnetConnectionProvider
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
        println(s"Impossible config.network option ${config.network}");
        sys.exit(1)
    }

    LNParams.routerConf =
      RouterConf(initRouteMaxLength = 10, LNParams.maxCltvExpiryDelta)
    LNParams.ourInit = LNParams.createInit
    LNParams.syncParams = new SyncParams

    val walletSeed =
      MnemonicCode.toSeed(config.mnemonics, passphrase = new String)
    val keys = LightningNodeKeys.makeFromSeed(seed = walletSeed.toArray)
    val secret = WalletSecret(keys, config.mnemonics, walletSeed)
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

    println("# setting up electrum")
    ElectrumClientPool.loadFromChainHash = {
      case Block.LivenetGenesisBlock.hash =>
        ElectrumClientPool.readServerAddresses(
          getClass.getResourceAsStream("servers_mainnet.json")
        )
      case Block.TestnetGenesisBlock.hash =>
        ElectrumClientPool.readServerAddresses(
          getClass.getResourceAsStream("servers_testnet.json")
        )
      case _ => throw new RuntimeException
    }

    CheckPoint.loadFromChainHash = {
      case Block.LivenetGenesisBlock.hash =>
        CheckPoint.load(
          getClass.getResourceAsStream("checkpoints_mainnet.json")
        )
      case Block.TestnetGenesisBlock.hash =>
        CheckPoint.load(
          getClass.getResourceAsStream("checkpoints_testnet.json")
        )
      case _ => throw new RuntimeException
    }

    println("# instantiating channel master")
    LNParams.cm = new ChannelMaster(payBag, chanBag, extDataBag, pf) {
      // There will be a disconnect if VPN (Orbot) suddenly stops working, we then clear everything and restart an app
      override def initConnect: Unit = super.initConnect
    }

    println("# instantiating electrum actors")
    val electrumPool = LNParams.loggedActor(
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
        electrumPool,
        extDataBag,
        LNParams.chainHash
      ),
      "chain-sync"
    )
    val watcher = LNParams.loggedActor(
      Props(classOf[ElectrumWatcher], LNParams.blockCount, electrumPool),
      "channel-watcher"
    )
    val catcher =
      LNParams.loggedActor(Props(new WalletEventsCatcher), "events-catcher")

    println("# loading onchain wallets")
    val params =
      WalletParameters(
        extDataBag,
        chainWalletBag,
        txDataBag,
        dustLimit = 546L.sat
      )
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
      override def onChainTipKnown(blockCountEvent: CurrentBlockCount): Unit =
        LNParams.cm.initConnect

      override def onWalletReady(blockCountEvent: WalletReady): Unit =
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

      override def onTransactionReceived(txEvent: TransactionReceived): Unit = {
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

        val fee = txEvent.feeOpt.getOrElse(0L.sat)
        val defDescription =
          TxDescription.define(LNParams.cm.all.values, Nil, txEvent.tx)
        val sentTxDescription =
          txDescriptions.getOrElse(txEvent.tx.txid, default = defDescription)
        if (txEvent.sent == txEvent.received + fee)
          addChainTx(
            received = 0L.sat,
            sent = fee,
            sentTxDescription,
            isIncoming = 0L
          )
        else if (txEvent.sent > txEvent.received)
          addChainTx(
            received = 0L.sat,
            txEvent.sent - txEvent.received - fee,
            sentTxDescription,
            isIncoming = 0L
          )
        else
          addChainTx(
            txEvent.received - txEvent.sent,
            sent = 0L.sat,
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
    // Get channels and still active FSMs up and running
    LNParams.cm.all = Channel.load(listeners = Set(LNParams.cm), chanBag)
    // Only schedule periodic resync if Lightning channels are being present
    if (LNParams.cm.all.nonEmpty) pf process PathFinder.CMDStartPeriodicResync
    // This inital notification will create all in/routed/out FSMs
    LNParams.cm.notifyResolvers

    println("# is operational: %b".format(LNParams.isOperational))
    LNParams.system.log.info("Test IMMORTAN LOG output")

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
}
