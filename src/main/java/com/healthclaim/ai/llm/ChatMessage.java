package com.healthclaim.ai.llm;

/**
 * A single chat message.
 */
public record ChatMessage(ChatRole role, String content) {

    public static ChatMessage system(String content) {
        return new ChatMessage(ChatRole.SYSTEM, content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(ChatRole.USER, content);
    }
}
