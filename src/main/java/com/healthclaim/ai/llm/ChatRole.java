package com.healthclaim.ai.llm;

/**
 * Role of a chat message in an LLM conversation.
 */
public enum ChatRole {
    SYSTEM,
    USER,
    ASSISTANT;

    public String wireValue() {
        return name().toLowerCase();
    }
}
