import java.sql.{Connection, DriverManager}
import fr.acinq.eclair.channel.PersistentChannelData
import fr.acinq.bitcoin.ByteVector32
import immortan.WalletSecret
import immortan.sqlite._

object DB {
  val dbname: String =
    WalletSecret(Config.seed).keys.ourNodePrivateKey.publicKey.toString.take(6)
  val dbpath: String = s"${Config.datadir}/db-${dbname}.sqlite"
  println(s"# setting up database at $dbpath")
  val sqlitedb = DriverManager.getConnection(s"jdbc:sqlite:$dbpath")

  // replace this with something that does migrations properly in the future
  DBInit.createTables(sqlitedb)

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
  val payBag = new SQLitePayment(extDataBag.db, preimageDb = dbinterface) {
    override def addSearchablePayment(
        search: String,
        paymentHash: ByteVector32
    ): Unit = {}
  }

  val chanBag =
    new SQLiteChannel(dbinterface, channelTxFeesDb = extDataBag.db) {
      override def put(
          data: PersistentChannelData
      ): PersistentChannelData = {
        super.put(data)
      }
    }
}
