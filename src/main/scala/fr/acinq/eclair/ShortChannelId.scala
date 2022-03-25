package fr.acinq.eclair

object ShortChannelId {
  def produce(sid: String): Long = sid.split("x").toList match {
    case blockHeight :: txIndex :: outputIndex :: Nil =>
      toShortId(blockHeight.toInt, txIndex.toInt, outputIndex.toInt)
    case _ =>
      throw new IllegalArgumentException(s"Invalid short channel id: $sid")
  }

  def asString(sid: Long): String = {
    val list = blockHeight(sid) :: txIndex(sid) :: outputIndex(sid) :: Nil
    list.mkString("x")
  }

  def apply(blockHeight: Int, txIndex: Int, outputIndex: Int): Long =
    toShortId(blockHeight, txIndex, outputIndex)

  def toShortId(blockHeight: Int, txIndex: Int, outputIndex: Int): Long =
    ((blockHeight & 0xffffffL) << 40) | ((txIndex & 0xffffffL) << 16) | (outputIndex & 0xffffL)

  @inline def blockHeight(sid: Long): Int = ((sid >> 40) & 0xffffff).toInt

  @inline def txIndex(sid: Long): Int = ((sid >> 16) & 0xffffff).toInt

  @inline def outputIndex(sid: Long): Int = (sid & 0xffff).toInt
}
