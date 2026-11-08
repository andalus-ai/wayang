package tech.kayys.wayang.adapter.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tech.kayys.wayang.runtime.*;

class WayangCoreMetadataMapperTest {
 @Test void projectsManifestAndRetainsHostFields() {
  var manifest = new PluginManifest("acme.media","1.2.3","1.0","Acme",">=1.0 <2.0",
   List.of(new PluginManifest.ProvidedComponent("acme.media.fetch","1.2.3")),
   List.of(new PluginManifest.Dependency("acme.common","^2.0.0",true)),Set.of("media:read"),"Apache-2.0");
  var descriptor = new ComponentDescriptor("acme.media.fetch","1.2.3",ComponentDescriptor.Kind.NODE,
   "http","Fetch",Set.of(),List.of(),ComponentDescriptor.IsolationLevel.IN_PROCESS,Set.of("media.fetch"),
   "Fetch","media","download",Map.of("type","object","properties",Map.of()),Map.of("widget","url"));
  var execution = new ComponentExecutionCapabilities("acme.media.fetch","1.2.3",
   Set.of(ComponentExecutionMode.EMBEDDED),ComponentStateModel.STATELESS,true,true,true,null,Set.of(),Map.of());
  var result = new WayangCoreMetadataMapper().map(manifest,List.of(descriptor),List.of(execution));
  assertEquals("acme.media",result.descriptor().id().toString());
  assertEquals("http",result.descriptor().components().get(0).metadata().get("wayang:executor-type"));
  assertEquals(1,result.descriptor().dependencies().size());
  assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("schema")));
 }
 @Test void refusesToGuessExecutionModeFromIsolation() {
  var manifest = new PluginManifest("acme.media","1.2.3","1.0","Acme",">=1.0 <2.0",
   List.of(new PluginManifest.ProvidedComponent("acme.media.fetch","1.2.3")),List.of(),Set.of(),null);
  var descriptor = new ComponentDescriptor("acme.media.fetch","1.2.3",ComponentDescriptor.Kind.NODE,
   "http","",Set.of(),List.of(),ComponentDescriptor.IsolationLevel.REMOTE,Set.of());
  assertThrows(IllegalArgumentException.class,
   () -> new WayangCoreMetadataMapper().map(manifest,List.of(descriptor),List.of()));
 }
}