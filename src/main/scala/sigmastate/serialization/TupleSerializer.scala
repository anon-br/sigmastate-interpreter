package sigmastate.serialization

import sigmastate.Values._
import sigmastate.serialization.OpCodes._
import sigmastate.utils.{ByteReader, ByteWriter}
import sigmastate.utils.Extensions._

object TupleSerializer extends ValueSerializer[Tuple] {

  override val opCode: Byte = TupleCode

  override def serializeBody(obj: Tuple, w: ByteWriter): Unit = {
    val length = obj.length
    w.putUByte(length)
    obj.items.foreach(w.putValue)
  }

  override def parseBody(r: ByteReader): Tuple = {
    val size = r.getByte()
    val values =  (1 to size).map(_ => r.getValue())
    Tuple(values)
  }

}
