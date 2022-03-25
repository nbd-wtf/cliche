package com.btcontract.wallet.sqlite

import java.sql.Connection

import immortan.sqlite._

class DBInterfaceSQLiteAndroidEssential(context: Connection) {
  val base: Connection = context

  ChannelTable.createStatements.foreach(s => base.prepareStatement(s).execute())
  HtlcInfoTable.createStatements.foreach(s =>
    base.prepareStatement(s).execute()
  )
  PreimageTable.createStatements.foreach(s =>
    base.prepareStatement(s).execute()
  )
}
