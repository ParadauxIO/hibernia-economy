package io.paradaux.chestshop.utils;

/**
 * Provides methods to handle usernames/UUIDs
 *
 * @author Andrzej Pomirski (Acrobot)
 */
public final class NameUtil {

    private NameUtil() {
    }


    /**
     * Strip the username to 15 characters (number of characters a sign can hold)
     *
     * @param username Username to strip
     * @return Stripped username
     */
    public static String stripUsername(String username) {
        return stripUsername(username, 15);
    }

    /**
     * Strips the username to a specified number of characters
     *
     * @param username Username to strip
     * @param length   Length of the expected username
     * @return Stripped username
     */
    public static String stripUsername(String username, int length) {
        if (username.length() > length) {
            return username.substring(0, length);
        }

        return username;
    }
}
