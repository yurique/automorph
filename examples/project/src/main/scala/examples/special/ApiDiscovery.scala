package examples.special

import automorph.Default
import automorph.protocol.JsonRpcProtocol
import automorph.schema.{OpenApi, OpenRpc}
import java.net.URI
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

private[examples] object ApiDiscovery {
  @scala.annotation.nowarn
  def main(arguments: Array[String]): Unit = {

    // Define a remote API
    trait Api {
      def hello(some: String, n: Int): Future[String]
    }

    // Create server implementation of the remote API
    val api = new Api {
      def hello(some: String, n: Int): Future[String] =
        Future(s"Hello $some $n!")
    }

    Await.ready(for {
      // Initialize JSON-RPC HTTP & WebSocket server with API discovery enabled
      server <- Default.rpcServer(9000, "/api").discovery(true).bind(api).init()

      // Initialize JSON-RPC HTTP client for sending POST requests to 'http://localhost:9000/api'
      client <- Default.rpcClient(new URI("http://localhost:9000/api")).init()

      // Retrieve the remote API schema in OpenRPC format
      result <- client.call[OpenRpc](JsonRpcProtocol.openRpcFunction)()
      _ = println(result.methods.map(_.name))

      // Retrieve the remote API schema in OpenAPI format
      result <- client.call[OpenApi](JsonRpcProtocol.openApiFunction)()
      _ = println(result.paths.get.keys.toList)

      // Close the RPC client
      _ <- client.close()

      // Close the RPC server
      _ <- server.close()
    } yield (), Duration.Inf)
  }
}
