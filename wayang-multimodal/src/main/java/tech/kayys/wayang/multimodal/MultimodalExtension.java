package tech.kayys.wayang.multimodal;

import java.util.List;
import java.util.Map;
import java.util.Set;

import tech.kayys.wayang.connectors.MediaStore;
import tech.kayys.wayang.connectors.UrlGuard;
import tech.kayys.wayang.runtime.ComponentDescriptor;
import tech.kayys.wayang.runtime.Profile;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.WayangExtension;

/** Adds the {@code image-generate}, {@code comfyui}, {@code transcribe} and {@code speak} connectors; loaded only when the profile enables the MEDIA feature. */
public final class MultimodalExtension implements WayangExtension {

    @Override
    public String id() {
        return "multimodal";
    }

    @Override
    public Set<Profile.Feature> requires() {
        return Set.of(Profile.Feature.MEDIA);
    }

    @Override
    public Map<String, ServiceHandler.Connector> connectors() {
        UrlGuard guard = new UrlGuard("true".equalsIgnoreCase(System.getenv("WAYANG_CONNECTORS_ALLOW_PRIVATE")), hosts());
        MediaStore store = MediaStore.fromEnvironment();
        return Map.of("image-generate", new ImageGenerate(guard, store), "comfyui", new ComfyUi(guard, store), "transcribe", new Transcribe(guard, store),
                "speak", new Speak(guard, store));
    }

    @Override
    public List<ComponentDescriptor> components() {
        return List.of(
                component("wayang.multimodal.image-generate", "Generate an image from a prompt", "media.image.generate"),
                component("wayang.multimodal.comfyui", "Submit a workflow to a ComfyUI endpoint", "media.workflow.execute"),
                component("wayang.multimodal.transcribe", "Transcribe audio to text", "media.audio.transcribe"),
                component("wayang.multimodal.speak", "Synthesize speech from text", "media.audio.synthesize"));
    }

    private static ComponentDescriptor component(String id, String description, String capability) {
        return new ComponentDescriptor(id, "0.0.1", ComponentDescriptor.Kind.CONNECTOR, "wayang-service",
                description, Set.of(Profile.Feature.MEDIA), List.of(),
                ComponentDescriptor.IsolationLevel.IN_PROCESS, Set.of(capability));
    }

    private static Set<String> hosts() {
        String h = System.getenv("WAYANG_CONNECTORS_ALLOWED_HOSTS");
        if (h == null || h.isBlank()) {
            return Set.of();
        }
        return Set.copyOf(java.util.Arrays.stream(h.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList());
    }
}
