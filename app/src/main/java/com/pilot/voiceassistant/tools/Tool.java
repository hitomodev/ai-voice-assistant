package com.pilot.voiceassistant.tools;

/**
 * Something the assistant can do when the user asks for it. The LLM engine shows the name and
 * description to the model and runs the action of the tool the model picks.
 */
public final class Tool {

    public interface Action {
        /** Does the work and returns the short sentence the assistant says afterwards. */
        String run();
    }

    private final String name;
    private final String description;
    private final Action action;

    /**
     * @param name letters, digits and underscores only
     * @param description tells the model when to pick the tool; it decides how well commands
     *         are recognized
     */
    public Tool(String name, String description, Action action) {
        this.name = name;
        this.description = description;
        this.action = action;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String run() {
        return action.run();
    }
}
