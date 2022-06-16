package sigmastate

import Values._
import org.ergoplatform.{Context, Global, Inputs, Height, LastBlockUtxoRootHash, MinerPubkey, Outputs, Self}
import org.typelevel.paiges.Doc
import sigmastate.serialization.OpCodes
import sigmastate.utxo._
import sigmastate.lang.Terms.{Apply, ApplyTypes, Block, Ident, Lambda, MethodCall, MethodCallLike, Select, ValNode}

/**
 * TODO: Docs - arguments/values naming $i
 * Brainstorm ideas: create configuration for printer (explicit types, curly/normal brackets, `to{$tpe}` explicit casting)
 */
object PrettyPrintErgoTree {

  def prettyPrint(t: SValue, width: Int = 80): String = createDoc(t).render(width)

  private def createDoc(t: SValue): Doc = t match {
    // Values
    case ev: EvaluatedValue[SType] => ev match {
      case c: Constant[SType] => c match {
        case FalseLeaf => Doc.text("false")
        case TrueLeaf => Doc.text("true")
        case ConstantNode(value, tpe) => Doc.text(s"$value.to") + STypeDoc(tpe)
      }
      case UnitConstant() => ??? // TODO: Only used in parser?
      case GroupGenerator => ??? // TODO: What is that?
      case ec: EvaluatedCollection[_, _] => ec match {
        case ConcreteCollection(items, elemType) =>
          Doc.text(s"Coll") + wrapWithBrackets(STypeDoc(elemType)) + nTupleDoc(items.map(createDoc))
      }
    }
    case bi: BlockItem => bi match {
      case ValDef(id, _, rhs) => Doc.text(s"val $$$id = ") + createDoc(rhs)
    } 
    case Tuple(items) => nTupleDoc(items.map(createDoc))
    case ValUse(id, tpe) => Doc.text(s"$$$id")
    case ConstantPlaceholder(id, tpe) => ??? // TODO: See unfinished test
    case TaggedVariableNode(varId, tpe) => ??? // TODO: Does not make sense for printer?
    case FalseSigmaProp | TrueSigmaProp => ??? // TODO: Does not make sense for printer?
    case FuncValue(args, body) =>
      Doc.char('{') + Doc.space + argsWithTypesDoc(args) + Doc.space + Doc.text("=>") +
      createDoc(body).bracketBy(Doc.empty, Doc.empty) + Doc.char('}')
    case BlockValue(items, result) =>
      val prettyItems = items.map(item => createDoc(item))
      Doc.intercalate(Doc.lineOr(Doc.text("; ")), prettyItems).tightBracketBy(Doc.empty, Doc.text("; ")) +
      createDoc(result)
    case SomeValue(_) | NoneValue(_) => ??? // Not implemented in ErgoTree (as of v5.0)

    // ErgoLike
    case Height => Doc.text("HEIGHT")
    case Inputs => Doc.text("INPUTS")
    case Outputs => Doc.text("OUTPUTS")
    case Self => Doc.text("SELF")
    case Context => Doc.text("CONTEXT")
    case Global => Doc.text("Global")
    // TODO: Not possible to reach following 2 cases until https://github.com/ScorexFoundation/sigmastate-interpreter/issues/799
    case MinerPubkey => Doc.text("minerPubKey")
    case LastBlockUtxoRootHash => Doc.text("LastBlockUtxoRootHash") 

    // Transformers
    case MapCollection(input, mapper) => methodDoc(input, "map", List(mapper))
    case Append(coll1, coll2) => methodDoc(coll1, "append", List(coll2))
    case Slice(input, from, until) => methodDoc(input, "slice", List(from, until))
    case Filter(input, condition) => methodDoc(input, "filter", List(condition))
    case bt: BooleanTransformer[_] => bt match {
      case Exists(input, condition) => methodDoc(input, "exists", List(condition))
      case ForAll(input, condition) => methodDoc(input, "forall", List(condition))
    }
    case Fold(input, zero, foldOp) => methodDoc(input, "fold", List(zero, foldOp))
    case SelectField(input, idx) => methodDoc(input, s"_$idx")
    case ByIndex(input, index, defaultIndex) =>
      defaultIndex match {
        case Some(v) => methodDoc(input, "getOrElse", List(index, v))
        case None => createDoc(input) + wrapWithParens(createDoc(index))
      }
    // TODO: Not used in final ErgoTree representation.
    case SigmaPropIsProven(input) => ???
    case SigmaPropBytes(input) => methodDoc(input, "propBytes")
    case SizeOf(input) => methodDoc(input, "size")
    case ExtractAmount(input) => methodDoc(input, "value")
    case ExtractScriptBytes(input) => methodDoc(input, "propositionBytes")
    case ExtractBytes(input) => methodDoc(input, "bytes")
    case ExtractBytesWithNoRef(input) => methodDoc(input, "bytesWithoutRef")
    case ExtractId(input) => methodDoc(input, "id")
    // TODO: Check what is returned inside elemType when maybeTpe is None?
    case ExtractRegisterAs(input, registerId, maybeTpe) =>
      createDoc(input) + Doc.text(s".$registerId") + wrapWithBrackets(STypeDoc(maybeTpe.elemType))
    case ExtractCreationInfo(input) => methodDoc(input, "creationInfo")
    // TODO: Can be part of final ErgoTree?
    case d: Deserialize[_] => d match {
      case DeserializeContext(id, tpe) => ???
      case DeserializeRegister(reg, tpe, default) => ???
    }
    // TODO: Check how to handle None inside maybeTpe
    case GetVar(varId, maybeTpe) =>
      Doc.text("getVar") + wrapWithBrackets(STypeDoc(maybeTpe.elemType)) + wrapWithParens(createDoc(varId))
    case OptionGet(input) => methodDoc(input, "get")
    case OptionGetOrElse(input, default) => methodDoc(input, "getOrElse", List(default))
    case OptionIsDefined(x) => methodDoc(x, "isDefined")

    // Terms
    // Following nodes are not part of final ErgoTree
    case Block(_, _) => ???
    case ValNode(_, _, _) => ???
    case Select(_, _, _) => ???
    case Ident(_, _) => ???
    case ApplyTypes(_, _) => ???
    case MethodCallLike(_, _, _, _) => ???
    case Lambda(_, _, _, _) => ???

    case Apply(func, args) => createDoc(func) + nTupleDoc(args.map(createDoc))
    case MethodCall(obj, method, args, map) => methodDoc(obj, method.name, args.toList)

    // Trees
    case BoolToSigmaProp(value) => Doc.text("sigmaProp") + wrapWithParens(createDoc(value))
    case CreateProveDlog(value) => Doc.text("proveDlog") + wrapWithParens(createDoc(value))
    // TODO: Can be removed as it isn't used anymore.
    case CreateAvlTree(_, _, _, _) => ???
    case CreateProveDHTuple(gv, hv, uv, vv) => Doc.text("proveDHTuple") + nTupleDoc(List(gv, hv, uv, vv).map(createDoc))
    case st: SigmaTransformer[_, _] => st match {
      // TODO: Test with more than 2 elements inside items
      case SigmaAnd(items) => Doc.intercalate(Doc.text(" && "), items.map(createDoc))
      case SigmaOr(items) => Doc.intercalate(Doc.text(" || "), items.map(createDoc))
    }
    case OR(input) => Doc.text("anyOf") + wrapWithParens(createDoc(input))
    case XorOf(input) => Doc.text("xorOf") + wrapWithParens(createDoc(input))
    case AND(input) => Doc.text("allOf") + wrapWithParens(createDoc(input))
    case AtLeast(bound, input) => Doc.text("atLeast") + nTupleDoc(List(bound, input).map(createDoc))
    case Upcast(input, tpe) => createDoc(input) + Doc.text(".to") + STypeDoc(tpe)
    case Downcast(input, tpe) => createDoc(input) + Doc.text(".to") + STypeDoc(tpe)
    case LongToByteArray(input) => Doc.text("longToByteArray") + wrapWithParens(createDoc(input))
    case ByteArrayToLong(input) => Doc.text("byteArrayToLong") + wrapWithParens(createDoc(input))
    case ByteArrayToBigInt(input) => Doc.text("byteArrayToBigInt") + wrapWithParens(createDoc(input))
    case nr: NotReadyValueGroupElement => nr match {
      case DecodePoint(input) => Doc.text("decodePoint") + wrapWithParens(createDoc(input))
      case Exponentiate(l, r) => methodDoc(l, "exp", List(r))
      case MultiplyGroup(l, r) => methodDoc(l, "multiply", List(r))
    }
    case ch: CalcHash => ch match {
      case CalcBlake2b256(input) => Doc.text("blake2b256") + wrapWithParens(createDoc(input))
      case CalcSha256(input) => Doc.text("sha256") + wrapWithParens(createDoc(input))
    }
    case SubstConstants(scriptBytes, positions, newValues) =>
      Doc.text("substConstants") + nTupleDoc(List(scriptBytes, positions, newValues).map(createDoc))

    case t: Triple[_, _, _] => t match {
      case ArithOp(l, r, OpCodes.PlusCode) => createDoc(l) + Doc.text(" + ") + createDoc(r)
      case ArithOp(l, r, OpCodes.MinusCode) => createDoc(l) + Doc.text(" - ") + createDoc(r)
      case ArithOp(l, r, OpCodes.MultiplyCode) => createDoc(l) + Doc.text(" * ") + createDoc(r)
      case ArithOp(l, r, OpCodes.DivisionCode) => createDoc(l) + Doc.text(" / ") + createDoc(r)
      case ArithOp(l, r, OpCodes.ModuloCode) => createDoc(l) + Doc.text(" % ") + createDoc(r)
      // TODO: min/max usage in script code
      case ArithOp(l, r, OpCodes.MinCode) => ???
      case ArithOp(l, r, OpCodes.MaxCode) => ???
      case ArithOp(l, r, _) => ???
      // TODO: Implement after https://github.com/ScorexFoundation/sigmastate-interpreter/issues/474
      case BitOp(l, r, OpCodes.BitOrCode) => binaryOperationWithParens(createDoc(l), createDoc(r), Doc.char('|'))
      case BitOp(l, r, OpCodes.BitAndCode) => binaryOperationWithParens(createDoc(l), createDoc(r), Doc.char('&'))
      // Possible xor clash with BinXor?
      case BitOp(_, _, _) => ???
      case Xor(l, r) => Doc.text("xor") + nTupleDoc(List(l, r).map(createDoc))
      case sr: SimpleRelation[_] => sr match {
        case GT(l, r) => binaryOperationWithParens(createDoc(l), createDoc(r), Doc.text(" > "))
        case GE(l, r) => binaryOperationWithParens(createDoc(l), createDoc(r), Doc.text(" >= "))
        case EQ(l, r) => binaryOperationWithParens(createDoc(l), createDoc(r), Doc.text(" == "))
        case NEQ(l, r) => binaryOperationWithParens(createDoc(l), createDoc(r), Doc.text(" != "))
        case LT(l, r) => binaryOperationWithParens(createDoc(l), createDoc(r), Doc.text(" < "))
        case LE(l, r) => binaryOperationWithParens(createDoc(l), createDoc(r), Doc.text(" <= "))
      }
      case BinOr(l, r) => binaryOperationWithParens(createDoc(l), createDoc(r), Doc.text(" || "))
      case BinAnd(l, r) => binaryOperationWithParens(createDoc(l), createDoc(r), Doc.text(" && "))
      case BinXor(l, r) => binaryOperationWithParens(createDoc(l), createDoc(r), Doc.text(" ^ "))
    }
    case oneArgOp: OneArgumentOperation[_, _] => oneArgOp match {
      // TODO: Only used in parser?
      case BitInversion(input) => Doc.char('~') + createDoc(input)
      case Negation(input) => Doc.char('!') + createDoc(input)
    }
    // TODO: How are ModQs used in ergo script?
    case ModQ(input) => ???
    case ModQArithOp(l, r, opCode) => ???

    case quad: Quadruple[_, _, _, _] => quad match {
      case If(condition, trueBranch, falseBranch) =>
        val res = Doc.text("if (") + createDoc(condition) + Doc.text(") {") +
          createDoc(trueBranch).bracketBy(Doc.empty, Doc.empty) +
          Doc.text("} else {") +
          createDoc(falseBranch).bracketBy(Doc.empty, Doc.empty) +
          Doc.char('}')
        res.tightBracketBy(Doc.empty, Doc.empty)
      // TODO: How it can be used in ergo script?
      case TreeLookup(tree, key, proof) => ???
    }
    case LogicalNot(input) => Doc.char('!') + createDoc(input)
  }

