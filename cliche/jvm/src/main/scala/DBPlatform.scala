import java.sql.{Connection, DriverManager}
import immortan.sqlite.DBInterface
import immortan.utils.JavaDBInterface

object DBPlatform {
  def apply(path: String): DBInterface = {
    val conn: Connection = DriverManager.getConnection(s"jdbc:sqlite:$path")
    new JavaDBInterface(conn)
  }
}
