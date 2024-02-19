//> using dep org.automorph::automorph-default:@PROJECT_VERSION@
//> using dep ch.qos.logback:logback-classic:@LOGGER_VERSION@
package examples.transport

import automorph.Default
import java.net.URI
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

private[examples] object WebSocketTransport {

  // Serve a and call a remote API using JSON-RPC over WebSocket.
  @scala.annotation.nowarn
  def main(arguments: Array[String]): Unit = {

    // Define a remote API
    trait Api {
      def hello(n: Int): Future[String]
    }

    // Create server implementation of the remote API
    val service = new Api {
      def hello(n: Int): Future[String] =
        Future(s"Hello world $n")
    }

    val run = for {
      // Initialize JSON-RPC HTTP & WebSocket server listening on port 9000 for requests to '/api'
      server <- Default.rpcServer(9000, "/api").bind(service).init()

      // Initialize JSON-RPC HTTP & WebSocket client sending requests to 'ws://localhost:9000/api'
      client <- Default.rpcClient(new URI("ws://localhost:9000/api")).init()
      remoteApi = client.bind[Api]

      // Call the remote API function via a local proxy
      result <- remoteApi.hello(1)
      _ = println(result)

      // Close the RPC client and server
      _ <- client.close()
      _ <- server.close()
    } yield ()
    Await.result(run, Duration.Inf)

  }
}
