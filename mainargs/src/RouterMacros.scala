package mainargs
import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context

class RouterMacros(val c: Context) {
  def generateRoutesImpl[T: c.WeakTypeTag]: c.Expr[Seq[EntryPoint[T]]] = {
    import c.universe._
    val allRoutes = getAllRoutesForClass(weakTypeOf[T])

    c.Expr[Seq[EntryPoint[T]]](q"_root_.scala.Seq(..$allRoutes)")
  }
  def generateClassRouteImpl[T: c.WeakTypeTag, C: c.WeakTypeTag]: c.Expr[EntryPoint[C]] = {
    import c.universe._

    val cls = weakTypeOf[T].typeSymbol.asClass
    val companionObj = weakTypeOf[T].typeSymbol.companion
    val constructor = cls.primaryConstructor.asMethod
    val route = extractMethod(
      "apply",
      constructor.paramLists.flatten,
      constructor.pos,
      cls.annotations.find(_.tpe =:= typeOf[main]).head,
      companionObj.typeSignature
    )

    c.Expr[EntryPoint[C]](route.asInstanceOf[c.Tree])
  }
  import c.universe._
  def getValsOrMeths(curCls: Type): Iterable[MethodSymbol] = {
    def isAMemberOfAnyRef(member: Symbol) = {
      // AnyRef is an alias symbol, we go to the real "owner" of these methods
      val anyRefSym = c.mirror.universe.definitions.ObjectClass
      member.owner == anyRefSym
    }
    val extractableMembers = for {
      member <- curCls.members.toList.reverse
      if !isAMemberOfAnyRef(member)
      if !member.isSynthetic
      if member.isPublic
      if member.isTerm
      memTerm = member.asTerm
      if memTerm.isMethod
      if !memTerm.isModule
    } yield memTerm.asMethod

    extractableMembers flatMap { case memTerm =>
      if (memTerm.isSetter || memTerm.isConstructor || memTerm.isGetter) Nil
      else Seq(memTerm)
    }
  }

  def extractMethod(methodName: String,
                    flattenedArgLists: Seq[Symbol],
                    methodPos: Position,
                    mainAnnotation: Annotation,
                    curCls: c.universe.Type): c.universe.Tree = {

    val baseArgSym = TermName(c.freshName())

    def hasDefault(i: Int) = {
      val defaultName = s"${methodName}$$default$$${i + 1}"
      if (curCls.members.exists(_.name.toString == defaultName)) Some(defaultName)
      else None
    }

    val argListSymbol = q"${c.fresh[TermName](TermName("argsList"))}"
    val extrasSymbol = q"${c.fresh[TermName](TermName("extras"))}"
    val defaults = for ((arg, i) <- flattenedArgLists.zipWithIndex) yield {
      val arg = TermName(c.freshName())
      hasDefault(i).map(defaultName => q"($arg: $curCls) => $arg.${newTermName(defaultName)}")
    }

    def unwrapVarargType(arg: Symbol) = {
      val vararg = arg.typeSignature.typeSymbol == definitions.RepeatedParamClass
      val unwrappedType =
        if (!vararg) arg.typeSignature
        else arg.typeSignature.asInstanceOf[TypeRef].args(0)

      (vararg, unwrappedType)
    }



    val readArgSigs = for(
      ((arg, defaultOpt), i) <- flattenedArgLists.zip(defaults).zipWithIndex
    ) yield {

      val (vararg, varargUnwrappedType) = unwrapVarargType(arg)

      val default =
        if (vararg) q"scala.Some(scala.Nil)"
        else defaultOpt match {
          case Some(defaultExpr) => q"scala.Some($defaultExpr($baseArgSym))"
          case None => q"scala.None"
        }
      val argAnnotation = arg.annotations.find(_.tpe =:= typeOf[arg]).headOption




      val instantiateArg = argAnnotation match{
        case Some(annot) =>
          val annotArgs =
            for(t <- annot.tree.children.tail if t.symbol != null) {
//              c.internal.changeOwner(t, t.symbol.owner, meth.owner)
            }

          q"new ${annot.tree.tpe}(..${annot.tree.children.tail})"
        case _ => q"new _root_.mainargs.arg()"
      }
      val argVal = TermName(c.freshName("arg"))
      val argSigVal = TermName(c.freshName("argSig"))
      val argSig = q"""
        val $argSigVal = {
          val $argVal = $instantiateArg
          _root_.mainargs.ArgSig[$curCls](
            scala.Option($argVal.name).getOrElse(${arg.name.toString}),
            scala.Option($argVal.short),
            ${varargUnwrappedType.toString + (if(vararg) "*" else "")},
            scala.Option($argVal.doc),
            $defaultOpt,
            $vararg
          )
        }
      """

      val reader =
        if(vararg) q"""
          mainargs.Router.makeReadVarargsCall[$varargUnwrappedType](
            $argSigVal,
            $extrasSymbol
          )
        """ else q"""
          mainargs.Router.makeReadCall[$varargUnwrappedType](
            $argListSymbol,
            $default,
            $argSigVal
          )
        """
      c.internal.setPos(reader, methodPos)
      (reader, argSig, (argSigVal, vararg))
    }

    val (readArgs, argSigs, argSigValVarargs) = readArgSigs.unzip3
    val (argSigVals, varargs) = argSigValVarargs.unzip
    val (argNames, argNameCasts) = flattenedArgLists.map { arg =>
      val (vararg, unwrappedType) = unwrapVarargType(arg)
      (
        pq"${arg.name.toTermName}",
        if (!vararg) q"${arg.name.toTermName}.asInstanceOf[$unwrappedType]"
        else q"${arg.name.toTermName}.asInstanceOf[Seq[$unwrappedType]]: _*"

      )
    }.unzip


    val methVal = TermName(c.freshName("arg"))
    val res = q"""{
    val $methVal = new ${mainAnnotation.tree.tpe}(..${mainAnnotation.tree.children.tail})
    ..$argSigs
    mainargs.EntryPoint(
      scala.Option($methVal.name).getOrElse($methodName),
      scala.Seq(..$argSigVals),
      scala.Option($methVal.doc),
      ${varargs.contains(true)},
      ($baseArgSym: $curCls, $argListSymbol: Map[String, String], $extrasSymbol: Seq[String]) =>
        mainargs.Router.validate(Seq(..$readArgs)) match{
          case mainargs.Result.Success(List(..$argNames)) =>
            mainargs.Result.Success(
              $baseArgSym.${TermName(methodName)}(..$argNameCasts)
            )
          case x: mainargs.Result.Error => x
        }
    )
    }"""

//    println(showCode(res))
    res
  }

  def hasMainAnnotation(t: MethodSymbol) = t.annotations.exists(_.tpe =:= typeOf[main])
  def getAllRoutesForClass(curCls: Type,
                           pred: MethodSymbol => Boolean = hasMainAnnotation)
                            : Iterable[c.universe.Tree] = {
    for(t <- getValsOrMeths(curCls) if pred(t))
    yield {
      extractMethod(
        t.name.toString,
        t.paramss.flatten,
        t.pos,
        t.annotations.find(_.tpe =:= typeOf[main]).head,
        curCls
      )
    }
  }
}
