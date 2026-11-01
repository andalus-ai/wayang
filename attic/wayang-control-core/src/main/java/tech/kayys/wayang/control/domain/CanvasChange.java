package tech.kayys.wayang.control.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tech.kayys.wayang.schema.data.ChangeOperation;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Real-time change tracking for collaborative canvas editing.
 */
@Entity
@Table(name = "canvas_changes")
public class CanvasChange extends io.quarkus.hibernate.reactive.panache.PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID changeId;

    @Column(name = "canvas_id")
    public UUID canvasId;

    @Column(name = "user_id")
    public String userId;

    @Column(name = "session_id")
    public String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation")
    public ChangeOperation operation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "change_data", columnDefinition = "jsonb")
    public Map<String, Object> changeData;

    @Column(name = "timestamp")
    public Instant timestamp;

    @Column(name = "sequence_number")
    public long sequenceNumber;

    @Column(name = "is_applied")
    public boolean isApplied = false;

    @Column(name = "conflict_resolved")
    public boolean conflictResolved = false;
}
