package org.jetbrains.plugins.dotty.codeInspection.deprecated

import com.intellij.codeInspection.ProblemHighlightType.LIKE_DEPRECATED
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.dotty.codeInspection.deprecated.WithTypeInspection._
import org.jetbrains.plugins.dotty.lang.psi.impl.base.types.DottyAndTypeElementImpl
import org.jetbrains.plugins.scala.codeInspection.{AbstractFixOnPsiElement, AbstractInspection, InspectionBundle}
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes.{kWITH, tAND}
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory.createElement

/**
  * @author adkozlov
  */
class WithTypeInspection extends AbstractInspection(id, name) {
  override def actionFor(holder: ProblemsHolder): PartialFunction[PsiElement, Unit] = {
    case typeElement: DottyAndTypeElementImpl =>
      typeElement.findChildrenByType(kWITH).foreach {
        case token => holder.registerProblem(token, message, LIKE_DEPRECATED, new ReplaceWithTypeQuickFix(token))
      }
  }
}

class ReplaceWithTypeQuickFix(token: PsiElement) extends AbstractFixOnPsiElement(name, token) {
  override def doApplyFix(project: Project): Unit = getElement match {
    case element if element.isValid =>
      element.replace(createElement(tAND.toString, element.getManager, _ => {}))
  }
}

object WithTypeInspection {
  private[codeInspection] val id = "WithTypeDeprecated"
  private[codeInspection] val name = InspectionBundle.message("replace.with.ampersand")
  private[codeInspection] val message = s"With type is deprecated in Dotty. $name"
}
