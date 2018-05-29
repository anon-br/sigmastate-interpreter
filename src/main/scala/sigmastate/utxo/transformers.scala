package sigmastate.utxo

import org.bitbucket.inkytonik.kiama.rewriting.Rewritable
import org.bitbucket.inkytonik.kiama.rewriting.Rewriter.{rule, everywherebu}
import sigmastate.SCollection.SByteArray
import sigmastate.Values._
import sigmastate.lang.Terms._
import sigmastate._
import sigmastate.interpreter.{Context, Interpreter}
import sigmastate.serialization.OpCodes.OpCode
import sigmastate.serialization.OpCodes
import sigmastate.utils.Helpers
import sigmastate.utxo.BooleanTransformer.ResultConstructor
import sigmastate.utxo.CostTable.Cost
import org.ergoplatform.ErgoBox.RegisterIdentifier

import scala.collection.immutable


trait Transformer[IV <: SType, OV <: SType] extends NotReadyValue[OV] {

  val input: Value[IV]

  def transformationReady: Boolean = input.evaluated

  def function(input: EvaluatedValue[IV]): Value[OV]

  def function(): Value[OV] = input match {
    case ev: EvaluatedValue[IV] => function(ev)
    case _ => Interpreter.error(s"Transformer function can be called only after input value is evaluated: $input")
  }

  def evaluate(): Value[OV] = input match {
    case ev: EvaluatedValue[IV] => function(ev)
    case _: NotReadyValue[OV] => this
  }
}


case class MapCollection[IV <: SType, OV <: SType](
    input: Value[SCollection[IV]],
    id: Byte,
    mapper: SValue)
  extends Transformer[SCollection[IV], SCollection[OV]] {

  override val opCode: OpCode = OpCodes.MapCollectionCode
  implicit def tOV = mapper.asValue[OV].tpe
  val tpe = SCollection[OV](tOV)

  override def transformationReady: Boolean = input.isEvaluated

  override def function(cl: EvaluatedValue[SCollection[IV]]): Value[SCollection[OV]] = {
    def rl(arg: Value[IV]) = everywherebu(rule[Value[IV]] {
      case t: TaggedVariable[IV] if t.varId == id => arg
    })

    ConcreteCollection(cl.items.map(el => rl(el)(mapper).get.asInstanceOf[Transformer[IV, OV]]).map(_.function()))
  }

  /**
    * We consider transformation cost as size of collection * cost of trasfomation of one element of the collection.
    * We also need to add cost of resulting collection, but as we can not calculate it in prior, we assume that cost of
    * resulting collection is no more than cost of initial collection, so we taker latter as the estimation.
    *
    * @param context
    * @tparam C
    * @return
    */
  override def cost[C <: Context[C]](context: C): Long =
    Cost.MapDeclaration + input.cost(context) * (mapper.cost(context) + 1)
}

case class Append[IV <: SType](input: Value[SCollection[IV]], col2: Value[SCollection[IV]])
  extends Transformer[SCollection[IV], SCollection[IV]] {
  override val opCode: OpCode = OpCodes.AppendCode

  val tpe = input.tpe

  override def transformationReady: Boolean = input.isEvaluated && col2.isEvaluated

  override def function(cl: EvaluatedValue[SCollection[IV]]): Value[SCollection[IV]] = (cl, col2) match {
    case (ConcreteCollection(items), ConcreteCollection(items2)) =>
      ConcreteCollection(items ++ items2)(tpe.elemType)

    case (CollectionConstant(arr, t1), CollectionConstant(arr2, t2)) =>
      if (t1 != t2)
        Interpreter.error(s"Cannot Append arrays of different types $t1 and $t2")
      val newArr = Helpers.concatArrays(Seq(arr, arr2))(t1.classTag)
      CollectionConstant(newArr, t1)

    case (CollectionConstant(arr, t1), arr2 @ ConcreteCollection(_)) =>
      val newArr = Helpers.concatArrays(Seq(arr, arr2.value))(t1.classTag)
      CollectionConstant(newArr, t1)

    case (arr @ ConcreteCollection(_), CollectionConstant(arr2, t2)) =>
      val newArr = Helpers.concatArrays(Seq(arr.value, arr2))(t2.classTag)
      CollectionConstant(newArr, t2)

    case _ =>
      Interpreter.error(s"Don't know how to append $cl and $col2")
  }

  override def cost[C <: Context[C]](context: C): Long = input.cost(context) + col2.cost(context)
}

case class Slice[IV <: SType](input: Value[SCollection[IV]], from: Value[SLong.type], until: Value[SLong.type])
  extends Transformer[SCollection[IV], SCollection[IV]] {
  override val opCode: OpCode = OpCodes.SliceCode

  val tpe = input.tpe

  override def transformationReady: Boolean =
    input.isEvaluated && from.evaluated && until.evaluated

  override def function(cl: EvaluatedValue[SCollection[IV]]): Value[SCollection[IV]] = {
    val fromValue = from.asInstanceOf[EvaluatedValue[SLong.type]].value
    val untilValue = until.asInstanceOf[EvaluatedValue[SLong.type]].value
    ConcreteCollection(cl.items.slice(fromValue.toInt, untilValue.toInt))(tpe.elemType)
  }

  override def cost[C <: Context[C]](context: C): Long =
    input.cost(context) * 2 + from.cost(context) + until.cost(context)
}

