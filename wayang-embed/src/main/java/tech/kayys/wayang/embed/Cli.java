package tech.kayys.wayang.embed;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

import tech.kayys.wayang.runtime.json.Json;

/**
 * The command line of a release. The generated {@code App} builds the runtime and hands it here.
 *
 * <pre>
 * serve                          the HTTP service, with the webhooks and pollers of the release (WAYANG_PORT=8080, WAYANG_BIND=0.0.0.0, WAYANG_API_KEY | WAYANG_API_KEY_FILE)
 * run &lt;process&gt; [--input FILE|-] [--wait-seconds N]
 *                                run once, print the run as JSON; exit 0 completed, 1 failed, 2 still waiting
 * check                          list what this release needs and what is missing; exit 0 only when nothing is
 * info                           the release: processes, versions, bundle hash
 * doctor [--live] [--json]       what is wrong or risky about this setup (missing secrets, open endpoints, no spend cap, chat triggers without an allow-list...);
 *                                --live also asks each model provider and each Telegram/Slack token whether it is accepted. Exit 0 unless something failed
 * </pre>
 */
public final class Cli {

    private Cli() {
    }

    public static int run(String[] args, ReleaseEngine rt, Function<String, String> env, InputStream stdin, PrintStream out, PrintStream err) {
        return run(args, rt, null, env, stdin, out, err);
    }

    /** @param triggers the release's webhook routes and poll loops, served by {@code serve} only; may be null */
    public static int run(String[] args, ReleaseEngine rt, ReleaseTriggers triggers, Function<String, String> env, InputStream stdin,
            PrintStream out, PrintStream err) {
        return run(args, rt, triggers, env, null, stdin, out, err);
    }

    /** @param secrets where {@code doctor --live} finds the keys and tokens to try; null: it can only say they are not available */
    public static int run(String[] args, ReleaseEngine rt, ReleaseTriggers triggers, Function<String, String> env, tech.kayys.wayang.runtime.SecretResolver secrets,
            InputStream stdin, PrintStream out, PrintStream err) {
        return run(args, rt, triggers, env, secrets, Doctor.jdkHttp(), stdin, out, err);
    }

    static int run(String[] args, ReleaseEngine rt, ReleaseTriggers triggers, Function<String, String> env, tech.kayys.wayang.runtime.SecretResolver secrets,
            Doctor.Http probes, InputStream stdin, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            err.println("usage: serve | run <process> [--input FILE|-] [--wait-seconds N] | check | info | doctor [--live] [--json]");
            return 64;
        }
        try {
            switch (args[0]) {
                case "info" -> {
                    out.println(Json.write(Map.of("name", rt.bundle().name(), "bundleSha256", rt.bundle().sha256(), "processes", rt.processIds())));
                    return 0;
                }
                case "check" -> {
                    var missing = rt.missingSecrets();
                    out.println(Json.write(Map.of("missingSecrets", missing, "requiredConfig", rt.bundle().requiredConfig())));
                    return missing.isEmpty() ? 0 : 1;
                }
                case "doctor" -> {
                    boolean live = false;
                    boolean json = false;
                    for (int i = 1; i < args.length; i++) {
                        switch (args[i]) {
                            case "--live" -> live = true;
                            case "--json" -> json = true;
                            default -> throw new IllegalArgumentException("unknown option: " + args[i]);
                        }
                    }
                    var findings = new Doctor(rt, env, secrets, probes).run(live);
                    out.print(json ? Json.write(findings.stream().map(Doctor.Finding::toMap).toList()) + "\n" : Doctor.text(findings));
                    return Doctor.exitCode(findings);
                }
                case "run" -> {
                    return runOnce(args, rt, stdin, out, err);
                }
                case "serve" -> {
                    return serve(rt, triggers, env, out, err);
                }
                default -> {
                    err.println("unknown command: " + args[0]);
                    return 64;
                }
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            err.println(e.getMessage());
            return 64;
        }
    }

