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
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tech.kayys.wayang.schema.DefinitionType;
import tech.kayys.wayang.schema.WayangSpec;

/**
 * WayangDefinition — The unified domain entity for an agentic AI workflow.
 * <p>
 * This is the <b>single source of truth</b> for everything needed to design,
 * validate, deploy, and execute a Wayang workflow. The entire specification
 * (canvas, workflow, agents, deployment, config) is stored as a single
 * JSONB column ({@code spec}) for efficiency.
 * <p>
 * Replaces the previously fragmented {@code CanvasDefinition} +
 * {@code WorkflowTemplate}
 * with one unified entity.
 *
 * <h3>Benefits of JSONB storage:</h3>
 * <ul>
 * <li>Single query returns the full definition — no JOINs</li>
 * <li>PostgreSQL JSONB supports indexing and partial queries</li>
 * <li>Schema evolution is trivial (just add fields to {@link WayangSpec})</li>
 * <li>The definition is always read/written as a whole unit</li>
 * </ul>
 */
@Entity
@Table(name = "wayang_definitions")
public class WayangDefinition extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "definition_id")
    public UUID definitionId;

    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    @Column(name = "project_id", nullable = false)
    public UUID projectId;

    @Column(name = "name", nullable = false)
    public String name;

    @Column(name = "description")
    public String description;

    @Column(name = "version", nullable = false)
    public String version = "1.0.0";

    @Column(name = "version_number", nullable = false)
    public int versionNumber = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "definition_type")
    public DefinitionType definitionType;

    // ===== THE CORE: Single JSONB column =====
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spec", columnDefinition = "jsonb", nullable = false)
    public WayangSpec spec;

    // ===== Status & Lifecycle =====
    @Column(name = "status")
    public String status = "DRAFT"; // DRAFT, VALIDATED, PUBLISHED, DEPLOYED, ARCHIVED

    @Column(name = "workflow_definition_id")
    public String workflowDefinitionId; // From Orchestrator

    @Column(name = "branch_name")
    public String branchName = "main";

    @Column(name = "parent_definition_id")
    public UUID parentDefinitionId; // For branching/forking

    // ===== Audit =====
    @Column(name = "created_by")
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

    // ===== Collaboration Locking =====
    @Column(name = "is_locked")
    public boolean isLocked = false;

    @Column(name = "locked_by")
    public String lockedBy;

    @Column(name = "locked_at")
    public Instant lockedAt;

    // ===== Tags =====
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", columnDefinition = "jsonb")
    public List<String> tags = new ArrayList<>();
}
