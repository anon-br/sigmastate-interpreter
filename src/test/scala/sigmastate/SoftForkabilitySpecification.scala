package sigmastate

import org.ergoplatform.ValidationRules.CheckValidOpCode
import org.ergoplatform._
import sigmastate.Values.{NotReadyValueInt, IntConstant, ErgoTree}
import sigmastate.helpers.{ErgoLikeTestProvingInterpreter, ErgoLikeTestInterpreter}
import sigmastate.interpreter.Interpreter
import sigmastate.interpreter.Interpreter.ScriptNameProp
import sigmastate.serialization._
import sigmastate.lang.Terms._
import sigmastate.lang.exceptions.{SerializerException, InvalidOpCode}
import sigmastate.serialization.OpCodes.{HeightCode, LastConstantCode, OpCode}
import special.sigma.SigmaTestingData

class SoftForkabilitySpecification extends SigmaTestingData {

  implicit lazy val IR = new TestingIRContext
  lazy val prover = new ErgoLikeTestProvingInterpreter()
  lazy val verifier = new ErgoLikeTestInterpreter
  lazy val invalidPropV1 = compile(Interpreter.emptyEnv,
    """{
     |  HEIGHT > 100 && OUTPUTS.size == 1
     |}""".stripMargin).asBoolValue
  lazy val invalidTxV1 = createTransaction(createBox(100, invalidPropV1.asSigmaProp, 1))
  lazy val invalidTxV1bytes = invalidTxV1.messageToSign

  lazy val propV1 = invalidPropV1.toSigmaProp
  lazy val txV1 = createTransaction(createBox(100, propV1, 1))
  lazy val txV1bytes = txV1.messageToSign

  def createContext(h: Int, tx: ErgoLikeTransaction) =
    ErgoLikeContext(h,
      AvlTreeData.dummy, ErgoLikeContext.dummyPubkey, IndexedSeq(fakeSelf),
      tx, fakeSelf)

  def verifyTx(name: String, tx: ErgoLikeTransaction) = {
    val env = Map(ScriptNameProp -> name)
    val ctx = createContext(110, tx)
    val prop = tx.outputs(0).ergoTree
    val proof1 = prover.prove(env, prop, ctx, fakeMessage).get.proof
    verifier.verify(env, prop, ctx, proof1, fakeMessage).map(_._1).getOrElse(false) shouldBe true
  }

  def checkTxProp[T <: ErgoLikeTransaction, R](tx1: T, tx2: T)(p: T => R) = {
    p(tx1) shouldBe p(tx2)
  }
  
  property("node v1, received tx with script v1, incorrect script") {
    an[SerializerException] should be thrownBy {
      // CheckDeserializedScriptIsSigmaProp rule violated
      ErgoLikeTransaction.serializer.parse(SigmaSerializer.startReader(invalidTxV1bytes))
    }
  }

  property("node v1, received tx with script v1, correct script") {
    // able to parse
    val tx = ErgoLikeTransaction.serializer.parse(SigmaSerializer.startReader(txV1bytes))

    // validating script
    verifyTx("propV1", tx)
  }

  val Height2Code = (LastConstantCode + 56).toByte
  /** Same as Height, but new opcode to test soft-fork */
  case object Height2 extends NotReadyValueInt {
    override val opCode: OpCode = Height2Code // use reserved code
    def opType = SFunc(SContext, SInt)
  }
  val Height2Ser = CaseObjectSerialization(Height2Code, Height2)

  lazy val prop = GT(Height2, IntConstant(100))
  lazy val invalidTxV2 = createTransaction(createBox(100, prop.asSigmaProp, 1))
  lazy val invalidTxV2bytes = invalidTxV2.messageToSign

  lazy val propV2 = prop.toSigmaProp

  property("node v1, soft-fork up to v2, script v2 without size") {
    // prepare bytes using default serialization without `size bit` in the header
    ValueSerializer.addSerializer(Height2Code, Height2Ser)
    val txV2_withoutSize = createTransaction(createBox(100, ErgoTree.fromProposition(propV2), 1))
    val txV2_withoutSize_bytes = txV2_withoutSize.messageToSign
    ValueSerializer.removeSerializer(Height2Code)

    // prepare soft-fork settings
    val v2vs = vs.updated(CheckValidOpCode.id, ChangedRule(Array(Height2Code)))

    // should fail with given exceptions
    assertExceptionThrown(
      {
        val r = SigmaSerializer.startReader(txV2_withoutSize_bytes)(v2vs)
        ErgoLikeTransaction.serializer.parse(r)
      },
      {
        case e: SerializerException => e.getCause.isInstanceOf[ChangedRuleException]
        case _ => false
      }
    )
  }

  property("node v1, soft-fork up to v2, script v2 with `size bit`") {
    // prepare bytes using special serialization WITH `size flag` in the header
    ValueSerializer.addSerializer(Height2Code, Height2Ser)
    val tree = ErgoTree.withSegregation(ErgoTree.SizeFlag,  propV2)
    val txV2 = createTransaction(createBox(100, tree, 1))
    val txV2bytes = txV2.messageToSign
    ValueSerializer.removeSerializer(Height2Code)

    // prepare soft-fork settings
    val v2vs = vs.updated(CheckValidOpCode.id, ChangedRule(Array(Height2Code)))

    // parse and validate tx
    val tx = ErgoLikeTransaction.serializer.parse(SigmaSerializer.startReader(txV2bytes)(v2vs))
    verifyTx("propV2", tx)

    // also check that transaction prop was trivialized due to soft-fork
    tx.outputs(0).ergoTree shouldBe ErgoTree.fromSigmaBoolean(TrivialProp.TrueProp)

    // check deserialized tx is otherwise remains the same
    checkTxProp(txV2, tx)(_.inputs)
    checkTxProp(txV2, tx)(_.dataInputs)
    checkTxProp(txV2, tx)(_.outputs.length)
    checkTxProp(txV2, tx)(_.outputs(0).creationHeight)
    checkTxProp(txV2, tx)(_.outputs(0).value)
    checkTxProp(txV2, tx)(_.outputs(0).additionalTokens)
  }

  property("node v1, soft-fork up to v2, script v2, soft-fork exception") {
    // try to parse
    // handle soft-fork exception, skip validation
  }

  property("node v1, no soft-fork, received script v2, raise error") {
    an[InvalidOpCode] should be thrownBy {
      ErgoLikeTransaction.serializer.parse(SigmaSerializer.startReader(invalidTxV2bytes))
    }
  }

  property("our node v2, was soft-fork up to v2, received script v2") {
    // able to parse
    // validating script
  }

}
