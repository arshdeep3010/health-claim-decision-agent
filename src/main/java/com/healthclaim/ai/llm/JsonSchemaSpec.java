package com.healthclaim.ai.llm;

/**
 * A JSON-schema constraint for structured output. {@code schemaJson} is the raw
 * JSON Schema object (as a string); {@code strict} requests exact adherence.
 * Maps to the provider's {@code response_format: {type:"json_schema", ...}}.
 */
public record JsonSchemaSpec(String name, String schemaJson, boolean strict) {
}
