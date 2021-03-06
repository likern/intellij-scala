package org.jetbrains.plugins.scala.lang

import com.intellij.psi._
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiElement
import org.jetbrains.plugins.scala.lang.psi.api.base.ScReferenceElement
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScReferenceExpression
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScTypeAliasDefinition
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory._
import org.jetbrains.plugins.scala.lang.psi.types.api.designator.{ScDesignatorType, ScThisType}
import org.jetbrains.plugins.scala.lang.resolve.ScalaResolveResult

import scala.annotation.tailrec
import scala.util.matching.Regex

/**
  * @author Pavel Fatin
  */
package object transformation {
  private val SimpleName = new Regex("(?:.+\\.)?(.+)")
  private val PartiallyQualifiedName = new Regex(".+\\.(.+\\..+)")
  private val RelativeName = new Regex("_root_\\.(.+)")
  private val AbsoluteName = new Regex("(_root_\\..+)")
  private val FullName = new Regex("(.+)")

  def quote(s: String): String = "\"" + s + "\""

  def simpleNameOf(qualifiedName: String): String = qualifiedName match {
    case SimpleName(name) => name
  }

  // TODO create a separate unit test for this method
  // Tries to use simple name, then partially qualified name, then fully qualified name instead of adding imports
  def bindTo(reference: ScReferenceElement, target: String) {
    val context = reference.getParent

    val isExpression = reference.isInstanceOf[ScReferenceExpression]

    @tailrec
    def bindTo0(r1: ScReferenceElement, paths: Seq[String]) {
      paths match {
        case Seq(path, alternatives @ _*)  =>
          val r2 = r1.replace(createReferenceElement(path)(r1.psiManager, isExpression)).asInstanceOf[ScReferenceElement]
          if (!isResolvedTo(r2, target)(context, isExpression)) {
            bindTo0(r2, alternatives)
          }
        case _ =>
      }
    }

    val variants = variantsOf(target)

    if (!(reference.text == variants.head && isResolvedTo(reference, target)(context, isExpression))) {
      bindTo0(reference, variants)
    }
  }

  private def variantsOf(reference: String): Seq[String] =
    Seq(SimpleName, PartiallyQualifiedName, RelativeName, AbsoluteName, FullName)
      .flatMap(_.findFirstMatchIn(reference)).map(_.group(1)).distinct

  private def relative(reference: String): String = reference.replaceFirst("^_root_.", "")

  private def isResolvedTo(reference: ScReferenceElement, target: String)(context: PsiElement, isExpression: Boolean): Boolean =
    reference.bind().exists(result =>
      qualifiedNameOf(result.element) == relative(target))

  private def createReferenceElement(reference: String)(manager: PsiManager, isExpression: Boolean): ScReferenceElement =
    if (isExpression) parseElement(reference, manager).asInstanceOf[ScReferenceExpression]
    else createTypeElementFromText(reference, manager).getFirstChild.asInstanceOf[ScReferenceElement]

  // TODO define PsiMember.qualifiedName
  def qualifiedNameOf(e: PsiNamedElement): String = e match {
    // TODO support complex types, how to handle aliases?
    case it: ScTypeAliasDefinition => it.aliasedType.map(t => relative(t.canonicalText)).getOrElse(it.name)
    case it: PsiClass => it.qualifiedName
    case it: PsiMember => Option(it.containingClass).map(_.qualifiedName + ".").getOrElse("") + it.name
    case it => it.name
  }

  def targetFor(result: ScalaResolveResult): String = {
    result.substitutor.updateThisType.collect {
      case t: ScThisType => t.element.qualifiedName + "." + result.element.name
      case t: ScDesignatorType => qualifiedNameOf(t.element) + "." + result.element.name
    } getOrElse {
      qualifiedNameOf(result.element)
    }
  }

  object RenamedReference {
    def unapply(r: ScReferenceElement): Option[(String, String)] = {
      val id = r.nameId
      r.bind().flatMap(_.innerResolveResult).orElse(r.bind()).map(_.element) collect  {
        case target: PsiNamedElement if id.getText != target.name => (id.getText, target.name)
      }
    }
  }

  object QualifiedReference {
    def unapply(r: ScReferenceElement): Some[(Option[ScalaPsiElement], PsiElement)] =
      Some(r.qualifier, r.nameId)
  }
}