case class Where[IV <: SType](input: Value[SCollection[IV]],
                              id: Byte,
                              condition: Value[SBoolean.type])
  extends Transformer[SCollection[IV], SCollection[IV]] {
  override val opCode: OpCode = OpCodes.WhereCode

  override def tpe: SCollection[IV] = input.tpe

  override def transformationReady: Boolean = input.isEvaluated

  override def cost[C <: Context[C]](context: C): Long =
    Cost.WhereDeclaration + input.cost(context) * condition.cost(context) + input.cost(context)

  override def function(input: EvaluatedValue[SCollection[IV]]): ConcreteCollection[IV] = {
    def rl(arg: Value[IV]) = everywherebu(rule[Value[IV]] {
      case t: TaggedVariable[IV] if t.varId == id => arg
    })

    def p(x: Value[IV]): Boolean = {
      val res = rl(x)(condition).get
      res.asInstanceOf[EvaluatedValue[SBoolean.type]].value
    }

    val filtered = input.items.filter(p)
    ConcreteCollection(filtered)(tpe.elemType)
  }
}

trait BooleanTransformer[IV <: SType] extends Transformer[SCollection[IV], SBoolean.type] {
  override val input: Value[SCollection[IV]]
  val id: Byte
  val condition: Value[SBoolean.type]
  val f: ResultConstructor

  override def tpe = SBoolean

  override def transformationReady: Boolean = input.isEvaluated

  override def function(input: EvaluatedValue[SCollection[IV]]): Value[SBoolean.type] = {
    def rl(arg: Value[IV]) = everywherebu(rule[Value[IV]] {
      case t: TaggedVariable[IV] if t.varId == id => arg
    })

    f(input.items.map(el => rl(el)(condition).get.asInstanceOf[Value[SBoolean.type]]))
  }
}

object BooleanTransformer {
  type ResultConstructor = (Seq[Value[SBoolean.type]]) => Transformer[SCollection[SBoolean.type], SBoolean.type]
}

case class Exists[IV <: SType](input: Value[SCollection[IV]],
                               id: Byte,
                               condition: Value[SBoolean.type])
  extends BooleanTransformer[IV] {
  override val opCode: OpCode = OpCodes.ExistsCode

  override def cost[C <: Context[C]](context: C): Long =
    Cost.ExistsDeclaration + input.cost(context) * condition.cost(context) + Cost.OrDeclaration

  override val f: ResultConstructor = OR.apply
}

case class ForAll[IV <: SType](input: Value[SCollection[IV]],
                               id: Byte,
                               condition: Value[SBoolean.type])
  extends BooleanTransformer[IV] {

  override val opCode: OpCode = OpCodes.ForAllCode

  override def cost[C <: Context[C]](context: C) =
    Cost.ForAllDeclaration + input.cost(context) * condition.cost(context) + Cost.AndDeclaration

  override val f: ResultConstructor = AND.apply
}


case class Fold[IV <: SType](input: Value[SCollection[IV]],
                             id: Byte,
                             zero: Value[IV],
                             accId: Byte,
                             foldOp: SValue)
  extends Transformer[SCollection[IV], IV] with NotReadyValue[IV] {
  override val opCode: OpCode = OpCodes.FoldCode
  implicit def tpe = input.tpe.elemType

  override def transformationReady: Boolean =
    input.evaluated &&
      input.items.forall(_.evaluated) &&
      zero.isInstanceOf[EvaluatedValue[IV]]

  override def cost[C <: Context[C]](context: C): Long =
    Cost.FoldDeclaration + zero.cost(context) + input.cost(context) * foldOp.cost(context)

  override def function(input: EvaluatedValue[SCollection[IV]]): Value[IV] = {
    def rl(arg: Value[IV], acc: Value[IV]) = everywherebu(rule[Value[IV]] {
      case t: TaggedVariable[IV] if t.varId == id => arg
      case t: TaggedVariable[IV] if t.varId == accId => acc
    })

    input.items.foldLeft(zero) { case (acc: Value[IV], elem: Value[IV]) =>
      rl(elem, acc)(foldOp).get.asInstanceOf[Value[IV]]
    }
  }
}

object Fold {
  def sum(input: Value[SCollection[SLong.type]]) =
    Fold(input, 21, LongConstant(0), 22, Plus(TaggedInt(22), TaggedInt(21)))

  def concat[T <: SType](input: Value[SCollection[SCollection[T]]])(implicit tT: T) = {
    val tCol = SCollection(tT)
    Fold[SCollection[T]](
      input, 21, ConcreteCollection()(tT), 22,
      Append(TaggedVariable(22, tCol), TaggedVariable(21, tCol)))
  }
}

