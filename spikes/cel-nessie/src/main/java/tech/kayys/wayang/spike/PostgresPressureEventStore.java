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
public final class PostgresPressureEventStore implements PressureEventStore {
    private static final String INSERT = """
            INSERT INTO wayang_pressure_event
              (event_id, stream_id, sequence_no, recorded_at, payload)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """;

    private static final String FIND_CONFLICT = """
            SELECT event_id, stream_id, sequence_no, recorded_at, payload
              FROM wayang_pressure_event
             WHERE event_id = ? OR (stream_id = ? AND sequence_no = ?)
            """;

    private static final String CLAIM = """
            SELECT event_id, stream_id, sequence_no, recorded_at, payload, delivery_attempts
              FROM wayang_pressure_event
             WHERE acknowledged_at IS NULL
               AND (claim_token IS NULL OR lease_expires_at <= ?)
             ORDER BY stream_id, sequence_no
             FOR UPDATE SKIP LOCKED
             LIMIT ?
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
                    statement.setBytes(5, payload);
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
                            Instant storedAt = rows.getTimestamp("recorded_at").toInstant();
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
                try (PreparedStatement select = connection.prepareStatement(CLAIM)) {
                    select.setTimestamp(1, Timestamp.from(now));
                    select.setInt(2, maxEvents);
                    try (ResultSet rows = select.executeQuery()) {
                        while (rows.next()) {
                            UUID eventId = rows.getObject("event_id", UUID.class);
                            UUID token = UUID.randomUUID();
                            int attempts = Math.addExact(rows.getInt("delivery_attempts"), 1);
                            var envelope = new PressureEventEnvelope(eventId,
                                    rows.getString("stream_id"), rows.getLong("sequence_no"),
                                    rows.getTimestamp("recorded_at").toInstant(),
                                    codec.decode(rows.getBytes("payload")));
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
                                update.setObject(5, eventId);
                                if (update.executeUpdate() != 1) {
                                    throw new IllegalStateException("locked event disappeared during claim");
                                }
                            }
                            result.add(new ClaimedPressureEvent(
                                    envelope, consumerId, token, expiresAt, attempts));
                        }
                    }
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

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
