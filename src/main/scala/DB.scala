import fr.acinq.eclair.channel.PersistentChannelData
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
import com.btcontract.wallet.sqlite.{
  DBInterfaceSQLiteAndroidEssential,
  DBInterfaceSQLiteAndroidGraph,
  DBInterfaceSQLiteAndroidMisc,
  SQLiteDataExtended
}
import utils.SQLiteUtils

object DB {
  println("# setting up database")

  val sqlitedb = SQLiteUtils.getConnection(Config.datadir)
  val dbinterface = DBInterfaceSQLiteGeneral(sqlitedb)
  val miscInterface = new DBInterfaceSQLiteAndroidMisc(sqlitedb)
  var txDataBag: SQLiteTx = null
  var lnUrlPayBag: SQLiteLNUrlPay = null
  var chainWalletBag: SQLiteChainWallet = null
  var extDataBag: SQLiteDataExtended = null

  dbinterface txWrap {
    txDataBag = new SQLiteTx(dbinterface)
    lnUrlPayBag = new SQLiteLNUrlPay(dbinterface)
    chainWalletBag = new SQLiteChainWallet(dbinterface)
    extDataBag = new SQLiteDataExtended(dbinterface)
  }

  val logBag = new SQLiteLog(dbinterface)

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

}