case class ByIndex[V <: SType](input: Value[SCollection[V]], index: Value[SLong.type])
  extends Transformer[SCollection[V], V] with NotReadyValue[V] {
  override val opCode: OpCode = OpCodes.ByIndexCode
  override val tpe = input.tpe.elemType
  override def transformationReady: Boolean = input.isEvaluated && index.evaluated

  override def function(input: EvaluatedValue[SCollection[V]]): Value[V] = {
    val i = index.asInstanceOf[EvaluatedValue[SLong.type]].value.toInt
    input.matchCase(
      cc => cc.items(i),
      const => Value.apply(tpe)(const.value(i).asInstanceOf[tpe.WrappedType])
    )
  }
  override def cost[C <: Context[C]](context: C) = input.cost(context) + Cost.ByIndexDeclaration
}

case class SizeOf[V <: SType](input: Value[SCollection[V]])
  extends Transformer[SCollection[V], SLong.type] with NotReadyValueInt {

  override val opCode: OpCode = OpCodes.SizeOfCode

  override def function(input: EvaluatedValue[SCollection[V]]) = LongConstant(input.length)

  //todo: isn't this cost too high? we can get size of a collection without touching it
  override def cost[C <: Context[C]](context: C) = input.cost(context) + Cost.SizeOfDeclaration
}


sealed trait Extract[V <: SType] extends Transformer[SBox.type, V] {
  override def function(box: EvaluatedValue[SBox.type]): Value[V]
}

case class ExtractAmount(input: Value[SBox.type]) extends Extract[SLong.type] with NotReadyValueInt {
  override val opCode: OpCode = OpCodes.ExtractAmountCode

  override def cost[C <: Context[C]](context: C) = 10

  override def function(box: EvaluatedValue[SBox.type]): Value[SLong.type] = LongConstant(box.value.value)
}


case class ExtractScriptBytes(input: Value[SBox.type]) extends Extract[SByteArray] with NotReadyValueByteArray {
  override val opCode: OpCode = OpCodes.ExtractScriptBytesCode

  override def cost[C <: Context[C]](context: C) = 1000

  override def function(box: EvaluatedValue[SBox.type]): Value[SByteArray] = {
    ByteArrayConstant(box.value.propositionBytes)
  }
}


case class ExtractBytes(input: Value[SBox.type]) extends Extract[SByteArray] with NotReadyValueByteArray {
  override val opCode: OpCode = OpCodes.ExtractBytesCode

  override def cost[C <: Context[C]](context: C): Long = 1000 //todo: make it PerKb * max box size in kbs

  override def function(box: EvaluatedValue[SBox.type]): Value[SByteArray] = ByteArrayConstant(box.value.bytes)
}

case class ExtractBytesWithNoRef(input: Value[SBox.type]) extends Extract[SByteArray] with NotReadyValueByteArray {
  override val opCode: OpCode = OpCodes.ExtractBytesWithNoRefCode

  override def cost[C <: Context[C]](context: C) = 1000 //todo: make it PerKb * max box size in kbs

  override def function(box: EvaluatedValue[SBox.type]): Value[SByteArray] = ByteArrayConstant(box.value.bytesWithNoRef)
}

case class ExtractId(input: Value[SBox.type]) extends Extract[SByteArray] with NotReadyValueByteArray {
  override val opCode: OpCode = OpCodes.ExtractIdCode

  override def cost[C <: Context[C]](context: C) = 10

  override def function(box: EvaluatedValue[SBox.type]): Value[SByteArray] = ByteArrayConstant(box.value.id)
}

case class ExtractRegisterAs[V <: SType](
    input: Value[SBox.type],
    registerId: RegisterIdentifier,
    tpe: V,
    default: Option[Value[V]])
  extends Extract[V] with NotReadyValue[V] {
  override val opCode: OpCode = OpCodes.ExtractRegisterAs
  override def cost[C <: Context[C]](context: C) = 1000 //todo: the same as ExtractBytes.cost
  override def function(box: EvaluatedValue[SBox.type]): Value[V] =
    box.value.get(registerId).orElse(default).get.asInstanceOf[Value[V]]
}

object ExtractRegisterAs {
  def apply[V <: SType](input: Value[SBox.type],
      registerId: RegisterIdentifier,
      default: Option[Value[V]] = None)(implicit tpe: V): ExtractRegisterAs[V] = ExtractRegisterAs(input, registerId, tpe, default)
}

trait Deserialize[V <: SType] extends NotReadyValue[V]


case class DeserializeContext[V <: SType](id: Byte, tpe: V) extends Deserialize[V] {
  override val opCode: OpCode = OpCodes.DeserializeContextCode
  override def cost[C <: Context[C]](context: C): Long = 1000 //todo: rework, consider limits
}


//todo: write test for this class
case class DeserializeRegister[V <: SType](
    reg: RegisterIdentifier, tpe: V, default: Option[Value[V]] = None) extends Deserialize[V] {

  override val opCode: OpCode = OpCodes.DeserializeRegisterCode
  override def cost[C <: Context[C]](context: C): Long = 1000 //todo: rework, consider limits
}