    private static int runOnce(String[] a, ReleaseEngine rt, InputStream stdin, PrintStream out, PrintStream err) {
        if (a.length < 2) {
            throw new IllegalArgumentException("usage: run <process> [--input FILE|-] [--wait-seconds N]");
        }
        String process = a[1];
        String inputArg = null;
        int wait = 0;
        for (int i = 2; i < a.length; i++) {
            if (a[i].equals("--input") && i + 1 < a.length) {
                inputArg = a[++i];
            } else if (a[i].equals("--wait-seconds") && i + 1 < a.length) {
                try {
                    wait = Math.max(0, Math.min(3600, Integer.parseInt(a[++i])));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("--wait-seconds needs a number");
                }
            } else {
                throw new IllegalArgumentException("unknown option: " + a[i]);
            }
        }
        Map<String, Object> input = Map.of();
        if (inputArg != null) {
            try {
                byte[] b = inputArg.equals("-") ? stdin.readNBytes(1_000_001) : Files.readAllBytes(Path.of(inputArg));
                if (b.length > 1_000_000) {
                    throw new IllegalArgumentException("The input is larger than 1 MB");
                }
                Object o = Json.read(new String(b, StandardCharsets.UTF_8));
                if (!(o instanceof Map<?, ?> m)) {
                    throw new IllegalArgumentException("The input must be a JSON object");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> mm = (Map<String, Object>) m;
                input = mm;
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read the input");
            }
        }
        var info = rt.start(process, input);
        long until = System.currentTimeMillis() + wait * 1000L;
        while (info.status().equals("WAITING") && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            rt.fireDueTimers();
            info = rt.get(info.id()).orElse(info);
        }
        out.println(Json.write(info.toMap()));
        return switch (info.status()) {
            case "COMPLETED" -> 0;
            case "WAITING" -> 2;
            default -> 1;
        };
    }

    private static int serve(ReleaseEngine rt, ReleaseTriggers triggers, Function<String, String> env, PrintStream out, PrintStream err) {
        HttpService svc = startService(rt, triggers, env, out, err);
        Runtime.getRuntime().addShutdownHook(new Thread(svc::close));
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    /**
     * Starts the HTTP service of a release from the environment (WAYANG_API_KEY | WAYANG_API_KEY_FILE, WAYANG_BIND, WAYANG_PORT,
     * WAYANG_REQUIRE_SECRETS) and returns it, already listening.
     *
     * @throws IllegalStateException when it cannot start (no key on a public address, bad port, port taken, a required secret missing)
     */
    public static HttpService startService(ReleaseEngine rt, ReleaseTriggers triggers, Function<String, String> env, PrintStream out, PrintStream err) {
        String key = env.apply("WAYANG_API_KEY");
        String keyFile = env.apply("WAYANG_API_KEY_FILE");
        if ((key == null || key.isBlank()) && keyFile != null && !keyFile.isBlank()) {
            try {
                key = Files.readString(Path.of(keyFile), StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read WAYANG_API_KEY_FILE");
            }
        }
        String bind = env.apply("WAYANG_BIND") == null ? "0.0.0.0" : env.apply("WAYANG_BIND");
        int port;
        try {
            port = env.apply("WAYANG_PORT") == null ? 8080 : Integer.parseInt(env.apply("WAYANG_PORT"));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("WAYANG_PORT must be a number");
        }
        if ("true".equalsIgnoreCase(env.apply("WAYANG_REQUIRE_SECRETS")) && !rt.missingSecrets().isEmpty()) {
            throw new IllegalStateException("Missing secrets: " + rt.missingSecrets());
        }
        HttpService svc;
        try {
            svc = new HttpService(rt, key, HttpService.address(bind, port), 1_000_000).withTriggers(triggers).start();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot listen on " + bind + ":" + port + " (" + e.getClass().getSimpleName() + ")");
        }
        out.println("wayang release " + rt.bundle().name() + " listening on " + bind + ":" + svc.port() + (key == null ? " (no API key)" : ""));
        if (!rt.missingSecrets().isEmpty()) {
            err.println("warning: " + rt.missingSecrets().size() + " required secret(s) are missing; /readyz reports not ready (run `check`)");
        }
        return svc;
    }
}
