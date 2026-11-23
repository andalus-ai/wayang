package tech.kayys.wayang.rest;

import java.nio.file.Path;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import tech.kayys.wayang.api.StudioFiles;

/**
 * All-in-one: serves the Studio web build from the API process when {@code wayang.studio.dir} names a {@code flutter build web}
 * directory (off otherwise). Only GET/HEAD, and never the API's own prefixes ({@code /api/}, {@code /ws/}, {@code /hooks/},
 * {@code /oauth/}, {@code /admin/}, {@code /openapi}, {@code /q/}), which fall through to their handlers. Files come from
 * {@link StudioFiles} (confined to the directory); the page carries its own content-security-policy because the API's
 * {@code default-src 'none'} would block it. The Studio is static: it holds no data and authenticates against the API as usual.
 */
@ApplicationScoped
public class StudioRoute {

    private static final String[] RESERVED = {"/api/", "/ws/", "/hooks/", "/oauth/", "/admin/", "/openapi", "/q/"};

    @ConfigProperty(name = "wayang.studio.dir")
    Optional<String> dir;

    void register(@Observes Router router) {
        if (dir.isEmpty() || dir.get().isBlank()) {
            return;
        }
        StudioFiles files = new StudioFiles(Path.of(dir.get())); // fails at start-up on a wrong directory
        router.route().order(-100).handler(ctx -> handle(files, ctx));
    }

    static boolean reserved(String path) {
        for (String r : RESERVED) {
            if (path.startsWith(r) || path.equals(r.substring(0, r.length() - (r.endsWith("/") ? 1 : 0)))) {
                return true;
            }
        }
        return false;
    }

    private static void handle(StudioFiles files, RoutingContext ctx) {
        HttpMethod m = ctx.request().method();
        String path = ctx.request().path();
        if ((m != HttpMethod.GET && m != HttpMethod.HEAD) || reserved(path)) {
            ctx.next();
            return;
        }
        var served = files.resolve(path);
        if (served.isEmpty()) {
            ctx.response().setStatusCode(404).putHeader("Content-Type", "text/plain; charset=utf-8").end("Not found");
            return;
        }
        var f = served.get();
        ctx.response().putHeader("Content-Type", f.contentType())
                .putHeader("Cache-Control", StudioFiles.cacheControl(f.file()))
                .putHeader("Content-Security-Policy", StudioFiles.CSP)
                .putHeader("X-Content-Type-Options", "nosniff")
                .putHeader("X-Frame-Options", "DENY")
                .putHeader("Referrer-Policy", "no-referrer")
                .sendFile(f.file().toString());
    }
}
