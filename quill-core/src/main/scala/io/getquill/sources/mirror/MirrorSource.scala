package io.getquill.sources.mirror

import scala.reflect.macros.whitebox.Context
import language.experimental.macros
import scala.util.Failure
import scala.util.Success
import io.getquill._
import io.getquill.ast.{ Ast, Ident }
import io.getquill.quotation.Quotation
import io.getquill.sources._
import io.getquill.util.Messages.RichContext
import io.getquill.norm.Normalize
import io.getquill.quotation.IsDynamic

class MirrorSource(config: SourceConfig[MirrorSource])
  extends Source[Row, Row]
  with MirrorEncoders
  with MirrorDecoders {

  override def close = ()

  def run[T](quoted: Quotation[Query[T]]): QueryMirror[T] = macro MirrorSourceMacro.run[Row, Row]
  def run[P1, T](quoted: Quotation[P1 => Query[T]]): P1 => QueryMirror[T] = macro MirrorSourceMacro.run[Row, Row]
  def run[P1, P2, T](quoted: Quotation[(P1, P2) => Query[T]]): (P1, P2) => QueryMirror[T] = macro MirrorSourceMacro.run[Row, Row]
  def run[P1, P2, P3, T](quoted: Quotation[(P1, P2, P3) => Query[T]]): (P1, P2, P3) => QueryMirror[T] = macro MirrorSourceMacro.run[Row, Row]

  def run[T](quoted: Quotation[Action[T]]): ActionMirror = macro MirrorSourceMacro.run[Row, Row]
  def run[P1, T](quoted: Quotation[P1 => Action[T]]): List[P1] => BatchActionMirror = macro MirrorSourceMacro.run[Row, Row]
  def run[P1, P2, T](quoted: Quotation[(P1, P2) => Action[T]]): List[(P1, P2)] => BatchActionMirror = macro MirrorSourceMacro.run[Row, Row]
  def run[P1, P2, P3, T](quoted: Quotation[(P1, P2, P3) => Action[T]]): List[(P1, P2, P3)] => BatchActionMirror = macro MirrorSourceMacro.run[Row, Row]

  def run[T](quoted: Quotation[T]): QueryMirror[T] = macro MirrorSourceMacro.run[Row, Row]
  def run[P1, T](quoted: Quotation[P1 => T]): P1 => QueryMirror[T] = macro MirrorSourceMacro.run[Row, Row]
  def run[P1, P2, T](quoted: Quotation[(P1, P2) => T]): (P1, P2) => QueryMirror[T] = macro MirrorSourceMacro.run[Row, Row]
  def run[P1, P2, P3, T](quoted: Quotation[(P1, P2, P3) => T]): (P1, P2, P3) => QueryMirror[T] = macro MirrorSourceMacro.run[Row, Row]

  def mirrorConfig = config

  def probe(ast: Ast) =
    if (ast.toString.contains("Fail"))
      Failure(new IllegalStateException("The ast contains 'Fail'"))
    else
      Success(())

  case class ActionMirror(ast: Ast, bind: Row)

  def execute(ast: Ast, bindParams: Row => Row = identity, generated: Option[String] = None) =
    ActionMirror(ast, bindParams(Row()))

  case class BatchActionMirror(ast: Ast, bindList: List[Row])

  def executeBatch[T](ast: Ast, bindParams: T => Row => Row = (_: T) => identity[Row] _, generated: Option[String] = None) =
    (values: List[T]) =>
      BatchActionMirror(ast, values.map(bindParams).map(_(Row())))

  case class QueryMirror[T](ast: Ast, binds: Row, extractor: Row => T)

  def querySingle[T](ast: Ast, extractor: Row => T = identity[Row] _, bind: Row => Row = identity) =
    QueryMirror(ast, bind(Row()), extractor)

  def query[T](ast: Ast, extractor: Row => T = identity[Row] _, bind: Row => Row = identity) = QueryMirror(ast, bind(Row()), extractor)
}

class MirrorSourceMacro(val c: Context) extends SourceMacro {
  import c.universe.{ Ident => _, _ }

  override protected def prepare(ast: Ast, params: List[Ident]) =
    IsDynamic(ast) match {
      case false =>
        val normalized = Normalize(ast)
        resolveSource[MirrorSource].map(_.probe(normalized)) match {
          case Some(Failure(e)) => c.error(s"Probe failed. Reason $e")
          case other            =>
        }
        c.info(normalized.toString)
        val (entity, insert) = ExtractEntityAndInsertAction(normalized)
        val isInsert = insert.isDefined
        val generated = if (isInsert) entity.flatMap(_.generated) else None
        q"($normalized, $params, $generated)"
      case true =>
        q"""
          import io.getquill.norm._
          import io.getquill.ast._
          import io.getquill.sources.ExtractEntityAndInsertAction

          val normalized = Normalize($ast: Ast)
          val (entity, insert) = ExtractEntityAndInsertAction(normalized)
          val isInsert = insert.isDefined
          val generated = if (isInsert) entity.flatMap(_.generated) else None

          (normalized, $params, generated)
        """
    }
}
