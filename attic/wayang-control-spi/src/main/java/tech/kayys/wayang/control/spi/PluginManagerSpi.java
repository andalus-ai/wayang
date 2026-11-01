package tech.kayys.wayang.control.spi;

import io.smallrye.mutiny.Uni;
import tech.kayys.wayang.plugin.WayangPlugin;

import java.util.List;

/**
 * SPI interface for plugin management services.
 */
public interface PluginManagerSpi {
    
    /**
     * Load a plugin from a JAR file or URL.
     */
    Uni<Void> loadPlugin(String pluginUrl);
    
    /**
     * Unload a plugin by ID.
     */
    Uni<Void> unloadPlugin(String pluginId);
    
    /**
     * Get a plugin by ID.
     */
    Uni<WayangPlugin> getPlugin(String pluginId);
    
    /**
     * List all loaded plugins.
     */
    Uni<List<WayangPlugin>> listPlugins();
    
    /**
     * Validate a plugin before loading.
     */
    Uni<Boolean> validatePlugin(String pluginUrl);
    
    /**
     * Enable a plugin.
     */
    Uni<Void> enablePlugin(String pluginId);
    
    /**
     * Disable a plugin.
     */
    Uni<Void> disablePlugin(String pluginId);
}