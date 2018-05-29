package sigmastate.serialization

import sigmastate.Values._
import sigmastate._
import sigmastate.serialization.ValueSerializer._
import OpCodes._

class RelationsSpecification extends TableSerializationSpecification {

  override val objects =
    Table(
      ("object", "bytes"),
      (LT(LongConstant(2), LongConstant(3)), Array[Byte](LtCode, SLong.typeCode, 0, 0, 0, 0, 0, 0, 0, 2, SLong.typeCode, 0, 0, 0, 0, 0, 0, 0, 3)),
      (LE(LongConstant(2), LongConstant(3)), Array[Byte](LeCode, SLong.typeCode, 0, 0, 0, 0, 0, 0, 0, 2, SLong.typeCode, 0, 0, 0, 0, 0, 0, 0, 3)),
      (GT(LongConstant(6), LongConstant(5)), Array[Byte](GtCode, SLong.typeCode, 0, 0, 0, 0, 0, 0, 0, 6, SLong.typeCode, 0, 0, 0, 0, 0, 0, 0, 5)),
      (GE(LongConstant(6), LongConstant(5)), Array[Byte](GeCode, SLong.typeCode, 0, 0, 0, 0, 0, 0, 0, 6, SLong.typeCode, 0, 0, 0, 0, 0, 0, 0, 5)),
      (EQ(TrueLeaf, FalseLeaf), Array[Byte](EqCode, TrueCode, FalseCode)),
      (NEQ(TrueLeaf, FalseLeaf), Array[Byte](NeqCode, TrueCode, FalseCode))
    )

  tableRoundTripTest("Relations: serializer round trip")
  tablePredefinedBytesTest("Relations: deserialize from predefined bytes")

  property("Relations: serialization LT(bool, bool) must fail") {
    assertThrows[Exception] {
      deserialize(Array[Byte](LtCode, 12, 13))
    }
  }

  property("IsMember: Serializer round trip") {
    forAll { im: IsMember =>
      roundTripTest(im)
    }
  }

  property("If: Serializer round trip") {
    forAll { i: If[SLong.type] =>
      roundTripTest(i)
    }
  }
}
