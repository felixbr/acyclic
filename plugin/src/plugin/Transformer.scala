package plugin

import tools.nsc.{Settings, Global}
import tools.nsc.plugins.PluginComponent
import tools.nsc.transform.{Transform, TypingTransformers}

import tools.nsc.symtab.Flags._
import tools.nsc.ast.TreeDSL
import tools.nsc.interpreter._
import com.sun.xml.internal.ws.api.model.wsdl.WSDLBoundOperation.ANONYMOUS

class Transformer(val plugin: SinjectPlugin)
extends PluginComponent
with Transform
with TypingTransformers
with TreeDSL{

  val global = plugin.global
  import global._

  val runsAfter = List("parser")
  override val runsRightAfter = Some("parser")

  val phaseName = "sinject"

  def newTransformer(unit: CompilationUnit) = {

    val unitInjections = unit.body.collect{
      case i @ Import(body, List(ImportSelector(name, _, _, _)))
        if name == newTermName("dynamic") =>

        body match {
          case Select(qualifier, treename) => Select(qualifier, treename.toTypeName) -> treename
          case Ident(treename) => Ident(treename.toTypeName) -> treename
        }
    }

    def makeValDefs(injections: List[(Tree, Name)], flags: Long) = for {
      ((inj, name), i) <- injections.zipWithIndex
    } yield ValDef(Modifiers(flags), newTermName(plugin.prefix + name), inj, EmptyTree)

    def makeDefDefs(injections: List[(Tree, Name)], flags: Long) = for {
      ((inj, name), i) <- injections.zipWithIndex
    } yield DefDef(Modifiers(flags), newTermName(plugin.prefix + name), List(), List(), inj, EmptyTree)

    def newConstrDefs(injections: List[(Tree, Name)]) = makeValDefs(injections, IMPLICIT | PARAMACCESSOR | PARAM)

    def constructorTransform(body: List[Tree], newConstrDefs: List[ValDef]): List[Tree] = body map {
      case dd @ DefDef(modifiers, name, tparams, vparamss, tpt, rhs)
        if name == newTermName("<init>") && newConstrDefs.length > 0 =>

        val newvparamss = enhanceVparamss(vparamss, newConstrDefs)

        dd.copy(vparamss = newvparamss)

      case x => x
    }

    def enhanceVparamss(vparamss: List[List[ValDef]], newConstrDefs: List[ValDef]) = {
      vparamss match {
        case first :+ last if !last.isEmpty && last.forall(_.mods.hasFlag(IMPLICIT)) =>
          first :+ (last ++ newConstrDefs)
        case _ => vparamss :+ newConstrDefs
      }
    }

    def makeAnnotation(msg: String) =
      Apply(
        Select(
          New(
            Select(
              Ident(
                newTermName("annotation")
              ),
              newTypeName("implicitNotFound")
            )
          ),
          newTermName("<init>")
        ),
        List(
          Literal(
            Constant(msg)
          )
        )
      )

    def thisTree(className: TypeName) =
      ValDef (
        Modifiers(IMPLICIT | PRIVATE | LOCAL),
        newTermName(plugin.prefix + className),
        Ident(className),
        This(className)
      )

    def classTransformer(injections: List[(Tree, Name)]) = new TypingTransformer(unit){

      override def transform(tree: Tree) = tree match {
        /* add injected class members and constructor parameters */
        case cd @ ClassDef(mods, className, tparams, impl)
          if !mods.hasFlag(TRAIT) && !mods.hasFlag(MODULE) =>
          println("Transforming " + className)
          val newBodyVals = makeValDefs(injections, IMPLICIT | PARAMACCESSOR | PROTECTED | LOCAL)

          val transformedBody = constructorTransform(impl.body, newConstrDefs(injections))

          // splitting to insert the newly defined implicit val into the
          // correct position in the list of arguments
          val (first, last) = transformedBody.splitAt(impl.body.indexWhere{
            case DefDef(mods, name, tparams, vparamss, tpt, rhs) => name == newTermName("<init>")
            case _ => false
          })

          val newBody =
            first ++
            newBodyVals ++
            last

          super.transform(cd.copy(impl = impl.copy(body = newBody)))

        case cd @ ClassDef(mods, className, tparams, impl) =>
          super.transform(cd)

        case x => super.transform(x)
      }
    }

    new TypingTransformer(unit) {

      override def transform(tree: Tree): Tree =  tree match {
        case dd @ DefDef(mods, name, tparams, vparamss, tpt, rhs)
          if name != newTermName("<init>") =>

          val newVparamss = enhanceVparamss(vparamss, newConstrDefs(unitInjections))
          classTransformer(unitInjections) transform dd.copy(vparamss = newVparamss)

        case cd @ ClassDef(mods, className, tparams, impl)
          if !mods.hasFlag(TRAIT) && !mods.hasFlag(MODULE)
          && unit.body.exists{
            case m @ ModuleDef(mods, termName, Template(parents, self, body))
              if parents.exists(_.toString().contains("sinject.Module"))
                && termName.toString == className.toString => true
            case _ => false
          } =>


          val newThisDef = List(thisTree(className))
          val newAnnotation = List(makeAnnotation("This requires an implicit " + className + " in scope."))

          classTransformer(unitInjections) transform cd.copy(
            mods = mods.copy(annotations = mods.annotations ++ newAnnotation),
            impl = classTransformer(List(Ident(className) -> className)).transform(impl.copy(body = newThisDef ++ impl.body)).asInstanceOf[Template]
          )

        /* inject abstract class member into trait */
        case cd @ ClassDef(mods, className, tparams, impl)
          if mods.hasFlag(TRAIT) =>

          val newDefDefs = makeDefDefs(unitInjections, DEFERRED | IMPLICIT | PROTECTED | LOCAL)

          classTransformer(unitInjections) transform cd.copy(impl = impl.copy(body = newDefDefs ++ impl.body))

        case cd @ ClassDef(mods, className, tparams, impl) =>
          classTransformer(unitInjections) transform cd
        case x => super.transform {x}
      }
    }
  }
}
