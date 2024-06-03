package automorph.transport.client

import automorph.log.{Logging, MessageLog}
import automorph.spi.EffectSystem.Completable
import automorph.spi.{EffectSystem, ClientTransport}
import automorph.transport.client.RabbitMqClient.{Context, Response}
import automorph.transport.{AmqpContext, RabbitMq}
import automorph.util.Extensions.{EffectOps, TryOps}
import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client.{Address, Channel, ConnectionFactory, DefaultConsumer, Envelope}
import java.net.URI
import scala.collection.concurrent.TrieMap
import scala.util.Try

/**
 * RabbitMQ client message transport plugin.
 *
 * Uses the supplied RPC request as AMQP request message body and returns AMQP response message body as a result.
 *   - AMQP request messages are published to the specified exchange using ``direct reply-to``mechanism.
 *   - AMQP response messages are consumed using ``direct reply-to``mechanism and automatically acknowledged.
 *
 * @see
 *   [[https://www.rabbitmq.com/java-client.html Documentation]]
 * @see
 *   [[https://rabbitmq.github.io/rabbitmq-java-client/api/current/index.html API]]
 * @constructor
 *   Creates a RabbitMQ client message transport plugin.
 * @param effectSystem
 *   effect system plugin
 * @param url
 *   AMQP broker URL (amqp[s]://[username:password@]host[:port][/virtual_host])
 * @param routingKey
 *   AMQP routing key (typically a queue name)
 * @param exchange
 *   direct non-durable AMQP message exchange name
 * @param addresses
 *   broker hostnames and ports for reconnection attempts
 * @param connectionFactory
 *   AMQP broker connection factory
 * @tparam Effect
 *   effect type
 */
final case class RabbitMqClient[Effect[_]](
  effectSystem: EffectSystem[Effect],
  url: URI,
  routingKey: String,
  exchange: String = RabbitMq.directExchange,
  addresses: Seq[Address] = Seq.empty,
  connectionFactory: ConnectionFactory = new ConnectionFactory,
) extends ClientTransport[Effect, Context] with Logging {
  private var session = Option.empty[RabbitMq.Session]
  private val directReplyToQueue = "amq.rabbitmq.reply-to"
  private val clientId = RabbitMq.applicationId(getClass.getName)
  private val urlText = url.toString
  private val responseHandlers = TrieMap[String, Completable[Effect, Response]]()
  private val log = MessageLog(logger, RabbitMq.protocol)
  implicit private val system: EffectSystem[Effect] = effectSystem

  override def call(
    body: Array[Byte],
    context: Context,
    id: String,
    mediaType: String,
  ): Effect[Response] =
    effectSystem.completable[Response].flatMap { response =>
      send(body, id, mediaType, context, Some(response)).flatMap(_ => response.effect)
    }

  override def tell(
    body: Array[Byte],
    context: Context,
    id: String,
    mediaType: String,
  ): Effect[Unit] =
    send(body, id, mediaType, context, None)

  override def context: Context =
    RabbitMq.Transport.context

  override def init(): Effect[Unit] =
    system.evaluate(this.synchronized {
      session.fold {
        val connection = RabbitMq.connect(url, addresses, clientId, connectionFactory)
        RabbitMq.declareExchange(exchange, connection)
        val consumer = RabbitMq.threadLocalConsumer(connection, createConsumer)
        session = Some(RabbitMq.Session(connection, consumer))
      }(_ => throw new IllegalStateException(s"${getClass.getSimpleName} already initialized"))
    })

  override def close(): Effect[Unit] =
    effectSystem.evaluate(this.synchronized {
      RabbitMq.close(session)
      session = None
    })

  private def send(
    requestBody: Array[Byte],
    defaultRequestId: String,
    mediaType: String,
    context: Context,
    response: Option[Completable[Effect, Response]],
  ): Effect[Unit] = {
    // Log the request
    val amqpProperties = RabbitMq.amqpProperties(
      Some(context),
      mediaType,
      directReplyToQueue,
      defaultRequestId,
      clientId,
      useDefaultRequestId = false,
    )
    val requestId = amqpProperties.getCorrelationId
    lazy val requestProperties = RabbitMq.messageProperties(Some(requestId), routingKey, urlText, None)
    log.sendingRequest(requestProperties)

    // Register deferred response effect if available
    response.foreach(responseHandlers.put(requestId, _))

    // Send the request
    effectSystem.evaluate {
      Try {
        session.get.consumer.get.getChannel.basicPublish(
          exchange,
          routingKey,
          true,
          false,
          amqpProperties,
          requestBody,
        )
        log.sentRequest(requestProperties)
      }.onError { error =>
        log.failedSendRequest(error, requestProperties)
      }.get
    }
  }

  private def createConsumer(channel: Channel): DefaultConsumer = {
    val consumer = new DefaultConsumer(channel) {

      override def handleDelivery(
        consumerTag: String,
        envelope: Envelope,
        properties: BasicProperties,
        responseBody: Array[Byte],
      ): Unit = {
        // Log the response
        lazy val responseProperties = RabbitMq
          .messageProperties(Option(properties.getCorrelationId), routingKey, urlText, None)
        log.receivedResponse(responseProperties)

        // Complete the registered deferred response effect
        val responseContext = RabbitMq.messageContext(properties)
        responseHandlers.get(properties.getCorrelationId).foreach { response =>
          response.succeed(responseBody -> responseContext).runAsync
        }
      }
    }
    consumer.getChannel.basicConsume(directReplyToQueue, true, consumer)
    consumer
  }
}

object RabbitMqClient {

  /** Request context type. */
  type Context = AmqpContext[Message]

  /** Message properties. */
  type Message = RabbitMq.Transport

  private type Response = (Array[Byte], Context)
}
