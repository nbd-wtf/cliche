package com.btcontract.wallet.sqlite

import java.sql.Connection

import immortan.sqlite._

class DBInterfaceSQLiteAndroidGraph(context: Connection) {
  val base: Connection = context

  NormalChannelAnnouncementTable.createStatements.foreach(s =>
    base.prepareStatement(s).execute()
  )
  HostedChannelAnnouncementTable.createStatements.foreach(s =>
    base.prepareStatement(s).execute()
  )

  NormalExcludedChannelTable.createStatements.foreach(s =>
    base.prepareStatement(s).execute()
  )
  HostedExcludedChannelTable.createStatements.foreach(s =>
    base.prepareStatement(s).execute()
  )

  NormalChannelUpdateTable.createStatements.foreach(s =>
    base.prepareStatement(s).execute()
  )
  HostedChannelUpdateTable.createStatements.foreach(s =>
    base.prepareStatement(s).execute()
  )

}
