package tech.kayys.wayang.control.dto;

import java.util.Map;

/**
 * Endpoint Configuration
 */
public class EndpointConfig {
    public String endpointType; // rest, kafka, database, sftp, etc.
    public String url;
    public Map<String, String> headers;
    public Map<String, Object> config;
    public AuthenticationConfig authentication;
}
