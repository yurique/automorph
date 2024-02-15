package automorph.codec.messagepack

import automorph.schema.OpenRpc
import automorph.schema.openrpc.*
import upack.{Arr, Msg, Obj, Str}
import upickle.core.{Abort, LinkedHashMap}

/** JSON-RPC protocol support for Circe message codec plugin using JSON format. */
private[automorph] object UpickleOpenRpc {

  @scala.annotation.nowarn("msg=never used")
  def readWriter[Config <: UpickleMessagePackConfig](config: Config): config.ReadWriter[OpenRpc] = {
    import config.*

    implicit val schemaRw: config.ReadWriter[Schema] = readwriter[Msg].bimap[Schema](fromSchema, toSchema)
    implicit val contactRw: config.ReadWriter[Contact] = config.macroRW
    implicit val contentDescriptorRw: config.ReadWriter[ContentDescriptor] = config.macroRW
    implicit val externalDocumentationRw: config.ReadWriter[ExternalDocumentation] = config.macroRW
    implicit val errorRw: config.ReadWriter[Error] = config.macroRW
    implicit val exampleRw: config.ReadWriter[Example] = config.macroRW
    implicit val licenseRw: config.ReadWriter[License] = config.macroRW
    implicit val serverVariableRw: config.ReadWriter[ServerVariable] = config.macroRW
    implicit val examplePairingRw: config.ReadWriter[ExamplePairing] = config.macroRW
    implicit val infoRw: config.ReadWriter[Info] = config.macroRW
    implicit val serverRw: config.ReadWriter[Server] = config.macroRW
    implicit val tagRw: config.ReadWriter[Tag] = config.macroRW
    implicit val linkRw: config.ReadWriter[Link] = config.macroRW
    implicit val componentsRw: config.ReadWriter[Components] = config.macroRW
    implicit val methodRw: config.ReadWriter[Method] = config.macroRW

    config.macroRW[OpenRpc]
  }

  private def fromSchema(schema: Schema): Msg =
    Obj(LinkedHashMap[Msg, Msg](
      Seq(
        schema.`type`.map(Str("type") -> Str(_)),
        schema.title.map(Str("title") -> Str(_)),
        schema.description.map(Str("description") -> Str(_)),
        schema.properties.map(v =>
          Str("properties") -> Obj(LinkedHashMap[Msg, Msg](v.map { case (key, value) =>
            Str(key) -> fromSchema(value)
          }))
        ),
        schema.required.map(v => Str("required") -> Arr(v.map(Str.apply)*)),
        schema.default.map(Str("default") -> Str(_)),
        schema.allOf.map(v => Str("allOf") -> Arr(v.map(fromSchema)*)),
        schema.$ref.map(Str("$ref") -> Str(_)),
      ).flatten
    ))

  private def toSchema(node: Msg): Schema =
    node match {
      case Obj(fields) => Schema(
          `type` = fields.get(Str("type")).map(_.str),
          title = fields.get(Str("title")).map(_.str),
          description = fields.get(Str("description")).map(_.str),
          properties = fields.get(Str("properties")).map(_.obj.map { case (key, value) => key.str -> toSchema(value) }
            .toMap),
          required = fields.get(Str("required")).map(_.arr.map(_.str).toList),
          default = fields.get(Str("default")).map(_.str),
          allOf = fields.get(Str("allOf")).map(_.arr.map(toSchema).toList),
          $ref = fields.get(Str("$ref")).map(_.str),
        )
      case _ => throw Abort(s"Invalid OpenRPC object")
    }
}
