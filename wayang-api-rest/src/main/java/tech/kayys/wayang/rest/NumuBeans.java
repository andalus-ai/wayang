package tech.kayys.wayang.rest;

import java.time.Clock;
import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import tech.kayys.wayang.numu.NumuIdentity;
import tech.kayys.wayang.numu.NumuPrincipalResolver;
import tech.kayys.wayang.numu.NumuScheme;

/**
 * Wires Numu identity ({@code wayang.auth.mode=numu}). Configure the gRPC client with
 * {@code quarkus.grpc.clients.numu.host} / {@code .port} (and TLS); the credential cache lifetime is
 * {@code wayang.auth.numu.cache-seconds} (default 30; 0 disables caching, and a revoked role or token then takes
 * effect immediately at the price of two remote calls per request).
 */
@ApplicationScoped
public class NumuBeans {

    @ConfigProperty(name = "wayang.auth.numu.cache-seconds", defaultValue = "30")
    long cacheSeconds;

    @ConfigProperty(name = "wayang.auth.numu.product", defaultValue = "wayang")
    String product;

    @Inject
    NumuGrpcIdentity identity;

    @Inject
    tech.kayys.wayang.api.ProjectStore projects;

    @Produces
    @ApplicationScoped
    public NumuPrincipalResolver numuResolver() {
        return new NumuPrincipalResolver((NumuIdentity) identity, new NumuScheme(product), Clock.systemUTC(),
                Duration.ofSeconds(cacheSeconds),
                tenant -> projects.list(tenant).stream().map(tech.kayys.wayang.api.Project::id).toList());
    }
}
