import fr.acinq.eclair.channel.PersistentChannelData
import immortan.WalletSecret
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
  SQLiteTx,
  SQLiteData
}
import utils.SQLiteUtils

object DB {
  println("# setting up database")

  val dbname: String =
    WalletSecret(Config.seed).keys.ourNodePrivateKey.publicKey.toString.take(6)
  val sqlitedb = SQLiteUtils.getConnection(dbname, Config.datadir)
  val dbinterface = DBInterfaceSQLiteGeneral(sqlitedb)
  var txDataBag: SQLiteTx = null
  var lnUrlPayBag: SQLiteLNUrlPay = null
  var chainWalletBag: SQLiteChainWallet = null
  var extDataBag: SQLiteData = null

  dbinterface txWrap {
    txDataBag = new SQLiteTx(dbinterface)
    lnUrlPayBag = new SQLiteLNUrlPay(dbinterface)
    chainWalletBag = new SQLiteChainWallet(dbinterface)
    extDataBag = new SQLiteData(dbinterface)
  }

  val logBag = new SQLiteLog(dbinterface)

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

}
