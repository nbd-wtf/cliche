package standartsat.immortancli.utils

import immortan.sqlite._

import java.sql.{Connection, DriverManager}


object SQLiteUtils {
  def getConnection: Connection = DriverManager.getConnection("jdbc:sqlite:db.sqlite")

  def interfaceWithTables(con: Connection, tables: Table*): DBInterface = {
    val interface = DBInterfaceSQLiteGeneral(con)

    interface txWrap {
      val preparedStatement = interface.connection.createStatement
      tables.flatMap(_.createStatements).foreach(preparedStatement.executeUpdate)
    }

    interface
  }

  def getSQLiteNetworkStores: (SQLiteNetwork, SQLiteNetwork) = {
    val interface = interfaceWithTables(getConnection, NormalChannelAnnouncementTable, HostedChannelAnnouncementTable,
      NormalExcludedChannelTable, HostedExcludedChannelTable, NormalChannelUpdateTable, HostedChannelUpdateTable)

    val normal = new SQLiteNetwork(interface, NormalChannelUpdateTable, NormalChannelAnnouncementTable, NormalExcludedChannelTable)
    val hosted = new SQLiteNetwork(interface, HostedChannelUpdateTable, HostedChannelAnnouncementTable, HostedExcludedChannelTable)
    (normal, hosted)
  }
}
