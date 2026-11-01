package tech.kayys.wayang.rest;

import jakarta.enterprise.context.ApplicationScoped;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.quarkus.grpc.GlobalInterceptor;

/**
 * Carries the call's {@code authorization} and {@code x-tenant} metadata into the gRPC {@link Context}, so
 * {@link Principals} can authenticate a gRPC call in numu mode (there are no HTTP headers there). It only transports the raw
 * strings: nothing is validated here, and the (blocking) identity lookup happens later on a worker thread.
 */
@ApplicationScoped
@GlobalInterceptor
public class GrpcAuthInterceptor implements ServerInterceptor {

    static final Context.Key<String> AUTHORIZATION = Context.key("wayang-authorization");
    static final Context.Key<String> TENANT = Context.key("wayang-tenant");
    private static final Metadata.Key<String> AUTH_KEY = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> TENANT_KEY = Metadata.Key.of("x-tenant", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
        String auth = headers.get(AUTH_KEY);
        String tenant = headers.get(TENANT_KEY);
        Context ctx = Context.current().withValue(AUTHORIZATION, auth == null ? "" : auth).withValue(TENANT, tenant == null ? "" : tenant);
        return Contexts.interceptCall(ctx, call, headers, next);
    }
}
