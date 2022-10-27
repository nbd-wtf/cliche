import java.nio.file.Paths
import scoin.ByteVector32
import immortan.WalletSecret
import immortan.channel.PersistentChannelData
import immortan.sqlite._

object DB {
  val dbname: String =
    WalletSecret(Config.seed).keys.ourNodePrivateKey.publicKey.toString.take(6)
  val dbpath: String =
    Paths
      .get(Config.datadir)
      .resolve("db-${dbname}.sqlite")
      .toAbsolutePath()
      .toString()
  println(s"# setting up database at $dbpath")

  val sqlitedb: DBInterface = DBPlatform(dbpath)

  // replace this with something that does migrations properly in the future
  DBInit.createTables(sqlitedb)

  var txDataBag: SQLiteTx = null
  var lnUrlPayBag: SQLiteLNUrlPay = null
  var chainWalletBag: SQLiteChainWallet = null
  var extDataBag: SQLiteData = null

  sqlitedb.txWrap {
    txDataBag = new SQLiteTx(sqlitedb)
    lnUrlPayBag = new SQLiteLNUrlPay(sqlitedb)
    chainWalletBag = new SQLiteChainWallet(sqlitedb)
    extDataBag = new SQLiteData(sqlitedb)
  }

  val logBag = new SQLiteLog(sqlitedb)

  val normalBag = new SQLiteNetwork(
    sqlitedb,
    NormalChannelUpdateTable,
    NormalChannelAnnouncementTable,
    NormalExcludedChannelTable
  )
  val hostedBag = new SQLiteNetwork(
    sqlitedb,
    HostedChannelUpdateTable,
    HostedChannelAnnouncementTable,
    HostedExcludedChannelTable
  )
  val payBag = new SQLitePayment(extDataBag.db, preimageDb = sqlitedb) {
    override def addSearchablePayment(
        search: String,
        paymentHash: ByteVector32
    ): Unit = {}
  }

  val chanBag =
    new SQLiteChannel(sqlitedb, channelTxFeesDb = extDataBag.db) {
      override def put(
          data: PersistentChannelData
      ): PersistentChannelData = {
        super.put(data)
      }
    }
}
