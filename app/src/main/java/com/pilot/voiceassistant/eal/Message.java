package com.pilot.voiceassistant.eal;

/** One entry of the conversation history passed to {@link LLMEAL}. */
public final class Message {

    public enum Role {
        USER,
        ASSISTANT
    }

    private final Role role;
    private final String text;

    public Message(Role role, String text) {
        this.role = role;
        this.text = text;
    }

    public static Message user(String text) {
        return new Message(Role.USER, text);
    }

    public static Message assistant(String text) {
        return new Message(Role.ASSISTANT, text);
    }

    public Role getRole() {
        return role;
    }

    public String getText() {
        return text;
    }

    @Override
    public String toString() {
        return role + ": " + text;
    }
}
