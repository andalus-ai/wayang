package tech.kayys.wayang.api.project;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.Principal;
import tech.kayys.wayang.api.WayangApi;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import tech.kayys.wayang.publish.Publisher;
import tech.kayys.wayang.publish.ReleaseOptions;
import tech.kayys.wayang.runtime.json.Json;

/**
 * The second phase of a project's life: from what was designed to something you can run without this server. It exports the
 * <em>published</em> versions of a project as a bundle (no drafts, no triggers, no secret values, only the settings the workflows
 * use) and hands that to the {@link Publisher}, which generates a standalone or service project that depends only on what the
 * workflows use. The generated project is a download; nothing is deployed from here.
 */
public final class ReleaseService {

    /** What a release would contain, without building it. */
    public record Plan(String bundleSha256, List<String> processes, List<String> connectors, boolean usesAgents, List<String> requiredSecrets,
            List<String> requiredConfig, List<String> files, List<String> notes, String target, String artifactId, String version) {
    }

    public record Release(Plan plan, byte[] zip, String filename) {
    }

    private final WorkspaceService workspace;
    private final WayangApi api;
    private final String wayangVersion;

    public ReleaseService(WorkspaceService workspace, WayangApi api, String wayangVersion) {
        this.workspace = workspace;
        this.api = api;
        this.wayangVersion = wayangVersion;
    }

    public Plan plan(Principal p, String projectId, Map<String, Object> options) {
        return build(p, projectId, options).plan();
    }

    public Release release(Principal p, String projectId, Map<String, Object> options) {
        return build(p, projectId, options);
    }

    @SuppressWarnings("unchecked")
    private Release build(Principal p, String projectId, Map<String, Object> options) {
        Project project = api.getProject(p, projectId);
        Map<String, Object> bundle = workspace.export(p, projectId, true, true);
        ReleaseOptions o;
        try {
            o = ReleaseOptions.of(options, defaultArtifact(project.name()), wayangVersion);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        Publisher.Project gen;
        try {
            gen = Publisher.generate(Json.write(bundle), o);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        Map<String, Object> req = bundle.get("requires") instanceof Map<?, ?> r ? (Map<String, Object>) r : Map.of();
        List<String> cfg = req.get("config") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
        Object integrity = bundle.get("integrity") instanceof Map<?, ?> i ? i.get("sha256") : null;
        Plan plan = new Plan(integrity == null ? "" : String.valueOf(integrity), gen.processes(), gen.connectors(), gen.usesAgents(),
                gen.requiredSecrets(), cfg, gen.files().keySet().stream().sorted().toList(), gen.notes(), o.target().name(), o.artifactId(), o.version());
        return new Release(plan, Publisher.zip(gen), o.artifactId() + "-" + o.version() + ".zip");
    }

    static String defaultArtifact(String projectName) {
        String a = projectName == null ? "" : projectName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (a.length() > 60) {
            a = a.substring(0, 60).replaceAll("-+$", "");
        }
        return a.isEmpty() ? "release" : a;
    }
}
