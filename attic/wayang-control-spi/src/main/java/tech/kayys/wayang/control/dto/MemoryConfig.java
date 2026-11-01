package tech.kayys.wayang.control.dto;

import java.util.Map;

/**
 * Memory Configuration
 */
public class MemoryConfig {
    public boolean shortTermEnabled;
    public boolean longTermEnabled;
    public String vectorStore; // pinecone, weaviate, etc.
    public int maxMemorySize;
    public Map<String, Object> indexConfig;
}
