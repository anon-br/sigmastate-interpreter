package special.sigma

import java.math.BigInteger

import org.ergoplatform.ErgoLikeContext.dummyPubkey
import org.ergoplatform.{ErgoLikeContext, ErgoBox}
import org.scalacheck.Gen.containerOfN
import org.scalatest.prop.PropertyChecks
import org.scalatest.{PropSpec, Matchers}
import org.scalacheck.{Arbitrary, Gen}
import scalan.RType
import scorex.crypto.authds.{ADKey, ADValue}
import scorex.crypto.authds.avltree.batch._
import scorex.crypto.hash.{Digest32, Blake2b256}
import sigmastate.helpers.SigmaTestingCommons
import sigma.util.Extensions._
import sigmastate.eval.Extensions._
import sigmastate.eval._
import sigmastate._
import sigmastate.Values.{Constant, SValue, IntConstant, ErgoTree, BooleanConstant}
import sigmastate.interpreter.{Interpreter, ContextExtension}
import sigmastate.interpreter.Interpreter.emptyEnv
import special.collection.Coll
import special.collections.CollGens

trait SigmaTypeGens {
  import sigma.types._
  val genBoolean = Arbitrary.arbBool.arbitrary.map(CBoolean(_): Boolean)
  implicit val arbBoolean = Arbitrary(genBoolean)
  
  val genByte = Arbitrary.arbByte.arbitrary.map(CByte(_): Byte)
  implicit val arbByte = Arbitrary(genByte)

  val genInt = Arbitrary.arbInt.arbitrary.map(CInt(_): Int)
  implicit val arbInt = Arbitrary(genInt)
}

/** This suite tests every method of every SigmaDsl type to be equivalent to
  * the evaluation of the corresponding ErgoScript operation */
class SigmaDslTest extends PropSpec with PropertyChecks with Matchers with SigmaTestingCommons with CollGens with SigmaTypeGens {
  implicit lazy val IR = new TestingIRContext {
    override val okPrintEvaluatedEntries: Boolean = false
  }

  val SigmaDsl = CostingSigmaDslBuilder

  def checkEq[A,B](f: A => B)(g: A => B): A => Unit = { x: A =>
    val b1 = f(x); val b2 = g(x)
//    assert(b1.getClass == b2.getClass)
    assert(b1 == b2)
  }

  def checkEq2[A,B,R](f: (A, B) => R)(g: (A, B) => R): (A,B) => Unit = { (x: A, y: B) =>
    val r1 = f(x, y); val r2 = g(x, y)
    assert(r1.getClass == r2.getClass)
    assert(r1 == r2)
  }

  property("Boolean methods equivalence") {
    lazy val toByte = checkEq(func[Boolean,Byte]("{ (x: Boolean) => x.toByte }"))(x => x.toByte)
    forAll { x: Boolean =>
      x.toByte
    }
  }

  property("Byte methods equivalence") {
    val toShort = checkEq(func[Byte,Short]("{ (x: Byte) => x.toShort }"))(x => x.toShort)
    val toInt = checkEq(func[Byte,Int]("{ (x: Byte) => x.toInt }"))(x => x.toInt)
    val toLong = checkEq(func[Byte,Long]("{ (x: Byte) => x.toLong }"))(x => x.toLong)
    val toBigInt = checkEq(func[Byte,BigInt]("{ (x: Byte) => x.toBigInt }"))(x => x.toBigInt)
    lazy val toBytes = checkEq(func[Byte,Coll[Byte]]("{ (x: Byte) => x.toBytes }"))(x => x.toBytes)
    lazy val toBits = checkEq(func[Byte,Coll[Boolean]]("{ (x: Byte) => x.toBits }"))(x => x.toBits)
    lazy val toAbs = checkEq(func[Byte,Byte]("{ (x: Byte) => x.toAbs }"))(x => x.toAbs)
    lazy val compareTo = checkEq(func[(Byte, Byte), Int]("{ (x: (Byte, Byte)) => x._1.compareTo(x._2) }"))({ (x: (Byte, Byte)) => x._1.compareTo(x._2) })

    forAll { x: Byte =>
      Seq(toInt, toLong, toBigInt).foreach(_(x))
//TODO toBytes, toBits, toAbs
    }
    forAll { x: (Byte, Byte) =>
//TODO  compareTo(x)
    }
  }


