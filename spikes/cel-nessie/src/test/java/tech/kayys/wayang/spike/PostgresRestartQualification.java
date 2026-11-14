package tech.kayys.wayang.spike;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Two-process PostgreSQL restart qualification. Invoke with "seed" before
 * restarting the database container and "verify" after it becomes healthy.
 */
public public final class PostgresRestartQualification {
    private static final UUID EVENT_ID = UUID.fromString("4f8dd0b5-61d1-4c77-9b91-000000000041");
    private static final String STREAM = "physical-restart-qualification";
    private static final Instant NOW = Instant.parse("2026-11-14T00:00:00Z");

    private PostgresRestartQualification() { }

    public static void main(String[] args) {
        if (args.length != 1 || !(args[0].equals("seed") || args[0].equals("verify"))) {
            throw new IllegalArgumentException("usage: PostgresRestartQualification seed|verify");
        }
        var source = new PGSimpleDataSource();
        source.setUrl(required("WAYANG_TEST_POSTGRES_URL"));
        source.setUser(System.getenv().getOrDefault("WAYANG_TEST_POSTGRES_USER", "postgres"));
        source.setPassword(System.getenv().getOrDefault("WAYANG_TEST_POSTGRES_PASSWORD", "postgres"));
        var store = new PostgresPressureEventStore(source, new Codec());

        if (args[0].equals("seed")) {
            var envelope = new PressureEventEnvelope(EVENT_ID, STREAM, 1, NOW, event());
            PressureEventAppendResult appended = store.append(envelope);
            if (appended != PressureEventAppendResult.APPENDED) {
                throw new IllegalStateException("seed event was not newly appended");
            }
            var claimed = store.claim("restart-worker-before", 1, NOW, Duration.ofSeconds(5));
            if (claimed.size() != 1 || !claimed.getFirst().envelope().eventId().equals(EVENT_ID)) {
                throw new IllegalStateException("seed event could not be leased before restart");
            }
            System.out.println("SEEDED event=" + EVENT_ID + " token=" + claimed.getFirst().claimToken());
            return;
        }

        var health = store.inspectHealth();
        if (!health.databaseAvailable() || health.schemaVersion() != 1 || health.pendingEvents() != 1) {
            throw new IllegalStateException("database restart did not preserve the pending event: " + health);
        }
        if (!store.claim("restart-worker-too-early", 1, NOW.plusSeconds(1),
                Duration.ofSeconds(30)).isEmpty()) {
            throw new IllegalStateException("live lease was lost during PostgreSQL restart");
        }
        var recovered = store.claim("restart-worker-after-expiry", 1, NOW.plusSeconds(5),
                Duration.ofSeconds(30));
        if (recovered.size() != 1
                || !recovered.getFirst().envelope().eventId().equals(EVENT_ID)
                || recovered.getFirst().deliveryAttempts() != 2) {
            throw new IllegalStateException("event was not recovered with incremented attempt count");
        }
        if (!store.acknowledge("restart-worker-after-expiry", EVENT_ID,
                recovered.getFirst().claimToken(), NOW.plusSeconds(6))) {
            throw new IllegalStateException("recovered event could not be acknowledged");
        }
        System.out.println("RECOVERED_AND_ACKNOWLEDGED event=" + EVENT_ID);
    }

    private static EvaluationPressureEvent event() {
        return new EvaluationPressureEvent(NOW,
                EvaluationPressureAssessment.Level.NORMAL,
                EvaluationPressureAssessment.Level.ELEVATED,
                EvaluationPressureAssessment.Level.ELEVATED,
                List.of(EvaluationPressureAssessment.Reason.PROVIDER_WORKERS_ELEVATED));
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException(key + " is required");
        return value;
    }

    private static final class Codec implements PressureEventCodec {
        @Override
        public byte[] encode(EvaluationPressureEvent value) {
            try {
                var bytes = new ByteArrayOutputStream();
                try (var out = new DataOutputStream(bytes)) {
                    out.writeLong(value.occurredAt().getEpochSecond());
                    out.writeInt(value.occurredAt().getNano());
                    out.writeUTF(value.previousLevel().name());
                    out.writeUTF(value.currentLevel().name());
                    out.writeUTF(value.observedLevel().name());
                    out.writeInt(value.observedReasons().size());
                    for (var reason : value.observedReasons()) out.writeUTF(reason.name());
                }
                return bytes.toByteArray();
            } catch (IOException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        @Override
        public EvaluationPressureEvent decode(byte[] payload) {
            try (var in = new DataInputStream(new ByteArrayInputStream(payload))) {
                Instant at = Instant.ofEpochSecond(in.readLong(), in.readInt());
                var previous = EvaluationPressureAssessment.Level.valueOf(in.readUTF());
                var current = EvaluationPressureAssessment.Level.valueOf(in.readUTF());
                var observed = EvaluationPressureAssessment.Level.valueOf(in.readUTF());
                int count = in.readInt();
                if (count < 0 || count > EvaluationPressureAssessment.Reason.values().length) {
                    throw new IllegalArgumentException("invalid reason count");
                }
                var reasons = new ArrayList<EvaluationPressureAssessment.Reason>(count);
                for (int i = 0; i < count; i++) {
                    reasons.add(EvaluationPressureAssessment.Reason.valueOf(in.readUTF()));
                }
                if (in.available() != 0) throw new IllegalArgumentException("trailing codec bytes");
                return new EvaluationPressureEvent(at, previous, current, observed, reasons);
            } catch (IOException failure) {
                throw new IllegalArgumentException("invalid pressure event payload", failure);
            }
        }
    }
}
