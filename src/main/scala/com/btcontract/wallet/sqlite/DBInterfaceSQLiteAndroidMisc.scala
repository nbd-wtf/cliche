package com.btcontract.wallet.sqlite

import java.sql.Connection

import immortan.sqlite._

class DBInterfaceSQLiteAndroidMisc(context: Connection) {
  val base: Connection = context

  TxTable.createStatements.foreach(s => base.prepareStatement(s).execute())
  ChannelTxFeesTable.createStatements.foreach(s =>
    base.prepareStatement(s).execute()
  )
  ElectrumHeadersTable.createStatements.foreach(s =>
    base.prepareStatement(s).execute()
  )
  ChainWalletTable.createStatements.foreach(s =>
    base.prepareStatement(s).execute()
  )
  LNUrlPayTable.createStatements.foreach(s =>
    base.prepareStatement(s).execute()
  )
  PaymentTable.createStatements.foreach(s => base.prepareStatement(s).execute())
  RelayTable.createStatements.foreach(s => base.prepareStatement(s).execute())
  DataTable.createStatements.foreach(s => base.prepareStatement(s).execute())
  LogTable.createStatements.foreach(s => base.prepareStatement(s).execute())

//  def onUpgrade(dbs: SQLiteDatabase, v0: Int, v1: Int): Unit = {
//    LNUrlPayTable.createStatements.foreach(dbs.execSQL) // 1 -> 2 migration creates LNURL-PAY table
//    LogTable.createStatements.foreach(dbs.execSQL) // 1 | 2 -> 3 migration creates error log table
//  }
}
