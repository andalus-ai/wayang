package tech.kayys.wayang.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import io.quarkus.arc.DefaultBean;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;

/**
 * The Gamelan SDK jars on the classpath contain beans (for example its HTTP executor client) that inject a Mutiny {@link WebClient}; the
 * SDK's own producer lives in a Gamelan engine module that Wayang does not include. {@link DefaultBean} so a deployment (or Gamelan itself)
 * can supply its own.
 */
@ApplicationScoped
public class WebClientProvider {

    @Inject
    Vertx vertx;

    @Produces
    @DefaultBean
    @ApplicationScoped
    public WebClient webClient() {
        return WebClient.create(vertx);
    }
}
