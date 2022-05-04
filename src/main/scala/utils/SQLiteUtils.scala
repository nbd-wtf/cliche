package utils

import java.sql.{Connection, DriverManager}
import immortan.sqlite._

object SQLiteUtils {
  def getConnection(datadir: String): Connection =
    DriverManager.getConnection(s"jdbc:sqlite:${datadir}/db.sqlite")

  def interfaceWithTables(con: Connection, tables: Table*): DBInterface = {
    val interface = DBInterfaceSQLiteGeneral(con)

    interface txWrap {
      val preparedStatement = interface.connection.createStatement
      tables
        .flatMap(_.createStatements)
        .foreach(preparedStatement.executeUpdate)
    }

    interface
  }
}
