package tech.kayys.wayang.spike;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * PostgreSQL implementation of the pressure-event persistence SPI.
 *
 * <p>Uses only JDBC APIs; the host supplies its PostgreSQL DataSource and a
 * deterministic, versioned payload codec. Apply the migration in
 * {@code db/migration/V1__pressure_event_store.sql} before use.</p>
 *
 * <p>All time arguments are supplied by the caller to preserve the SPI
 * contract. Multi-instance deployments must use a shared, disciplined clock
 * (or a future database-clock SPI revision).</p>
 */
public final class PostgresPressureEventStore implements PressureEventStore, PressureEventRetention {
    private static final String INSERT = """
            INSERT INTO wayang_pressure_event
              (event_id, stream_id, sequence_no, recorded_at, recorded_at_nano, payload)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """;

    private static final String FIND_CONFLICT = """
            SELECT event_id, stream_id, sequence_no, recorded_at, recorded_at_nano, payload
              FROM wayang_pressure_event
             WHERE event_id = ? OR (stream_id = ? AND sequence_no = ?)
            """;

    private static final String CLAIM = """
            SELECT event_id, stream_id, sequence_no, recorded_at, recorded_at_nano, payload, delivery_attempts
              FROM wayang_pressure_event
             WHERE acknowledged_at IS NULL
               AND (claim_token IS NULL OR lease_expires_at <= ?)
             ORDER BY stream_id, sequence_no
             LIMIT ?
             FOR UPDATE SKIP LOCKED
            """;

    private final DataSource dataSource;
    private final PressureEventCodec codec;