  property("Int methods equivalence") {
    val toByte = checkEq(func[Int,Byte]("{ (x: Int) => x.toByte }"))(x => x.toByte)
    val toShort = checkEq(func[Int,Short]("{ (x: Int) => x.toShort }"))(x => x.toShort)
    val toInt = checkEq(func[Int,Int]("{ (x: Int) => x.toInt }"))(x => x.toInt)
    val toLong = checkEq(func[Int,Long]("{ (x: Int) => x.toLong }"))(x => x.toLong)
    val toBigInt = checkEq(func[Int,BigInt]("{ (x: Int) => x.toBigInt }"))(x => x.toBigInt)
    lazy val toBytes = checkEq(func[Int,Coll[Byte]]("{ (x: Int) => x.toBytes }"))(x => x.toBytes)
    lazy val toBits = checkEq(func[Int,Coll[Boolean]]("{ (x: Int) => x.toBits }"))(x => x.toBits)
    lazy val toAbs = checkEq(func[Int,Int]("{ (x: Int) => x.toAbs }"))(x => x.toAbs)
    lazy val compareTo = checkEq(func[(Int, Int), Int]("{ (x: (Int, Int)) => x._1.compareTo(x._2) }"))(x => x._1.compareTo(x._2))

    forAll(valGen) { x: Int =>
      whenever(Byte.MinValue <= x && x <= scala.Byte.MaxValue) {
        toByte(x)
      }
      whenever(Short.MinValue <= x && x <= Short.MaxValue) {
        toShort(x)
      }
      Seq(toInt, toLong, toBigInt).foreach(_(x))
      //TODO toBytes, toBits, toAbs
    }
    forAll { x: (Int, Int) =>
      //TODO  compareTo(x)
    }
  }
  // TODO add tests for Short, Long, BigInt operations

  property("sigma.types.Byte methods equivalence") {
    import sigma.types._
    val toInt = checkEq(func[Byte,Int]("{ (x: Byte) => x.toInt }"))(x => x.toInt)
    forAll { x: Byte =>
      Seq(toInt).foreach(_(x))
    }
  }

  property("sigma.types.Int methods equivalence") {
    import sigma.types._
    val toByte = checkEq(func[Int,Byte]("{ (x: Int) => x.toByte }"))(x => x.toByte)
    lazy val compareTo = checkEq(func[(Int, Int), Int]("{ (x: (Int, Int)) => x._1.compareTo(x._2) }"))(x => x._1.compareTo(x._2))
    forAll(valGen) { in: scala.Int =>
      whenever(scala.Byte.MinValue <= in && in <= scala.Byte.MaxValue) {
        val x = CInt(in)
        toByte(x)
      }
    }
  }

  val bytesGen: Gen[Array[Byte]] = containerOfN[Array, Byte](100, Arbitrary.arbByte.arbitrary)
  val bytesCollGen = bytesGen.map(builder.fromArray(_))
  implicit val arbBytes = Arbitrary(bytesCollGen)
  val keyCollGen = bytesCollGen.map(_.slice(0, 32))
  import org.ergoplatform.dsl.AvlTreeHelpers._

  private def sampleAvlProver = {
    val key = keyCollGen.sample.get
    val value = bytesCollGen.sample.get
    val (_, avlProver) = createAvlTree(AvlTreeFlags.AllOperationsAllowed, ADKey @@ key.toArray -> ADValue @@ value.toArray)
    (key, value, avlProver)
  }

  private def sampleAvlTree = {
    val (key, _, avlProver) = sampleAvlProver
    val digest = avlProver.digest.toColl
    val tree = CostingSigmaDslBuilder.avlTree(AvlTreeFlags.ReadOnly.serializeToByte, digest, 32, None)
    tree
  }

  property("AvlTree properties equivalence") {
    val doDigest = checkEq(func[AvlTree, Coll[Byte]]("{ (t: AvlTree) => t.digest }")) { (t: AvlTree) => t.digest }
    val doEnabledOps = checkEq(func[AvlTree, Byte](
      "{ (t: AvlTree) => t.enabledOperations }")) { (t: AvlTree) => t.enabledOperations }
    val doKeyLength = checkEq(func[AvlTree, Int]("{ (t: AvlTree) => t.keyLength }")) { (t: AvlTree) => t.keyLength }
    val doValueLength = checkEq(func[AvlTree, Option[Int]]("{ (t: AvlTree) => t.valueLengthOpt }")) { (t: AvlTree) => t.valueLengthOpt }
    val insertAllowed = checkEq(func[AvlTree, Boolean]("{ (t: AvlTree) => t.isInsertAllowed }")) { (t: AvlTree) => t.isInsertAllowed }
    val updateAllowed = checkEq(func[AvlTree, Boolean]("{ (t: AvlTree) => t.isUpdateAllowed }")) { (t: AvlTree) => t.isUpdateAllowed }
    val removeAllowed = checkEq(func[AvlTree, Boolean]("{ (t: AvlTree) => t.isRemoveAllowed }")) { (t: AvlTree) => t.isRemoveAllowed }

    val tree = sampleAvlTree

    doDigest(tree)
    doEnabledOps(tree)
    doKeyLength(tree)
    doValueLength(tree)
    insertAllowed(tree)
    updateAllowed(tree)
    removeAllowed(tree)
  }