  // empty args list returns just `name` suffix
  private def methodDoc(input: SValue, name: String, args: List[SValue] = Nil): Doc = {
    val argsDoc = if (args.nonEmpty) nTupleDoc(args.map(createDoc)) else Doc.empty
    createDoc(input) + Doc.text(s".$name") + argsDoc
  }

  private def binaryOperationWithParens(l: Doc, r: Doc, sep: Doc) = wrapWithParens(l) + sep + wrapWithParens(r)
  private def wrapWithParens(d: Doc): Doc = d.tightBracketBy(Doc.char('('), Doc.char(')'))
  private def wrapWithBrackets(d: Doc): Doc = d.tightBracketBy(Doc.char('['), Doc.char(']'))

  /* Create argument representation enclosed in brackets with types, e.g. `($5: String, $1: Int)` */
  private def argsWithTypesDoc(args: Seq[(Int, SType)]): Doc = {
    val argsWithTypes = args.map { case (i, tpe) => Doc.text(s"$$$i:") + Doc.space + STypeDoc(tpe) }
    nTupleDoc(argsWithTypes)
  }

  private def STypeDoc(tpe: SType): Doc = tpe match {
    case SBoolean => Doc.text("Boolean")
    case SByte => Doc.text("Byte")
    case SShort => Doc.text("Short")
    case SInt => Doc.text("Int")
    case SLong => Doc.text("Long")
    case SBigInt => Doc.text("BigInt")
    case SBox => Doc.text("Box")
    case SSigmaProp => Doc.text("SigmaProp")
    case SCollectionType(elemType) => Doc.text("Coll") + wrapWithBrackets(STypeDoc(elemType))
    case STuple(items) => nTupleDoc(items.map(STypeDoc))
    case SContext => Doc.text("Context")
    case SOption(elemType) => Doc.text("Option") + wrapWithBrackets(STypeDoc(elemType))
    case SGroupElement => Doc.text("GroupElement")
    case SPreHeader => Doc.text("PreHeader")
    // TODO: Not tested
    case NoType => Doc.empty
    case SString => Doc.text("String")
    case SAny => Doc.text("Any")
    case SUnit => Doc.text("Unit")
    case SFunc(tDom, tRange, tpeParams) => nTupleDoc(tDom.map(STypeDoc)) + Doc.text(" => ") + STypeDoc(tRange)
    case SAvlTree => Doc.text("AvlTree")
    case SHeader => Doc.text("Header")
    case SGlobal => Doc.text("Global")
    // Not used in final ergo tree
    case STypeApply(name, args) => ???
    case STypeVar(name) => ???
  }

  // Create (item1, item2, ...)
  private def nTupleDoc(items: Seq[Doc]): Doc =
    wrapWithParens(
      Doc.intercalate(Doc.text(", "), items))

}