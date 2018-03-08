package sigmastate.interpreter

import org.bitbucket.inkytonik.kiama.attribution.AttributionCore
import org.bitbucket.inkytonik.kiama.relation.Tree
import scapi.sigma.SigmaProtocolPrivateInput
import scapi.sigma.DLogProtocol._
import sigmastate._
import sigmastate.utils.Helpers

import scala.util.Try
import org.bitbucket.inkytonik.kiama.rewriting.Rewriter.{everywherebu, everywheretd, rule}
import org.bitbucket.inkytonik.kiama.rewriting.Strategy
import scapi.sigma._
import scorex.crypto.hash.Blake2b256
import scorex.utils.Random

/**
  * Proof generated by a prover along with possible context extensions
  */
case class ProverResult[ProofT <: UncheckedTree](proof: ProofT, extension: ContextExtension)

/**
  * Interpreter with enhanced functionality to prove statements.
  *
  */
trait ProverInterpreter extends Interpreter with AttributionCore {
  override type ProofT = UncheckedTree

  val secrets: Seq[SigmaProtocolPrivateInput[_, _]]

  val contextExtenders: Map[Byte, EvaluatedValue[_ <: SType]]

  val knownExtensions = ContextExtension(contextExtenders)

  def enrichContext(tree: Value[SBoolean.type]): ContextExtension = {
    val targetName = TaggedByteArray.getClass.getSimpleName.replace("$", "")

    val ce: Map[Byte, EvaluatedValue[_ <: SType]] = new Tree(tree).nodes.flatMap { n =>
      if (n.productPrefix == targetName) {
        val tag = n.productIterator.next().asInstanceOf[Byte]
        contextExtenders.get(tag).map(v => tag -> v)
      } else None
    }.toMap

    ContextExtension(ce)
  }

  /**
    * "Prover steps:
    * *
    * (markSimulated)
    *1. bottom-up: mark every node real or simulated, according to the following rule. DLogNode -- you know the DL,
    * then real, else simulated. COR: if at least one child real, then real; else simulated. CAND: if at least one child
    * simulated, then simulated; else real. Note that all descendants of a simulated node will be later simulated, even
    * if they were marked as real. This is what the next step will do.
    * *
    * Root should end up real according to this rule -- else you won't be able to carry out the proof in the end.
    * *
    * (polishSimulated)
    *2. top-down: mark every child of a simulated node "simulated." If two or more more children of a real COR are real,
    * mark all but one simulated.
    * *
    * (challengeSimulated)
    *3. top-down: compute a challenge for every simulated child of every COR and CAND, according to the following rules.
    * If COR, then every simulated child gets a fresh random challenge. If CAND (which means CAND itself is simulated, and
    * all its children are), then every child gets the same challenge as the CAND.
    * *
    * (simulations)
    *4. bottom-up: For every simulated leaf, simulate a response and a commitment (i.e., second and first prover message)
    * according to the Schnorr simulator. For every real leaf, compute the commitment (i.e., first prover message) according
    * to the Schnorr protocol. For every COR/CAND node, let the commitment be the union (as a set) of commitments below it.
    * *
    *5. Compute the Schnorr challenge as the hash of the commitment of the root (plus other inputs -- probably the tree
    * being proven and the message).
    * *
    * (challengesReal, proving)
    *6. top-down: compute the challenge for every real child of every real COR and CAND, as follows. If COR, then the
    * challenge for the one real child of COR is equal to the XOR of the challenge of COR and the challenges for all the
    * simulated children of COR. If CAND, then the challenge for every real child of CAND is equal to the the challenge of
    * the CAND. Note that simulated CAND and COR have only simulated descendants, so no need to recurse down from them."
    *
    */