  property("AvlTree.{contains, get, getMany} equivalence") {
    val doContains = checkEq(
      func[(AvlTree, (Coll[Byte], Coll[Byte])), Boolean](
      "{ (t: (AvlTree, (Coll[Byte], Coll[Byte]))) => t._1.contains(t._2._1, t._2._2) }"))
         { (t: (AvlTree, (Coll[Byte], Coll[Byte]))) => t._1.contains(t._2._1, t._2._2) }
    val doGet = checkEq(
      func[(AvlTree, (Coll[Byte], Coll[Byte])), Option[Coll[Byte]]](
      "{ (t: (AvlTree, (Coll[Byte], Coll[Byte]))) => t._1.get(t._2._1, t._2._2) }"))
         { (t: (AvlTree, (Coll[Byte], Coll[Byte]))) => t._1.get(t._2._1, t._2._2) }
    val doGetMany = checkEq(
      func[(AvlTree, (Coll[Coll[Byte]], Coll[Byte])), Coll[Option[Coll[Byte]]]](
      "{ (t: (AvlTree, (Coll[Coll[Byte]], Coll[Byte]))) => t._1.getMany(t._2._1, t._2._2) }"))
         { (t: (AvlTree, (Coll[Coll[Byte]], Coll[Byte]))) => t._1.getMany(t._2._1, t._2._2) }

    val (key, _, avlProver) = sampleAvlProver
    avlProver.performOneOperation(Lookup(ADKey @@ key.toArray))
    val digest = avlProver.digest.toColl
    val proof = avlProver.generateProof().toColl
    val tree = CostingSigmaDslBuilder.avlTree(AvlTreeFlags.ReadOnly.serializeToByte, digest, 32, None)
    doContains((tree, (key, proof)))
    doGet((tree, (key, proof)))
    val keys = builder.fromItems(key)
    doGetMany((tree, (keys, proof)))
  }

  property("AvlTree.{insert, update, remove} equivalence") {
    type KV = (Coll[Byte], Coll[Byte])
    val doInsert = checkEq(
      func[(AvlTree, (Coll[KV], Coll[Byte])), Option[AvlTree]](
      "{ (t: (AvlTree, (Coll[(Coll[Byte], Coll[Byte])], Coll[Byte]))) => t._1.insert(t._2._1, t._2._2) }"))
         { (t: (AvlTree, (Coll[KV], Coll[Byte]))) => t._1.insert(t._2._1, t._2._2) }
    val doUpdate = checkEq(
      func[(AvlTree, (Coll[KV], Coll[Byte])), Option[AvlTree]](
        "{ (t: (AvlTree, (Coll[(Coll[Byte], Coll[Byte])], Coll[Byte]))) => t._1.update(t._2._1, t._2._2) }"))
        { (t: (AvlTree, (Coll[KV], Coll[Byte]))) => t._1.update(t._2._1, t._2._2) }
    val doRemove = checkEq(
      func[(AvlTree, (Coll[Coll[Byte]], Coll[Byte])), Option[AvlTree]](
      "{ (t: (AvlTree, (Coll[Coll[Byte]], Coll[Byte]))) => t._1.remove(t._2._1, t._2._2) }"))
         { (t: (AvlTree, (Coll[Coll[Byte]], Coll[Byte]))) => t._1.remove(t._2._1, t._2._2) }

    val avlProver = new BatchAVLProver[Digest32, Blake2b256.type](keyLength = 32, None)
    val key = Array.fill(32)(1.toByte).toColl
    
    {
      val preInsertDigest = avlProver.digest.toColl
      val value = bytesCollGen.sample.get
      avlProver.performOneOperation(Insert(ADKey @@ key.toArray, ADValue @@ value.toArray))
      val insertProof = avlProver.generateProof().toColl
      val preInsertTree = CostingSigmaDslBuilder.avlTree(AvlTreeFlags(true, false, false).serializeToByte, preInsertDigest, 32, None)
      val insertKvs = builder.fromItems((key -> value))
      doInsert((preInsertTree, (insertKvs, insertProof)))
    }

    {
      val preUpdateDigest = avlProver.digest.toColl
      val newValue = bytesCollGen.sample.get
      avlProver.performOneOperation(Update(ADKey @@ key.toArray, ADValue @@ newValue.toArray))
      val updateProof = avlProver.generateProof().toColl
      val preUpdateTree = CostingSigmaDslBuilder.avlTree(AvlTreeFlags(false, true, false).serializeToByte, preUpdateDigest, 32, None)
      val updateKvs = builder.fromItems((key -> newValue))
      doUpdate((preUpdateTree, (updateKvs, updateProof)))
    }

    {
      val preRemoveDigest = avlProver.digest.toColl
      avlProver.performOneOperation(Remove(ADKey @@ key.toArray))
      val removeProof = avlProver.generateProof().toColl
      val preRemoveTree = CostingSigmaDslBuilder.avlTree(AvlTreeFlags(false, false, true).serializeToByte, preRemoveDigest, 32, None)
      val removeKeys = builder.fromItems(key)
      doRemove((preRemoveTree, (removeKeys, removeProof)))
    }
  }


