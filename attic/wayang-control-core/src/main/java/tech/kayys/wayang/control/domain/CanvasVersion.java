package tech.kayys.wayang.control.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tech.kayys.wayang.schema.canvas.CanvasData;
import tech.kayys.wayang.schema.data.ChangeRecord;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Canvas version history for the control plane.
 */
@Entity
@Table(name = "canvas_versions")
public class CanvasVersion extends io.quarkus.hibernate.reactive.panache.PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID versionId;

    @Column(name = "canvas_id", nullable = false)
    public UUID canvasId;

    @Column(name = "version_number", nullable = false)
    public int versionNumber;

    @Column(name = "version_tag")
    public String versionTag; // v1.0.0, v1.1.0, etc.

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "canvas_data", columnDefinition = "jsonb")
    public CanvasData canvasData;

    @Column(name = "change_summary")
    public String changeSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "change_details", columnDefinition = "jsonb")
    public List<ChangeRecord> changeDetails;

    @Column(name = "created_by")
    public String createdBy;

    @Column(name = "created_at")
    public Instant createdAt;

    @Column(name = "parent_version_id")
    public UUID parentVersionId;
}
