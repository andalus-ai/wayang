package tech.kayys.wayang.control.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tech.kayys.wayang.schema.canvas.CanvasData;
import tech.kayys.wayang.schema.canvas.CanvasMetadata;
import tech.kayys.wayang.schema.canvas.CanvasStatus;
import tech.kayys.wayang.schema.canvas.CanvasValidationResult;
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * ============================================================================
 * ENHANCED CANVAS ENGINE
 * ============================================================================
 * 
 * Advanced visual workflow designer with:
 * - Version control and branching
 * - Collaborative editing with conflict resolution
 * - Canvas validation and linting
 * - Auto-layout and beautification
 * - Template marketplace
 * - Import/Export (BPMN, JSON, YAML)
 * - AI-powered suggestions
 * - Real-time collaboration
 * - Change tracking and audit
 * - Rollback capabilities
 */
/**
 * Canvas definition with versioning and collaboration
 */
@Entity
@Table(name = "canvas_definitions")
public class CanvasDefinition extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID canvasId;

    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    @Column(name = "project_id", nullable = false)
    public UUID projectId;

    @Column(name = "name", nullable = false)
    public String name;

    @Column(name = "description")
    public String description;

    @Column(name = "version", nullable = false)
    public String version;

    @Column(name = "version_number", nullable = false)
    public int versionNumber = 1;

    @Column(name = "parent_canvas_id")
    public UUID parentCanvasId; // For branching

    @Column(name = "branch_name")
    public String branchName = "main";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "canvas_data", columnDefinition = "jsonb", nullable = false)
    public CanvasData canvasData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    public CanvasMetadata metadata;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_result", columnDefinition = "jsonb")
    public CanvasValidationResult validationResult;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    public CanvasStatus status = CanvasStatus.DRAFT;

    @Column(name = "created_by", nullable = false)
    public String createdBy;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_by")
    public String updatedBy;

    @Column(name = "updated_at")
    public Instant updatedAt;

    @Column(name = "published_at")
    public Instant publishedAt;

    @Column(name = "published_by")
    public String publishedBy;

    @Column(name = "is_locked")
    public boolean isLocked = false;

    @Column(name = "locked_by")
    public String lockedBy;

    @Column(name = "locked_at")
    public Instant lockedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", columnDefinition = "jsonb")
    public List<String> tags = new ArrayList<>();
}