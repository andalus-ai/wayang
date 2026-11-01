/*
 * PolyForm Noncommercial License 1.0.0
 *
 * Copyright (c) 2026 Kayys.tech
 *
 * This software is licensed for non-commercial use only.
 * You may use, modify, and distribute this software for personal,
 * educational, or research purposes.
 *
 * Commercial use, including SaaS or revenue-generating services,
 * requires a separate commercial license from Kayys.tech.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.
 *
 * @author Bhangun
 */

package tech.kayys.wayang.schema.validator;

/**
 * Schema reference - can be inline JSON or file path
 */
public class SchemaReference {
    public SchemaType type; // INLINE, FILE, URL
    public String content; // Schema content or path

    public enum SchemaType {
        INLINE, // Inline JSON
        FILE, // File path in JAR
        URL // External URL
    }

    public String getSchema() {
        return content;
    }
}
