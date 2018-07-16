package io.rsocket.demo;

import io.rsocket.AbstractRSocket;
import io.rsocket.ConnectionSetupPayload;
import io.rsocket.Payload;
import io.rsocket.RSocket;
import io.rsocket.RSocketFactory;
import io.rsocket.transport.netty.server.WebsocketRouteTransport;
import io.rsocket.util.PayloadImpl;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.social.connect.Connection;
import org.springframework.social.twitter.api.Stream;
import org.springframework.social.twitter.api.Tweet;
import org.springframework.social.twitter.api.Twitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.http.server.HttpServer;
import reactor.ipc.netty.http.server.HttpServerRoutes;

public class App {
  public static void main(String[] args) {
    Connection<org.springframework.social.twitter.api.Twitter> twitter = TwitterFactory.connect();

    HttpServer httpServer = HttpServer.create(port());

    HttpServerRoutes routes = HttpServerRoutes.newRoutes();

    RSocketFactory.receive()
        .acceptor(
            (setupPayload, reactiveSocket) -> createServerRequestHandler(twitter.getApi(),
                setupPayload))
        .transport(new WebsocketRouteTransport(routes, "/ws"))
        .start()
        .block();

    routes.file("/", webPath("src/main/resources/web/index.html"));
    routes.directory("/", webPath("src/main/resources/web"));

    NettyContext server = httpServer.newHandler((request, response) -> {
      response.addHeader("Access-Control-Allow-Origin", "*");

      return routes.apply(request, response);
    }).block();

    System.out.println("running on " + port());

    server.onClose().block(Duration.ofDays(3650));
  }

  private static Path webPath(String pathname) {
    return new File(pathname).getAbsoluteFile().toPath();
  }

  private static int port() {
    return Integer.getInteger("server.port", 8080);
  }

  private static Mono<RSocket> createServerRequestHandler(Twitter twitter,
      ConnectionSetupPayload setupPayload) {

    return Mono.just(
        new AbstractRSocket() {
          @Override
          public Mono<Payload> requestResponse(Payload payload) {
            return Mono.just(payload);
          }

          @Override
          public Flux<Payload> requestStream(Payload payload) {
            String query = StandardCharsets.UTF_8.decode(payload.getData()).toString();
            return twitterSearch(query, twitter).take(10000);
          }
        });
  }

  private static Flux<Payload> twitterSearch(String query, Twitter twitter) {
    return Flux.create(s -> {
      System.out.println("Opening " + query);
      Stream stream =
          twitter.streamingOperations().filter(query, Arrays.asList(new StreamAdapter() {
            @Override public void onTweet(Tweet tweet) {
              s.next(new PayloadImpl(tweet.getText()));
              //if (s.requestedFromDownstream() == 0) {
              //  stream.close();
              //}
            }
          }));

      //s.onRequest(l -> stream.open());
      s.onCancel(() -> {
        System.out.println("Closing " + query);
        stream.close();
      });
    }, FluxSink.OverflowStrategy.DROP);
  }
}