  protected def prove(unprovenTree: UnprovenTree, message: Array[Byte]): ProofT = {
    val step1 = markSimulated(unprovenTree).get.asInstanceOf[UnprovenTree]
    assert(step1.real)
    val step2 = polishSimulated(step1).get.asInstanceOf[UnprovenTree]
    val step3 = challengeSimulated(step2).get.asInstanceOf[UnprovenTree]
    val step4 = simulations(step3).get.asInstanceOf[UnprovenTree]

    //step 5 - compute root challenge
    val commitments = step4 match {
      case ul: UnprovenLeaf => ul.commitmentOpt.toSeq
      case uc: UnprovenConjecture => uc.childrenCommitments
    }

    val rootChallenge = Blake2b256(commitments.map(_.bytes).reduce(_ ++ _) ++ message)

    val step5 = step4.withChallenge(rootChallenge)

    val step6 = proving(step5).get.asInstanceOf[ProofTree]

    convertToUnchecked(step6)
  }

  def prove(exp: Value[SBoolean.type], context: CTX, message: Array[Byte]): Try[ProverResult[ProofT]] = Try {
    val reducedProp = reduceToCrypto(exp, context.withExtension(knownExtensions)).get

    ProverResult(reducedProp match {
      case bool: BooleanConstant =>
        bool match {
          case TrueLeaf => NoProof
          case FalseLeaf => ???
        }
      case _ =>
        val ct = convertToUnproven(reducedProp.asInstanceOf[SigmaBoolean])
        prove(ct, message)
    }, knownExtensions)
  }

  /**
    * 1. bottom-up: mark every node real or simulated, according to the following rule. DLogNode -- you know the DL,
    * then real, else simulated. COR: if at least one child real, then real; else simulated. CAND: if at least one child
    * simulated, then simulated; else real. Note that all descendants of a simulated node will be later simulated, even
    * if they were marked as real. This is what the next step will do.
    */
  val markSimulated: Strategy = everywherebu(rule[UnprovenTree] {
    case and: CAndUnproven =>
      val simulated = and.children.exists(_.asInstanceOf[UnprovenTree].simulated)
      and.copy(simulated = simulated)
    case or: COrUnproven =>
      val simulated = or.children.forall(_.asInstanceOf[UnprovenTree].simulated)
      or.copy(simulated = simulated)
    case su: SchnorrUnproven =>
      val secretKnown = secrets
        .filter(_.isInstanceOf[DLogProverInput])
        .exists(_.asInstanceOf[DLogProverInput].publicImage == su.proposition)
      su.copy(simulated = !secretKnown)
    case dhu: DiffieHellmanTupleUnproven =>
      val secretKnown = secrets
        .filter(_.isInstanceOf[DiffieHellmanTupleProverInput])
        .exists(_.asInstanceOf[DiffieHellmanTupleProverInput].publicImage == dhu.proposition)
      dhu.copy(simulated = !secretKnown)
    case _ => ???
  })

  /**
    * 2. top-down: mark every child of a simulated node "simulated." If two or more children of a real COR
    * are real, mark all but one simulated.
    */

  val polishSimulated: Strategy = everywheretd(rule[UnprovenTree] {
    case and: CAndUnproven =>
      if (and.simulated) and.copy(children = and.children.map(_.asInstanceOf[UnprovenTree].withSimulated(true)))
      else and
    case or: COrUnproven =>
      if (or.simulated) {
        or.copy(children = or.children.map(_.asInstanceOf[UnprovenTree].withSimulated(true)))
      } else {
        val newChildren = or.children.foldLeft((Seq[UnprovenTree](), false)) { case ((children, realFound), child) =>
          val cut = child.asInstanceOf[UnprovenTree]
          (realFound, cut.real) match {
            case (true, true) => (children :+ cut.withSimulated(true), true)
            case (true, false) => (children :+ cut, true)
            case (false, true) => (children :+ cut, true)
            case (false, false) => (children :+ cut, false)
          }
        }._1
        or.copy(children = newChildren)
      }
    case su: SchnorrUnproven => su
    case dhu: DiffieHellmanTupleUnproven => dhu
    case _ => ???
  })