    public PostgresPressureEventStore(DataSource dataSource, PressureEventCodec codec) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public PressureEventAppendResult append(PressureEventEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        byte[] payload = requirePayload(codec.encode(envelope.event()));
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int inserted;
                try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                    statement.setObject(1, envelope.eventId());
                    statement.setString(2, envelope.streamId());
                    statement.setLong(3, envelope.sequence());
                    statement.setTimestamp(4, Timestamp.from(envelope.recordedAt()));
                    statement.setInt(5, envelope.recordedAt().getNano());
                    statement.setBytes(6, payload);
                    inserted = statement.executeUpdate();
                }
                if (inserted == 1) {
                    connection.commit();
                    return PressureEventAppendResult.APPENDED;
                }
                boolean exactDuplicate = false;
                try (PreparedStatement statement = connection.prepareStatement(FIND_CONFLICT)) {
                    statement.setObject(1, envelope.eventId());
                    statement.setString(2, envelope.streamId());
                    statement.setLong(3, envelope.sequence());
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            UUID storedId = rows.getObject("event_id", UUID.class);
                            String storedStream = rows.getString("stream_id");
                            long storedSequence = rows.getLong("sequence_no");
                            Instant storedAt = Instant.ofEpochSecond(
                                    rows.getTimestamp("recorded_at").toInstant().getEpochSecond(),
                                    rows.getInt("recorded_at_nano"));
                            byte[] storedPayload = rows.getBytes("payload");
                            if (storedId.equals(envelope.eventId())
                                    && storedStream.equals(envelope.streamId())
                                    && storedSequence == envelope.sequence()
                                    && storedAt.equals(envelope.recordedAt())
                                    && java.util.Arrays.equals(storedPayload, payload)) {
                                exactDuplicate = true;
                            } else {
                                throw new IllegalStateException(
                                        "pressure event identity or stream sequence conflict");
                            }
                        }
                    }
                }
                if (!exactDuplicate) {
                    throw new IllegalStateException("conflicting pressure event row was not visible");
                }
                connection.commit();
                return PressureEventAppendResult.DUPLICATE;
            } catch (RuntimeException | SQLException failure) {
                rollback(connection, failure);
                throw failure;
            }
        } catch (SQLException failure) {
            throw persistenceFailure("append", failure);
        }
    }

    @Override
    public List<ClaimedPressureEvent> claim(
            String consumerId, int maxEvents, Instant now, Duration leaseDuration) {
        requireText(consumerId, "consumerId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (maxEvents < 1) throw new IllegalArgumentException("maxEvents must be positive");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        Instant expiresAt = now.plus(leaseDuration);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var result = new ArrayList<ClaimedPressureEvent>();
                var candidates = new ArrayList<ClaimCandidate>();
                try (PreparedStatement select = connection.prepareStatement(CLAIM)) {
                    select.setTimestamp(1, Timestamp.from(now));
                    select.setInt(2, maxEvents);
                    try (ResultSet rows = select.executeQuery()) {
                        while (rows.next()) {
                            candidates.add(new ClaimCandidate(
                                    rows.getObject("event_id", UUID.class),
                                    rows.getString("stream_id"),
                                    rows.getLong("sequence_no"),
                                    Instant.ofEpochSecond(
                                            rows.getTimestamp("recorded_at").toInstant().getEpochSecond(),
                                            rows.getInt("recorded_at_nano")),
                                    rows.getBytes("payload"),
                                    rows.getInt("delivery_attempts")));
                        }
                    }
                }
                for (var candidate : candidates) {
                    UUID token = UUID.randomUUID();
                    int attempts = Math.addExact(candidate.deliveryAttempts(), 1);
                    var envelope = new PressureEventEnvelope(candidate.eventId(),
                            candidate.streamId(), candidate.sequence(), candidate.recordedAt(),
                            codec.decode(candidate.payload()));
                    try (PreparedStatement update = connection.prepareStatement("""
                            UPDATE wayang_pressure_event
                               SET consumer_id = ?, claim_token = ?,
                                   lease_expires_at = ?, delivery_attempts = ?
                             WHERE event_id = ?
                            """)) {
                        update.setString(1, consumerId);
                        update.setObject(2, token);
                        update.setTimestamp(3, Timestamp.from(expiresAt));
                        update.setInt(4, attempts);
                        update.setObject(5, candidate.eventId());
                        if (update.executeUpdate() != 1) {
                            throw new IllegalStateException("locked event disappeared during claim");
                        }
                    }
                    result.add(new ClaimedPressureEvent(
                            envelope, consumerId, token, expiresAt, attempts));
                }
                connection.commit();
                return List.copyOf(result);
            } catch (RuntimeException | SQLException failure) {
                rollback(connection, failure);
                throw failure;
            }
        } catch (SQLException failure) {
            throw persistenceFailure("claim", failure);
        }
    }

    @Override
    public boolean acknowledge(String consumerId, UUID eventId, UUID claimToken, Instant now) {
        requireText(consumerId, "consumerId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(claimToken, "claimToken");
        Objects.requireNonNull(now, "now");
        return update("""
                UPDATE wayang_pressure_event
                   SET acknowledged_at = ?, consumer_id = NULL, claim_token = NULL,
                       lease_expires_at = NULL
                 WHERE event_id = ? AND consumer_id = ? AND claim_token = ?
                   AND acknowledged_at IS NULL AND lease_expires_at > ?
                """, statement -> {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setObject(2, eventId);
            statement.setString(3, consumerId);
            statement.setObject(4, claimToken);
            statement.setTimestamp(5, Timestamp.from(now));
        }, "acknowledge") == 1;
    }

    @Override
    public boolean release(String consumerId, UUID eventId, UUID claimToken) {
        requireText(consumerId, "consumerId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(claimToken, "claimToken");
        return update("""
                UPDATE wayang_pressure_event
                   SET consumer_id = NULL, claim_token = NULL, lease_expires_at = NULL
                 WHERE event_id = ? AND consumer_id = ? AND claim_token = ?
                   AND acknowledged_at IS NULL
                """, statement -> {
            statement.setObject(1, eventId);
            statement.setString(2, consumerId);
            statement.setObject(3, claimToken);
        }, "release") == 1;
    }


    /**
     * Delete at most {@code maxEvents} acknowledged rows older than cutoff.
     * Pending and leased events are never eligible. No automatic scheduler is
     * created; the host owns retention policy and invocation cadence.
     */
    @Override
    public int purgeAcknowledgedBefore(Instant cutoff, int maxEvents) {
        Objects.requireNonNull(cutoff, "cutoff");
        if (maxEvents < 1) throw new IllegalArgumentException("maxEvents must be positive");
        String sql = """
                WITH candidates AS (
                    SELECT event_id
                      FROM wayang_pressure_event
                     WHERE acknowledged_at IS NOT NULL AND acknowledged_at < ?
                     ORDER BY acknowledged_at, event_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                )
                DELETE FROM wayang_pressure_event event
                 USING candidates
                 WHERE event.event_id = candidates.event_id
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(cutoff));
            statement.setInt(2, maxEvents);
            return statement.executeUpdate();
        } catch (SQLException failure) {
            throw persistenceFailure("retention purge", failure);
        }
    }

    /**
     * Return a value-free aggregate health snapshot. Failure details are
     * intentionally reduced to stable codes; SQL messages are not exposed.
     */
    public PressureEventStoreHealth inspectHealth() {
        Instant observedAt = Instant.now();
        try (Connection connection = dataSource.getConnection()) {
            int schemaVersion;
            try (PreparedStatement schema = connection.prepareStatement(
                    "SELECT schema_version FROM wayang_pressure_event_schema WHERE singleton = TRUE");
                 ResultSet row = schema.executeQuery()) {
                if (!row.next()) {
                    return PressureEventStoreHealth.unavailable("SCHEMA_VERSION_MISSING", observedAt);
                }
                schemaVersion = row.getInt(1);
            }
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT COUNT(*) FILTER (WHERE acknowledged_at IS NULL) AS pending_events,
                           COUNT(*) FILTER (WHERE acknowledged_at IS NULL
                               AND claim_token IS NOT NULL AND lease_expires_at <= clock_timestamp()) AS expired_leases,
                           MIN(recorded_at) FILTER (WHERE acknowledged_at IS NULL) AS oldest_pending_at,
                           clock_timestamp() AS database_time
                      FROM wayang_pressure_event
                    """);
                 ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    return PressureEventStoreHealth.unavailable("HEALTH_QUERY_EMPTY", observedAt);
                }
                Timestamp oldest = row.getTimestamp("oldest_pending_at");
                Timestamp databaseTime = row.getTimestamp("database_time");
                return new PressureEventStoreHealth(true, schemaVersion,
                        row.getLong("pending_events"), row.getLong("expired_leases"),
                        oldest == null ? null : oldest.toInstant(),
                        databaseTime == null ? observedAt : databaseTime.toInstant(), null);
            }
        } catch (SQLException failure) {
            String code = failure.getSQLState() != null && failure.getSQLState().startsWith("42")
                    ? "SCHEMA_UNAVAILABLE" : "DATABASE_UNAVAILABLE";
            return PressureEventStoreHealth.unavailable(code, observedAt);
        }
    }

    private int update(String sql, SqlBinder binder, String operation) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            return statement.executeUpdate();
        } catch (SQLException failure) {
            throw persistenceFailure(operation, failure);
        }
    }

    private static byte[] requirePayload(byte[] payload) {
        Objects.requireNonNull(payload, "codec payload");
        if (payload.length == 0) throw new IllegalArgumentException("codec payload must not be empty");
        return payload.clone();
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(name + " must be non-blank and trimmed");
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static IllegalStateException persistenceFailure(String operation, SQLException cause) {
        return new IllegalStateException("pressure event store " + operation + " failed", cause);
    }

    private record ClaimCandidate(UUID eventId, String streamId, long sequence,
                                  Instant recordedAt, byte[] payload, int deliveryAttempts) { }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
