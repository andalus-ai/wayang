package tech.kayys.wayang.rest;

import java.util.Map;

import jakarta.inject.Inject;

import io.grpc.Status;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.Principal;
import tech.kayys.wayang.api.Views;
import tech.kayys.wayang.api.WayangApi;
import tech.kayys.wayang.grpc.CompleteTaskRequest;
import tech.kayys.wayang.grpc.Empty;
import tech.kayys.wayang.grpc.JsonReply;
import tech.kayys.wayang.grpc.ListRequest;
import tech.kayys.wayang.grpc.MessageRequest;
import tech.kayys.wayang.grpc.MutinyWayangGrpc;
import tech.kayys.wayang.grpc.RunRef;
import tech.kayys.wayang.grpc.StartRunRequest;
import tech.kayys.wayang.grpc.WatchRequest;
import tech.kayys.wayang.runtime.json.Json;

/**
 * gRPC shell over {@link WayangApi}, for server-to-server and mobile/desktop
 * clients. Flutter web cannot speak native gRPC (needs a grpc-web proxy); the
 * Studio should use REST and WebSocket.
 *
 * <p>Authentication is not left to the framework: every call resolves its {@link Principal} through {@link Principals}
 * (OIDC token in jwt mode; {@code authorization} and {@code x-tenant} metadata, captured by {@link GrpcAuthInterceptor},
 * in numu mode, where Quarkus OIDC is disabled and {@code @Authenticated} would refuse everything).
 */
@GrpcService
public class WayangGrpcService extends MutinyWayangGrpc.WayangImplBase {

    @Inject
    WayangApi api;
    @Inject
    Principals principals;
    @Inject
    GrpcPrincipals grpcPrincipals;

    @Override
    @Blocking
    public Uni<JsonReply> startRun(StartRunRequest r) {
        return call(p -> json(api.startRun(p, r.getProcessId(), r.getVersion() == 0 ? null : r.getVersion(),
                map(r.getInputJson()))));
    }

    @Override
    @Blocking
    public Uni<JsonReply> getRun(RunRef r) {
        return call(p -> json(api.getRun(p, r.getRunId())));
    }

    @Override
    @Blocking
    public Uni<Empty> cancelRun(RunRef r) {
        return Uni.createFrom().item(() -> {
            try {
                api.cancelRun(principals.current(), r.getRunId());
                return Empty.getDefaultInstance();
            } catch (ApiException e) {
                throw status(e);
            }
        });
    }

    @Override
    @Blocking
    public Uni<JsonReply> listTasks(ListRequest r) {
        return call(p -> json(api.listTasks(p, r.getLimit() <= 0 ? 100 : r.getLimit())));
    }

    @Override
    @Blocking
    public Uni<Empty> completeTask(CompleteTaskRequest r) {
        return Uni.createFrom().item(() -> {
            try {
                api.completeTask(principals.current(), r.getToken(), map(r.getOutputsJson()));
                return Empty.getDefaultInstance();
            } catch (ApiException e) {
                throw status(e);
            }
        });
    }

    @Override
    @Blocking
    public Uni<JsonReply> sendMessage(MessageRequest r) {
        return call(p -> json(Map.of("result", api.sendMessage(p, r.getName(), r.getCorrelationKey(), map(r.getPayloadJson())))));
    }

    @Override
    public Multi<JsonReply> watchRuns(WatchRequest r) {
        String runId = r.getRunId().isEmpty() ? null : r.getRunId();
        if (!grpcPrincipals.numuMode()) {
            try {
                return watch(principals.current(), runId);
            } catch (ApiException e) {
                return Multi.createFrom().failure(status(e));
            }
        }
        // numu mode: the raw credentials are read on the call thread; the identity lookup (a remote call) runs on a worker.
        String auth = GrpcAuthInterceptor.AUTHORIZATION.get();
        String tenant = GrpcAuthInterceptor.TENANT.get();
        return Uni.createFrom().item(() -> {
            try {
                return grpcPrincipals.resolve(auth, tenant);
            } catch (ApiException e) {
                throw status(e);
            }
        }).runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool())
                .onItem().transformToMulti(p -> watch(p, runId));
    }

    private Multi<JsonReply> watch(Principal p, String runId) {
        return Multi.createFrom().<JsonReply>emitter(em -> {
            try {
                AutoCloseable sub = api.subscribe(p, runId, e -> em.emit(json(e.toMap())));
                em.onTermination(() -> {
                    try {
                        sub.close();
                    } catch (Exception ignored) {
                        // best effort
                    }
                });
            } catch (ApiException e) {
                em.fail(status(e));
            }
        }, io.smallrye.mutiny.subscription.BackPressureStrategy.DROP);
    }

    private Uni<JsonReply> call(java.util.function.Function<Principal, JsonReply> f) {
        return Uni.createFrom().item(() -> {
            try {
                return f.apply(principals.current());
            } catch (ApiException e) {
                throw status(e);
            }
        });
    }

    private static JsonReply json(Object o) {
        return JsonReply.newBuilder().setJson(Json.write(Views.plain(o))).build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Object v = Json.read(json);
        if (!(v instanceof Map)) {
            throw Status.INVALID_ARGUMENT.withDescription("JSON object expected").asRuntimeException();
        }
        return (Map<String, Object>) v;
    }

    private static RuntimeException status(ApiException e) {
        Status s = switch (e.code()) {
            case BAD_REQUEST, VALIDATION_FAILED -> Status.INVALID_ARGUMENT;
            case UNAUTHORIZED -> Status.UNAUTHENTICATED;
            case FORBIDDEN -> Status.PERMISSION_DENIED;
            case NOT_FOUND -> Status.NOT_FOUND;
            case CONFLICT -> Status.FAILED_PRECONDITION;
            case TOO_MANY_REQUESTS -> Status.RESOURCE_EXHAUSTED;
            case UNAVAILABLE -> Status.UNAVAILABLE;
            case INTERNAL -> Status.INTERNAL;
        };
        return s.withDescription(e.getMessage()).asRuntimeException();
    }
}
