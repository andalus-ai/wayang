package tech.kayys.wayang.control.spi;

import io.smallrye.mutiny.Uni;
import tech.kayys.wayang.control.dto.ProjectDTO;
import tech.kayys.wayang.control.dto.CreateProjectRequest;

import java.util.List;
import java.util.UUID;

/**
 * SPI interface for project management services.
 */
public interface ProjectManagerSpi {

    /**
     * Create a new project.
     */
    Uni<ProjectDTO> createProject(CreateProjectRequest request);

    /**
     * Get project by ID.
     */
    Uni<ProjectDTO> getProject(UUID projectId, String tenantId);

    /**
     * List projects for a tenant.
     */
    Uni<List<ProjectDTO>> listProjects(String tenantId, tech.kayys.wayang.control.dto.ProjectType type);

    /**
     * Delete/Archive a project.
     */
    Uni<Boolean> deleteProject(UUID projectId, String tenantId);
}