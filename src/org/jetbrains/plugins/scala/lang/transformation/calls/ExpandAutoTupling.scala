package org.jetbrains.plugins.scala.lang.transformation
package calls

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.scala.extensions.Resolved
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScInfixExpr, ScMethodCall}
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaCode._

/**
  * @author Pavel Fatin
  */
class ExpandAutoTupling extends AbstractTransformer {
  def transformation(implicit project: Project): PartialFunction[PsiElement, Unit] = {
    case e @ ScMethodCall(t @ Resolved(result), es) if result.tuplingUsed =>
      e.replace(code"$t((${@@(es)}))")

    case ScInfixExpr(_, Resolved(result), r) if result.tuplingUsed =>
      r.replace(code"($r)")
  }
}
