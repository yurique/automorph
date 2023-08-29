package automorph.codec.messagepack.meta

import automorph.codec.messagepack.UpickleMessagePackConfig
import automorph.spi.MessageCodec
import scala.language.experimental.macros
import scala.reflect.macros.blackbox
import upack.Msg

/**
 * UPickle MessagePack codec plugin code generation.
 *
 * @tparam Config
 *   Upickle configuration type
 */
trait UpickleMessagePackMeta[Config <: UpickleMessagePackConfig] extends MessageCodec[Msg] {

  override def encode[T](value: T): Msg =
    macro UpickleMessagePackMeta.encodeMacro[T]

  override def decode[T](node: Msg): T =
    macro UpickleMessagePackMeta.decodeMacro[T]
}

object UpickleMessagePackMeta {

  def encodeMacro[T](c: blackbox.Context)(value: c.Expr[T]): c.Expr[Msg] = {
    import c.universe.Quasiquote

    c.Expr[Msg](q"""
      import ${c.prefix}.config.*
      ${c.prefix}.config.writeMsg($value)
    """)
  }

  def decodeMacro[T: c.WeakTypeTag](c: blackbox.Context)(node: c.Expr[Msg]): c.Expr[T] = {
    import c.universe.{weakTypeOf, Quasiquote}

    c.Expr[T](q"""
      import ${c.prefix}.config.*
      ${c.prefix}.config.readBinary[${weakTypeOf[T]}]($node)
    """)
  }
}
