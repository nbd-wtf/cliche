import immortan.sqlite.DBInterface

object DBPlatform {
  def apply(path: String): DBInterface = throw NotImplementedError(
    "javascript!"
  )
}
