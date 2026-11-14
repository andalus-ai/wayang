package tech.kayys.wayang.spike;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

@EnabledIfEnvironmentVariable(named = "WAYANG_TEST_POSTGRES_URL", matches = ".+")
/**
 * Real PostgreSQL qualification tests. The dedicated workflow supplies the
 * database; local runs may use the same WAYANG_TEST_POSTGRES_* variables.
 */
class PostgresPressureEventStoreTest {
    private static final Instant NOW = Instant.parse("2026-11-13T09:00:00.123456789Z");
    private DataSource dataSource;
    private PressureEventCodec codec;
    private PostgresPressureEventStore store;

    @BeforeEach
    void setUp() throws Exception {
        String url = requiredEnv("WAYANG_TEST_POSTGRES_URL");
        PGSimpleDataSource postgres = new PGSimpleDataSource();
        postgres.setUrl(url);
        postgres.setUser(System.getenv().getOrDefault("WAYANG_TEST_POSTGRES_USER", "postgres"));
        postgres.setPassword(System.getenv().getOrDefault("WAYANG_TEST_POSTGRES_PASSWORD", "postgres"));
        dataSource = postgres;
        codec = new BinaryPressureEventCodec();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var migration = getClass().getClassLoader()
                     .getResourceAsStream("db/migration/V1__pressure_event_store.sql")) {
            assertNotNull(migration, "pressure event migration must be on the test classpath");
            String sql = new String(migration.readAllBytes(), StandardCharsets.UTF_8);
            for (String command : sql.split(";")) {
                if (!command.isBlank()) statement.execute(command);
            }
            statement.execute("TRUNCATE TABLE wayang_pressure_event");
        }
        store = new PostgresPressureEventStore(dataSource, codec);
    }

    @Test
    void appendIsIdempotentAndConflictingIdentityOrSequenceFailsClosed() {
        PressureEventEnvelope event = envelope("append-stream", 1);
        assertEquals(PressureEventAppendResult.APPENDED, store.append(event));
        assertEquals(PressureEventAppendResult.DUPLICATE,
                new PostgresPressureEventStore(dataSource, codec).append(event));

        PressureEventEnvelope sameIdDifferentContent = new PressureEventEnvelope(
                event.eventId(), event.streamId(), 1, event.recordedAt(),
                pressureEvent(EvaluationPressureAssessment.Level.SATURATED));
        assertThrows(IllegalStateException.class, () -> store.append(sameIdDifferentContent));

        PressureEventEnvelope sameSequenceDifferentId = envelope("append-stream", 1);
        assertThrows(IllegalStateException.class, () -> store.append(sameSequenceDifferentId));
    }

    @Test
    void claimSurvivesStoreReconstructionAndAcknowledgementIsDurable() {
        PressureEventEnvelope event = envelope("recovery-stream", 1);
        store.append(event);
        ClaimedPressureEvent first = store.claim("worker-a", 1, NOW, Duration.ofSeconds(30)).getFirst();
        assertTrue(store.release("worker-a", event.eventId(), first.claimToken()));

        PostgresPressureEventStore restarted = new PostgresPressureEventStore(dataSource, codec);
        ClaimedPressureEvent recovered = restarted.claim(
                "worker-b", 1, NOW.plusSeconds(1), Duration.ofSeconds(30)).getFirst();
        assertEquals(event.eventId(), recovered.envelope().eventId());
        assertEquals(2, recovered.deliveryAttempts());
        assertTrue(restarted.acknowledge("worker-b", event.eventId(),
                recovered.claimToken(), NOW.plusSeconds(2)));
        assertTrue(restarted.claim("worker-c", 1, NOW.plusSeconds(3), Duration.ofSeconds(30)).isEmpty());
    }

    @Test
    void concurrentClaimersNeverReceiveTheSameEvent() throws Exception {
        for (int i = 1; i <= 20; i++) store.append(envelope("concurrent-stream", i));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> claimTogether("worker-1", ready, start));
            var second = workers.submit(() -> claimTogether("worker-2", ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<ClaimedPressureEvent> a = first.get(10, TimeUnit.SECONDS);
            List<ClaimedPressureEvent> b = second.get(10, TimeUnit.SECONDS);
            HashSet<UUID> ids = new HashSet<>();
            a.forEach(item -> assertTrue(ids.add(item.envelope().eventId())));
            b.forEach(item -> assertTrue(ids.add(item.envelope().eventId())));
            assertEquals(20, ids.size());
            assertEquals(20, a.size() + b.size());
        }
    }

    @Test
    void expiredLeaseIsReclaimedWithFreshFenceAndStaleWorkerCannotAckOrRelease() {
        PressureEventEnvelope event = envelope("fencing-stream", 1);
        store.append(event);
        ClaimedPressureEvent stale = store.claim(
                "worker-old", 1, NOW, Duration.ofSeconds(5)).getFirst();
        ClaimedPressureEvent current = store.claim(
                "worker-new", 1, NOW.plusSeconds(5), Duration.ofSeconds(30)).getFirst();

        assertNotEquals(stale.claimToken(), current.claimToken());
        assertFalse(store.acknowledge("worker-old", event.eventId(),
                stale.claimToken(), NOW.plusSeconds(6)));
        assertFalse(store.release("worker-old", event.eventId(), stale.claimToken()));
        assertTrue(store.acknowledge("worker-new", event.eventId(),
                current.claimToken(), NOW.plusSeconds(7)));
    }

    @Test
    void decodeFailureRollsBackClaimAndLeavesEventAvailable() {
        PressureEventEnvelope event = envelope("rollback-stream", 1);
        store.append(event);
        PressureEventCodec brokenDecoder = new PressureEventCodec() {
            @Override public byte[] encode(EvaluationPressureEvent value) {
                return codec.encode(value);
            }
            @Override public EvaluationPressureEvent decode(byte[] payload) {
                throw new IllegalArgumentException("deliberate test decode failure");
            }
        };
        var brokenStore = new PostgresPressureEventStore(dataSource, brokenDecoder);
        assertThrows(IllegalArgumentException.class,
                () -> brokenStore.claim("broken-worker", 1, NOW, Duration.ofSeconds(30)));

        ClaimedPressureEvent available = store.claim(
                "healthy-worker", 1, NOW, Duration.ofSeconds(30)).getFirst();
        assertEquals(event.eventId(), available.envelope().eventId());
        assertEquals(1, available.deliveryAttempts());
    }



    @Test
    void appendRetryAfterCommitAcknowledgementWasLostIsIdempotent() {
        PressureEventEnvelope event = envelope("ambiguous-append", 1);
        DataSource uncertain = failOnceAfterCommit(dataSource);
        var uncertainStore = new PostgresPressureEventStore(uncertain, codec);

        assertThrows(IllegalStateException.class, () -> uncertainStore.append(event));
        assertEquals(PressureEventAppendResult.DUPLICATE, store.append(event));
        assertEquals(1, store.claim("recovery-worker", 10, NOW,
                Duration.ofSeconds(30)).size());
    }

    @Test
    void claimCommitOutcomeCanBeRecoveredOnlyAfterLeaseExpiry() throws Exception {
        PressureEventEnvelope event = envelope("ambiguous-claim", 1);
        store.append(event);
        var uncertainStore = new PostgresPressureEventStore(
                failOnceAfterCommit(dataSource, null), codec);

        assertThrows(IllegalStateException.class,
                () -> uncertainStore.claim("worker-uncertain", 1, NOW, Duration.ofSeconds(5)));
        UUID committedToken = readClaimToken(event.eventId());
        assertTrue(store.claim("worker-other", 1, NOW.plusSeconds(1),
                Duration.ofSeconds(30)).isEmpty());

        ClaimedPressureEvent recovered = store.claim("worker-recovered", 1,
                NOW.plusSeconds(5), Duration.ofSeconds(30)).getFirst();
        assertEquals(event.eventId(), recovered.envelope().eventId());
        assertNotEquals(committedToken, recovered.claimToken());
        assertEquals(2, recovered.deliveryAttempts());
    }

    @Test
    void acknowledgementCommitOutcomeCanBeRecoveredByObservingTerminalState() {
        PressureEventEnvelope event = envelope("ambiguous-ack", 1);
        store.append(event);
        ClaimedPressureEvent claim = store.claim("worker-ack", 1, NOW,
                Duration.ofSeconds(30)).getFirst();
        var uncertainStore = new PostgresPressureEventStore(
                failOnceAfterStatement(dataSource, "SET acknowledged_at"), codec);

        assertThrows(IllegalStateException.class, () -> uncertainStore.acknowledge(
                "worker-ack", event.eventId(), claim.claimToken(), NOW.plusSeconds(1)));
        assertFalse(store.acknowledge("worker-ack", event.eventId(),
                claim.claimToken(), NOW.plusSeconds(2)));
        assertTrue(store.claim("worker-next", 1, NOW.plusSeconds(3),
                Duration.ofSeconds(30)).isEmpty());
    }

    @Test
    void releaseCommitOutcomeCanBeRecoveredByReclaimingWithFreshFence() {
        PressureEventEnvelope event = envelope("ambiguous-release", 1);
        store.append(event);
        ClaimedPressureEvent oldClaim = store.claim("worker-release", 1, NOW,
                Duration.ofSeconds(30)).getFirst();
        var uncertainStore = new PostgresPressureEventStore(
                failOnceAfterStatement(dataSource, "SET consumer_id = NULL"), codec);

        assertThrows(IllegalStateException.class, () -> uncertainStore.release(
                "worker-release", event.eventId(), oldClaim.claimToken()));
        ClaimedPressureEvent next = store.claim("worker-next", 1, NOW.plusSeconds(1),
                Duration.ofSeconds(30)).getFirst();
        assertNotEquals(oldClaim.claimToken(), next.claimToken());
        assertEquals(2, next.deliveryAttempts());
        assertFalse(store.release("worker-release", event.eventId(), oldClaim.claimToken()));
    }

    private UUID readClaimToken(UUID eventId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT claim_token FROM wayang_pressure_event WHERE event_id = ?")) {
            statement.setObject(1, eventId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getObject(1, UUID.class);
            }
        }
    }

    private static DataSource failOnceAfterCommit(DataSource delegate) {
        AtomicBoolean armed = new AtomicBoolean(true);
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    Object value = invoke(delegate, method, args);
                    if (!method.getName().equals("getConnection")) return value;
                    Connection connection = (Connection) value;
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (connectionProxy, connectionMethod, connectionArgs) -> {
                                Object result = invoke(connection, connectionMethod, connectionArgs);
                                if (connectionMethod.getName().equals("commit")
                                        && armed.compareAndSet(true, false)) {
                                    throw new SQLException("injected lost commit response", "08006");
                                }
                                return result;
                            });
                });
    }

    private static DataSource failOnceAfterStatement(DataSource delegate, String sqlFragment) {
        AtomicBoolean armed = new AtomicBoolean(true);
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    Object value = invoke(delegate, method, args);
                    if (!method.getName().equals("getConnection")) return value;
                    Connection connection = (Connection) value;
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (connectionProxy, connectionMethod, connectionArgs) -> {
                                Object statementValue = invoke(connection, connectionMethod, connectionArgs);
                                if (!connectionMethod.getName().equals("prepareStatement")
                                        || connectionArgs == null || connectionArgs.length == 0
                                        || !(connectionArgs[0] instanceof String sql)
                                        || !sql.contains(sqlFragment)) return statementValue;
                                PreparedStatement statement = (PreparedStatement) statementValue;
                                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                        new Class<?>[]{PreparedStatement.class}, (statementProxy, statementMethod, statementArgs) -> {
                                            Object result = invoke(statement, statementMethod, statementArgs);
                                            if (statementMethod.getName().equals("executeUpdate")
                                                    && armed.compareAndSet(true, false)) {
                                                throw new SQLException("injected lost statement response", "08006");
                                            }
                                            return result;
                                        });
                            });
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    @Test
    void retentionDeletesOnlyAcknowledgedRowsAndHonorsBatchLimit() {
        PressureEventEnvelope acknowledgedOne = envelope("retention-ack", 1);
        PressureEventEnvelope acknowledgedTwo = envelope("retention-ack", 2);
        PressureEventEnvelope acknowledgedAtCutoff = envelope("retention-cutoff", 1);
        PressureEventEnvelope pending = envelope("retention-pending", 1);
        PressureEventEnvelope claimed = envelope("retention-claimed", 1);
        store.append(acknowledgedOne);
        store.append(acknowledgedTwo);
        store.append(acknowledgedAtCutoff);
        store.append(pending);
        store.append(claimed);

        ClaimedPressureEvent first = store.claim("retention-worker", 1, NOW, Duration.ofSeconds(30)).getFirst();
        assertTrue(store.acknowledge("retention-worker", first.envelope().eventId(),
                first.claimToken(), NOW.plusSeconds(1)));
        ClaimedPressureEvent second = store.claim("retention-worker", 1,
                NOW.plusSeconds(1), Duration.ofSeconds(30)).getFirst();
        assertTrue(store.acknowledge("retention-worker", second.envelope().eventId(),
                second.claimToken(), NOW.plusSeconds(1)));
        ClaimedPressureEvent active = store.claim("active-worker", 1,
                NOW.plusSeconds(1), Duration.ofSeconds(30)).getFirst();
        assertEquals(claimed.eventId(), active.envelope().eventId());
        ClaimedPressureEvent exactCutoff = store.claim("cutoff-worker", 1,
                NOW.plusSeconds(1), Duration.ofSeconds(30)).getFirst();
        assertEquals(acknowledgedAtCutoff.eventId(), exactCutoff.envelope().eventId());
        assertTrue(store.acknowledge("cutoff-worker", exactCutoff.envelope().eventId(),
                exactCutoff.claimToken(), NOW.plusSeconds(2)));

        PressureEventRetention retention = store;
        assertEquals(1, retention.purgeAcknowledgedBefore(NOW.plusSeconds(2), 1));
        assertEquals(1, retention.purgeAcknowledgedBefore(NOW.plusSeconds(2), 10));
        assertEquals(0, retention.purgeAcknowledgedBefore(NOW.plusSeconds(2), 10));
        assertEquals(1, retention.purgeAcknowledgedBefore(NOW.plusSeconds(3), 10));

        assertTrue(store.acknowledge("active-worker", active.envelope().eventId(),
                active.claimToken(), NOW.plusSeconds(3)));
        assertEquals(1, store.claim("pending-worker", 10,
                NOW.plusSeconds(4), Duration.ofSeconds(30)).size());
    }

    @Test
    void healthReportsSchemaAndAggregateBacklogWithoutEventDetails() {
        store.append(envelope("health-stream", 1));
        PressureEventStoreHealth health = store.inspectHealth();
        assertTrue(health.databaseAvailable());
        assertEquals(1, health.schemaVersion());
        assertEquals(1, health.pendingEvents());
        assertEquals(0, health.expiredLeases());
        assertNotNull(health.oldestPendingAt());
        assertNull(health.failureCode());
    }

    @Test
    void healthFailsClosedWhenSchemaMetadataIsMissing() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE wayang_pressure_event_schema");
        }
        PressureEventStoreHealth health = store.inspectHealth();
        assertFalse(health.databaseAvailable());
        assertEquals("SCHEMA_UNAVAILABLE", health.failureCode());
    }


    @Test
    void healthFailsClosedOnUnsupportedSchemaVersion() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("UPDATE wayang_pressure_event_schema SET schema_version = 99 WHERE singleton = TRUE");
        }
        PressureEventStoreHealth health = store.inspectHealth();
        assertFalse(health.databaseAvailable());
        assertEquals(99, health.schemaVersion());
        assertEquals("SCHEMA_VERSION_UNSUPPORTED", health.failureCode());
    }

    private List<ClaimedPressureEvent> claimTogether(
            String consumer, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start gate timed out");
        return new PostgresPressureEventStore(dataSource, codec)
                .claim(consumer, 20, NOW, Duration.ofSeconds(30));
    }

    private static PressureEventEnvelope envelope(String stream, long sequence) {
        return new PressureEventEnvelope(UUID.randomUUID(), stream, sequence,
                NOW.plusNanos(sequence), pressureEvent(EvaluationPressureAssessment.Level.ELEVATED));
    }

    private static EvaluationPressureEvent pressureEvent(EvaluationPressureAssessment.Level current) {
        return new EvaluationPressureEvent(NOW,
                EvaluationPressureAssessment.Level.NORMAL, current, current,
                List.of(EvaluationPressureAssessment.Reason.PROVIDER_WORKERS_ELEVATED));
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be configured by the PostgreSQL test workflow");
        }
        return value;
    }

    /** Small deterministic test-only codec; production codecs remain host-owned. */
    private static final class BinaryPressureEventCodec implements PressureEventCodec {
        @Override
        public byte[] encode(EvaluationPressureEvent event) {
            try {
                var bytes = new ByteArrayOutputStream();
                try (var out = new DataOutputStream(bytes)) {
                    out.writeLong(event.occurredAt().getEpochSecond());
                    out.writeInt(event.occurredAt().getNano());
                    out.writeUTF(event.previousLevel().name());
                    out.writeUTF(event.currentLevel().name());
                    out.writeUTF(event.observedLevel().name());
                    out.writeInt(event.observedReasons().size());
                    for (var reason : event.observedReasons()) out.writeUTF(reason.name());
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
                throw new IllegalArgumentException("invalid test pressure event payload", failure);
            }
        }
    }
}