  /**
    * 3. top-down: compute a challenge for every simulated child of every COR and CAND, according to the following rules.
    * If COR, then every simulated child gets a fresh random challenge. If CAND (which means CAND itself is simulated, and
    * all its children are), then every child gets the same challenge as the CAND.
    */
  val challengeSimulated: Strategy = everywheretd(rule[UnprovenTree] {
    case and: CAndUnproven if and.simulated =>
      assert(and.challengeOpt.isDefined)
      val challenge = and.challengeOpt.get
      and.copy(children = and.children.map(_.asInstanceOf[UnprovenTree].withChallenge(challenge)))

    case and: CAndUnproven if and.real => and

    case or: COrUnproven if or.real =>
      or.copy(children = or.children.map(c => c.asInstanceOf[UnprovenTree].real match {
        case true => c
        case false => c.asInstanceOf[UnprovenTree].withChallenge(Random.randomBytes())
      }))

    case or: COrUnproven if or.simulated =>
      assert(or.challengeOpt.isDefined)

      val t = or.children.tail.map(_.asInstanceOf[UnprovenTree].withChallenge(Random.randomBytes()))
      val toXor: Seq[Array[Byte]] = or.challengeOpt.get +: t.map(_.challengeOpt.get)
      val h = or.children.head.asInstanceOf[UnprovenTree]
        .withChallenge(Helpers.xor(toXor: _*))
      or.copy(children = h +: t)

    case su: SchnorrUnproven => su

    case dhu: DiffieHellmanTupleUnproven => dhu

    case a: Any => println(a); ???
  })

  /**
    * 4. bottom-up: For every simulated leaf, simulate a response and a commitment (i.e., second and first prover
    * message) according to the Schnorr simulator. For every real leaf, compute the commitment (i.e., first prover
    * message) according to the Schnorr protocol. For every COR/CAND node, let the commitment be the union (as a set)
    * of commitments below it.
    */
  val simulations: Strategy = everywherebu(rule[ProofTree] {
    case and: CAndUnproven =>
      val commitments = and.children.flatMap {
        case ul: UnprovenLeaf => ul.commitmentOpt.toSeq
        case uc: UnprovenConjecture => uc.childrenCommitments
        case sn: SchnorrNode => sn.firstMessageOpt.toSeq
        case dh: DiffieHellmanTupleUncheckedNode => dh.firstMessageOpt.toSeq
        case _ => ???
      }
      and.copy(childrenCommitments = commitments)

    case or: COrUnproven =>
      val commitments = or.children.flatMap {
        case ul: UnprovenLeaf => ul.commitmentOpt.toSeq
        case uc: UnprovenConjecture => uc.childrenCommitments
        case sn: SchnorrNode => sn.firstMessageOpt.toSeq
        case dh: DiffieHellmanTupleUncheckedNode => dh.firstMessageOpt.toSeq
        case a: Any => ???
      }
      or.copy(childrenCommitments = commitments)

    case su: SchnorrUnproven =>
      if (su.simulated) {
        assert(su.challengeOpt.isDefined)
        SchnorrSigner(su.proposition, None).prove(su.challengeOpt.get)
      } else {
        val (r, commitment) = DLogInteractiveProver.firstMessage(su.proposition)
        su.copy(commitmentOpt = Some(commitment), randomnessOpt = Some(r))
      }

    case dhu: DiffieHellmanTupleUnproven =>
      if (dhu.simulated) {
        assert(dhu.challengeOpt.isDefined)
        val prover = new DiffieHellmanTupleInteractiveProver(dhu.proposition, None)
        val (fm, sm) = prover.simulate(Challenge(dhu.challengeOpt.get))
        DiffieHellmanTupleUncheckedNode(dhu.proposition, Some(fm), dhu.challengeOpt.get, sm)
      } else {
        val (r, fm) = DiffieHellmanTupleInteractiveProver.firstMessage(dhu.proposition)
        dhu.copy(commitmentOpt = Some(fm), randomnessOpt = Some(r))
      }

    case _ => ???
  })

