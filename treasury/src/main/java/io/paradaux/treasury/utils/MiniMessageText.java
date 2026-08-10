package io.paradaux.treasury.utils;

/** MiniMessage helpers for command output built from user-supplied strings. */
public final class MiniMessageText {

    private MiniMessageText() {
    }

    /**
     * Escapes MiniMessage tag syntax in a user-supplied string so it renders
     * literally, preventing tag injection when the value is spliced into an
     * operator-authored MiniMessage template (memos, fine reasons, …).
     */
    public static String sanitize(String input) {
        return input.replace("<", "\\<");
    }
}