  // TODO costing: expression t._1(t._2) cannot be costed because t is lambda argument
  ignore("Func context variable") {
    val doApply = checkEq(func[(Int => Int, Int), Int]("{ (t: (Int => Int, Int)) => t._1(t._2) }")) { (t: (Int => Int, Int)) => t._1(t._2) }
    val code = compileWithCosting(emptyEnv, s"{ (x: Int) => x + 1 }")
    val ctx = ErgoLikeContext.dummy(fakeSelf)
    doApply((CFunc[Int, Int](ctx, code), 10))
  }

  val tokenId1: Digest32 = Blake2b256("id1")
  val tokenId2: Digest32 = Blake2b256("id2")
  val box = createBox(10, TrivialProp.TrueProp,
    Seq(tokenId1 -> 10L, tokenId2 -> 20L),
    Map(ErgoBox.R4 -> IntConstant(100), ErgoBox.R5 -> BooleanConstant(true)))

  val dataBox = createBox(1000, TrivialProp.TrueProp,
    Seq(tokenId1 -> 10L, tokenId2 -> 20L),
    Map(ErgoBox.R4 -> IntConstant(100), ErgoBox.R5 -> BooleanConstant(true)))

  val header: Header = CHeader(0,
    Blake2b256("parentId").toColl,
    Blake2b256("ADProofsRoot").toColl,
    sampleAvlTree,
    Blake2b256("transactionsRoot").toColl,
    timestamp = 0,
    nBits = 0,
    height = 0,
    extensionRoot = Blake2b256("transactionsRoot").toColl,
    minerPk = SigmaDsl.groupGenerator,
    powOnetimePk = SigmaDsl.groupGenerator,
    powNonce = Colls.fromArray(Array[Byte](0, 1, 2, 3)),
    powDistance = SigmaDsl.BigInt(BigInteger.ONE),
    votes = Colls.emptyColl[Byte]
    )
  val headers = Colls.fromItems(header)
  val preHeader: PreHeader = null
  val ergoCtx = new ErgoLikeContext(
    currentHeight = 0,
    lastBlockUtxoRoot = AvlTreeData.dummy,
    dummyPubkey, boxesToSpend = IndexedSeq(box),
    spendingTransaction = null,
    self = box, headers = headers, preHeader = preHeader, dataInputs = IndexedSeq(dataBox),
    extension = ContextExtension(Map()))
  lazy val ctx = ergoCtx.toSigmaContext(IR, false)

  property("Box properties equivalence") {
    val box = ctx.dataInputs(0)
    checkEq(func[Box, Coll[Byte]]("{ (x: Box) => x.id }"))({ (x: Box) => x.id })(box)
    checkEq(func[Box, Long]("{ (x: Box) => x.value }"))({ (x: Box) => x.value })(box)
    checkEq(func[Box, Coll[Byte]]("{ (x: Box) => x.propositionBytes }"))({ (x: Box) => x.propositionBytes })(box)
    checkEq(func[Box, Coll[Byte]]("{ (x: Box) => x.bytes }"))({ (x: Box) => x.bytes })(box)
    checkEq(func[Box, Coll[Byte]]("{ (x: Box) => x.bytesWithoutRef }"))({ (x: Box) => x.bytesWithoutRef })(box)
    checkEq(func[Box, (Int, Coll[Byte])]("{ (x: Box) => x.creationInfo }"))({ (x: Box) => x.creationInfo })(box)
    checkEq(func[Box, Coll[(Coll[Byte], Long)]]("{ (x: Box) => x.tokens }"))({ (x: Box) => x.tokens })(box)
//    checkEq(func[Box, Coll[(Coll[Byte], Long)]]("{ (x: Box) => x.registers }"))({ (x: Box) => x.registers })(box)
  }

  property("Context properties equivalence") {
    checkEq(func[Context, Coll[Box]]("{ (x: Context) => x.dataInputs }"))({ (x: Context) => x.dataInputs })(ctx)
    checkEq(func[Context, Box]("{ (x: Context) => x.dataInputs(0) }"))({ (x: Context) => x.dataInputs(0) })(ctx)
    checkEq(func[Context, Coll[Byte]]("{ (x: Context) => x.dataInputs(0).id }"))({ (x: Context) => x.dataInputs(0).id })(ctx)
  }

}
