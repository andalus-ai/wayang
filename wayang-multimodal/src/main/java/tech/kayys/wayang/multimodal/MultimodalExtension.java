package tech.kayys.wayang.multimodal;

import java.util.Map;
import java.util.Set;

import tech.kayys.wayang.connectors.MediaStore;
import tech.kayys.wayang.connectors.UrlGuard;
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

    private static Set<String> hosts() {
        String h = System.getenv("WAYANG_CONNECTORS_ALLOWED_HOSTS");
        if (h == null || h.isBlank()) {
            return Set.of();
        }
        return Set.copyOf(java.util.Arrays.stream(h.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList());
    }
}
