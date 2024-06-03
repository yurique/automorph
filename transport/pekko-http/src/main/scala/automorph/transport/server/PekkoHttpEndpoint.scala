package automorph.transport.server

import automorph.spi.{EffectSystem, RequestHandler, ServerTransport}
import automorph.transport.HttpContext.headerRpcNodeId
import automorph.transport.HttpRequestHandler.{RequestMetadata, ResponseMetadata}
import automorph.transport.server.PekkoHttpEndpoint.Context
import automorph.transport.{HighHttpRequestHandler, HttpContext, HttpMethod, HttpRequestHandler, Protocol}
import automorph.util.Extensions.{EffectOps, ThrowableOps}
import org.apache.pekko.http.scaladsl.model.StatusCodes.InternalServerError
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.{ContentType, HttpEntity, HttpRequest, HttpResponse, RemoteAddress, StatusCode}
import org.apache.pekko.http.scaladsl.server.Directives.{complete, extractClientIP, extractExecutionContext, extractMaterializer, extractRequest, onComplete}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.Materializer
import scala.annotation.unused
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.OptionConverters.RichOptional

/**
 * Pekko HTTP endpoint message transport plugin.
 *
 * Interprets HTTP request body as a RPC request and processes it with the specified RPC handler.
 *   - The response returned by the RPC handler is used as HTTP response body.
 *
 * @see
 *   [[https://en.wikipedia.org/wiki/HTTP Transport protocol]]
 * @see
 *   [[https://pekko.apache.org Library documentation]]
 * @see
 *   [[https://pekko.apache.org/api/pekko-http/current/ API]]
 * @constructor
 *   Creates an Pekko HTTP endpoint message transport plugin with specified effect system and request handler.
 * @param effectSystem
 *   effect system plugin
 * @param mapException
 *   maps an exception to a corresponding HTTP status code
 * @param readTimeout
 *   HTTP request read timeout
 * @param handler
 *   RPC request handler
 * @tparam Effect
 *   effect type
 */
final case class PekkoHttpEndpoint[Effect[_]](
  effectSystem: EffectSystem[Effect],
  mapException: Throwable => Int = HttpContext.toStatusCode,
  readTimeout: FiniteDuration = 30.seconds,
  handler: RequestHandler[Effect, Context] = RequestHandler.dummy[Effect, Context],
) extends ServerTransport[Effect, Context, Route] {

  private lazy val contentType = ContentType.parse(handler.mediaType).swap.map { errors =>
    new IllegalStateException(s"Invalid message content type: ${errors.mkString("\n")}")
  }.swap.toTry.get
  private lazy val route: Route =
    extractRequest { httpRequest =>
      extractClientIP { remoteAddress =>
        extractMaterializer { implicit materializer =>
          extractExecutionContext { implicit executionContext =>
            onComplete(handleRequest(httpRequest, remoteAddress))(_.fold(
              error => complete(InternalServerError, error.description),
              response => complete(response),
            ))
          }
        }
      }
    }
  private val httpHandler =
    HighHttpRequestHandler(receiveRequest, createResponse, Protocol.Http, effectSystem, mapException, handler)
  implicit private val system: EffectSystem[Effect] = effectSystem

  def adapter: Route =
    route

  override def init(): Effect[Unit] =
    effectSystem.successful {}

  override def close(): Effect[Unit] =
    effectSystem.successful {}

  override def requestHandler(handler: RequestHandler[Effect, Context]): PekkoHttpEndpoint[Effect] =
    copy(handler = handler)

  private def handleRequest(request: HttpRequest, remoteAddress: RemoteAddress)(
    implicit
    materializer: Materializer,
    executionContext: ExecutionContext,
  ): Future[HttpResponse] = {
    val handleRequestResult = Promise[HttpResponse]()
    request.entity.toStrict(readTimeout).flatMap { requestEntity =>
      httpHandler.processRequest((request, requestEntity, remoteAddress), ()).fold(
        error => handleRequestResult.failure(error),
        result => handleRequestResult.success(result),
      )
      handleRequestResult.future
    }
  }

  private def receiveRequest(
    incomingRequest: (HttpRequest, HttpEntity.Strict, RemoteAddress)
  ): (RequestMetadata[Context], Effect[Array[Byte]]) = {
    val (request, requestEntity, remoteAddress) = incomingRequest
    val requestMetadata = RequestMetadata(
      getRequestContext(request, remoteAddress),
      httpHandler.protocol,
      request.uri.toString,
      Some(request.method.value),
    )
    val requestBody = effectSystem.evaluate(requestEntity.data.toArray[Byte])
    (requestMetadata, requestBody)
  }

  private def createResponse(responseData: ResponseMetadata[Context], @unused channel: Unit): Effect[HttpResponse] =
    effectSystem.successful(
      createResponseContext(HttpResponse(), responseData.context)
        .withStatus(StatusCode.int2StatusCode(responseData.statusCode))
        .withEntity(contentType, responseData.body)
    )

  private def getRequestContext(request: HttpRequest, remoteAddress: RemoteAddress): Context =
    HttpContext(
      transportContext = Some(request),
      method = Some(HttpMethod.valueOf(request.method.value)),
      headers = request.headers.map(header => header.name -> header.value),
      peerId = Some(clientId(remoteAddress, request)),
    ).url(request.uri.toString)

  private def createResponseContext(response: HttpResponse, responseContext: Option[Context]): HttpResponse =
    response.withHeaders(responseContext.toSeq.flatMap(_.headers).map { case (name, value) => RawHeader(name, value) })

  private def clientId(remoteAddress: RemoteAddress, request: HttpRequest): String = {
    val address = remoteAddress.toOption.flatMap(address => Option(address.getHostAddress)).getOrElse("")
    val nodeId = request.getHeader(headerRpcNodeId).toScala.map(_.value)
    HttpRequestHandler.clientId(address, None, nodeId)
  }
}

object PekkoHttpEndpoint {

  /** Request context type. */
  type Context = HttpContext[HttpRequest]
}
