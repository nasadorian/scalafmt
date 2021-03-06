package org.scalafmt.rewrite

import org.scalafmt.util.{TokenOps, Whitespace}

import scala.meta.tokens.Tokens
import scala.meta._

/**
  * Replaces multi generator For / ForYield Expression parens and semi-colons
  * with braces and new-lines.
  *
  * For example,
  *
  *   for(a <- as; b <- bs if b > 2) yield (a, b)
  *
  * becomes,
  *
  *   for {
  *     a <- as
  *     b <- bs if b > 2
  *   } yield (a, b)
  *
  */
case object PreferCurlyFors extends Rewrite {

  def findForParens(
      forTokens: Tokens
  )(implicit ctx: RewriteCtx): Option[(Token, Token)] = {
    import ctx.tokenTraverser._

    for {
      forToken <- forTokens.find(_.is[Token.KwFor])
      leftParen <- findAfter(forToken) {
        case _: Token.LeftParen => Some(true)
        case Whitespace() => None
        case _ => Some(false)
      }
      rightParen <- ctx.matchingParens.get(TokenOps.hash(leftParen))
    } yield (leftParen, rightParen)
  }

  def findForSemiColons(
      forEnumerators: Seq[Enumerator]
  )(implicit ctx: RewriteCtx): Seq[Token] = {
    import ctx.tokenTraverser._

    for {
      enumerator <- forEnumerators
      token <- enumerator
        .tokens(ctx.style.runner.dialect)
        .headOption
        .toIterable
      semicolon <- findBefore(token) {
        case _: Token.Semicolon => Some(true)
        case Whitespace() => None
        case _ => Some(false)
      }.toIterable
    } yield semicolon
  }

  def rewriteFor(
      forTokens: Tokens,
      forEnumerators: Seq[Enumerator]
  )(implicit ctx: RewriteCtx): Seq[TokenPatch] = {
    import ctx.tokenTraverser._

    val builder = Seq.newBuilder[TokenPatch]

    findForParens(forTokens).foreach { parens =>
      val openBraceTokens =
        if (nextToken(parens._1).is[Token.LF]) "{" else "{\n"
      builder += TokenPatch.AddRight(parens._1, openBraceTokens)
      builder += TokenPatch.AddRight(parens._2, "}")
      findForSemiColons(forEnumerators).foreach { semiColon =>
        val semiColonReplacementTokens =
          if (nextToken(semiColon).is[Token.LF]) "" else "\n"
        builder += TokenPatch.AddRight(semiColon, semiColonReplacementTokens)
      }
    }

    builder.result()
  }

  def hasMoreThanOneGenerator(forEnumerators: Seq[Enumerator]): Boolean =
    forEnumerators.count(_.is[Enumerator.Generator]) > 1

  override def rewrite(implicit ctx: RewriteCtx): Unit = {
    import ctx.dialect
    ctx.tree.traverse {
      case fy: Term.ForYield if hasMoreThanOneGenerator(fy.enums) =>
        ctx.addPatchSet(rewriteFor(fy.tokens, fy.enums): _*)
      case f: Term.For if hasMoreThanOneGenerator(f.enums) =>
        ctx.addPatchSet(rewriteFor(f.tokens, f.enums): _*)
    }
  }
}