  def extractChallenge(pt: ProofTree): Option[Array[Byte]] = pt match {
    case upt: UnprovenTree => upt.challengeOpt
    case sn: SchnorrNode => Some(sn.challenge)
    case dh: DiffieHellmanTupleUncheckedNode => Some(dh.challenge)
    case _ => ???
  }

  /**
    * (proving)
    * 6. top-down: compute the challenge for every real child of every real COR and CAND, as follows. If COR, then the
    * challenge for the one real child of COR is equal to the XOR of the challenge of COR and the challenges for all the
    * simulated children of COR. If CAND, then the challenge for every real child of CAND is equal to the the challenge of
    * the CAND. Note that simulated CAND and COR have only simulated descendants, so no need to recurse down from them."
    **/
  val proving: Strategy = everywheretd(rule[ProofTree] {
    case and: CAndUnproven if and.real =>
      assert(and.challengeOpt.isDefined)
      val andChallenge = and.challengeOpt.get
      and.copy(children = and.children.map(_.asInstanceOf[UnprovenTree].withChallenge(andChallenge)))

    case or: COrUnproven if or.real =>
      assert(or.challengeOpt.isDefined)
      val rootChallenge = or.challengeOpt.get
      val challenge = Helpers.xor(rootChallenge +: or.children.flatMap(extractChallenge): _*)

      or.copy(children = or.children.map {
        case r: UnprovenTree if r.real => r.withChallenge(challenge)
        case p: ProofTree => p
      })

    case su: SchnorrUnproven if su.real =>
      assert(su.challengeOpt.isDefined)
      val privKey = secrets
        .filter(_.isInstanceOf[DLogProverInput])
        .find(_.asInstanceOf[DLogProverInput].publicImage == su.proposition)
        .get.asInstanceOf[DLogProverInput]
      val z = DLogInteractiveProver.secondMessage(privKey, su.randomnessOpt.get, Challenge(su.challengeOpt.get))
      SchnorrNode(su.proposition, None, su.challengeOpt.get, z)

    case dhu: DiffieHellmanTupleUnproven if dhu.real =>
      assert(dhu.challengeOpt.isDefined)
      val privKey = secrets
        .filter(_.isInstanceOf[DiffieHellmanTupleProverInput])
        .find(_.asInstanceOf[DiffieHellmanTupleProverInput].publicImage == dhu.proposition)
        .get.asInstanceOf[DiffieHellmanTupleProverInput]
      val z = DiffieHellmanTupleInteractiveProver.secondMessage(privKey, dhu.randomnessOpt.get, Challenge(dhu.challengeOpt.get))
      DiffieHellmanTupleUncheckedNode(dhu.proposition, None, dhu.challengeOpt.get, z)


    case sn: SchnorrNode => sn

    case dh: DiffieHellmanTupleUncheckedNode => dh

    case ut: UnprovenTree => ut

    case a: Any => println(a); ???
  })


  //converts SigmaTree => UnprovenTree
  val convertToUnproven: SigmaBoolean => UnprovenTree = attr {
    case CAND(sigmaTrees) =>
      CAndUnproven(CAND(sigmaTrees), Seq(), None, simulated = false, sigmaTrees.map(convertToUnproven))
    case COR(children) =>
      COrUnproven(COR(children), Seq(), None, simulated = false, children.map(convertToUnproven))
    case ci: ProveDlog =>
      SchnorrUnproven(ci, None, None, None, simulated = false)
    case dh: ProveDiffieHellmanTuple =>
      DiffieHellmanTupleUnproven(dh, None, None, None, simulated = false)
  }

  //converts ProofTree => UncheckedTree
  val convertToUnchecked: ProofTree => UncheckedTree = attr {
    case and: CAndUnproven =>
      CAndUncheckedNode(and.proposition, None, Seq(), and.children.map(convertToUnchecked))
    case or: COrUnproven =>
      COr2UncheckedNode(or.proposition, None, Seq(), or.children.map(convertToUnchecked))
    case s: SchnorrNode => s
    case d: DiffieHellmanTupleUncheckedNode => d
    case _ => ???
  }
}