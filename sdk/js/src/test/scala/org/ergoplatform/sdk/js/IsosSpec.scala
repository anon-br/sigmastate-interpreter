package org.ergoplatform.sdk.js

import org.ergoplatform.ErgoBox.{AdditionalRegisters, BoxId, TokenId}
import org.ergoplatform.sdk.{ExtendedInputBox, Iso}
import org.ergoplatform._
import org.ergoplatform.sdk.wallet.protocol.context.{CErgoLikeStateContext, ErgoLikeStateContext}
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import scorex.crypto.authds.ADDigest
import sigmastate.SType
import sigmastate.Values.Constant
import sigmastate.eval.Colls
import sigmastate.interpreter.ContextExtension
import sigmastate.serialization.generators.ObjectGenerators
import special.collection.Coll
import special.sigma
import special.sigma.GroupElement

import scala.scalajs.js

class IsosSpec  extends AnyPropSpec with Matchers with ObjectGenerators with ScalaCheckPropertyChecks{

  lazy val extendedInputBoxGen: Gen[ExtendedInputBox] = for {
    box <- ergoBoxGen
    extension <- contextExtensionGen
  } yield ExtendedInputBox(box, extension)

  lazy val ergoLikeStateContextGen: Gen[ErgoLikeStateContext] = for {
    stateRoot <- avlTreeGen
    headers <- headersGen(stateRoot)
    preHeader <- preHeaderGen(headers.headOption.map(_.id).getOrElse(modifierIdBytesGen.sample.get))
  } yield CErgoLikeStateContext(
      sigmaLastHeaders = Colls.fromItems(headers:_*),
      previousStateDigest = ADDigest @@ stateRoot.digest.toArray,
      sigmaPreHeader = preHeader
    )

  def roundtrip[A,B](iso: Iso[A,B])(b: B): Unit = {
    val invIso = iso.inverse
    invIso.from(invIso.to(b)) shouldBe b
  }

  override implicit val generatorDrivenConfig: PropertyCheckConfiguration = PropertyCheckConfiguration(minSuccessful = 30)

  property("Iso.isoStringToArray") {
    forAll() { (bytes: Array[Byte]) =>
      roundtrip(Isos.isoStringToArray)(bytes)
    }
  }

  property("Iso.isoStringToColl") {
    forAll() { (bytes: Coll[Byte]) =>
      roundtrip(Isos.isoStringToColl)(bytes)
    }
  }

  property("Iso.isoStringToGroupElement") {
    forAll() { (bytes: GroupElement) =>
      roundtrip(Isos.isoStringToGroupElement)(bytes)
    }
  }

  property("Iso.isoBoxId") {
    forAll(boxIdGen) { (id: BoxId) =>
      roundtrip(Isos.isoBoxId)(id)
    }
  }

  property("Iso.isoHexStringToConstant") {
    forAll(constantGen, MinSuccessful(100)) { (c: Constant[SType]) =>
      roundtrip(Isos.isoHexStringToConstant)(c)
    }
  }

  property("Iso.avlTree") {
    forAll { (c: sigma.AvlTree) =>
      roundtrip(Isos.isoAvlTree)(c)
    }
  }

  property("Iso.isoHeader") {
    forAll { (c: sigma.Header) =>
      roundtrip(Isos.isoHeader)(c)
    }
  }

  property("Iso.isoPreHeader") {
    forAll { (c: sigma.PreHeader) =>
      roundtrip(Isos.isoPreHeader)(c)
    }
  }

  property("Iso.isoBlockchainStateContext") {
    forAll(ergoLikeStateContextGen) { (c: ErgoLikeStateContext) =>
      roundtrip(Isos.isoBlockchainStateContext)(c)
    }
  }

  property("Iso.isoContextExtension") {
    forAll { (c: ContextExtension) =>
      roundtrip(Isos.isoContextExtension)(c)
    }
  }

  property("Iso.isoUnsignedInput") {
    forAll { (c: UnsignedInput) =>
      roundtrip(Isos.isoUnsignedInput)(c)
    }
  }

  property("Iso.isoDataInput") {
    forAll { (c: DataInput) =>
      roundtrip(Isos.isoDataInput)(c)
    }
  }

  property("Iso.isoBigInt") {
    forAll { (c: sigma.BigInt) =>
      roundtrip(Isos.isoBigInt)(c)
    }
  }

  property("Iso.isoBigIntToLong") {
    forAll { (c: Long) =>
      roundtrip(Isos.isoBigIntToLong)(c)
    }
  }

  property("Iso.isoAmount") {
    forAll { (c: Long) =>
      roundtrip(Isos.isoAmount)(c)
      Isos.isoAmount.to(js.BigInt(c.toString)) shouldBe c
    }
  }

  property("Iso.isoToken") {
    forAll(tokenIdGen, Arbitrary.arbLong.arbitrary) { (id: TokenId, amount: Long) =>
      roundtrip(Isos.isoToken)((id, amount))
    }
  }

  property("Iso.isoTokenArray") {
    forAll(ergoBoxTokens(tokensGen.sample.get)) { tokens =>
      roundtrip(Isos.isoTokenArray)(tokens)
    }
  }

  property("Iso.isoUndefOr") {
    forAll { opt: Option[Long] =>
      roundtrip(Isos.isoUndefOr(Iso.identityIso[Long]))(opt)
    }
  }

  property("Iso.isoNonMandatoryRegisters") {
    forAll(additionalRegistersGen) { (x: AdditionalRegisters) =>
      roundtrip(Isos.isoNonMandatoryRegisters)(x)
    }
  }

  property("Iso.isoBoxCandidate") {
    forAll { (box: ErgoBoxCandidate) =>
      roundtrip(Isos.isoBoxCandidate)(box)
    }
  }

  ignore("Iso.isoUnsignedTransaction") {
    forAll { (tx: UnsignedErgoLikeTransaction) =>
      roundtrip(Isos.isoUnsignedTransaction)(tx)
    }
  }

  property("Iso.isoBox") {
    forAll { (b: ErgoBox) =>
      roundtrip(Isos.isoBox)(b)
    }
  }

  property("Iso.isoEIP12UnsignedInput") {
    forAll(extendedInputBoxGen) { (b: ExtendedInputBox) =>
      roundtrip(Isos.isoEIP12UnsignedInput)(b)
    }
  }

}
