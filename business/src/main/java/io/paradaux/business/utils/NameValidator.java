package io.paradaux.business.utils;

import java.util.regex.Pattern;

/**
 * Centralised validation for player-supplied names that end up rendered through
 * MiniMessage (firm names, account names). The whitelist excludes characters
 * that MiniMessage parses as tags ({@code <}, {@code >}), the legacy colour-code
 * char ({@code &}), or anything else that could be abused for
 * impersonation/spoofing in chat.
 */
public final class NameValidator {

    /** Letters, digits, space, underscore, hyphen, period. ({@code &} excluded — legacy colour code.) */
    private static final Pattern FIRM_NAME = Pattern.compile("[A-Za-z0-9 _.\\-]{2,32}");

    /** Same character set, slightly tighter range; account names are shown next to a firm name. */
    private static final Pattern ACCOUNT_NAME = Pattern.compile("[A-Za-z0-9 _.\\-]{2,40}");

    /**
     * A firm's advertised Discord invite (business/structure/0003). Strict
     * {@code https + discord.gg} code charset — the old {@code \S+} let a
     * MiniMessage payload ride into the public /firm info card (ADT
     * discord-url-minimessage-injection). This is the single source of the rule
     * shared by the command layer and {@code FirmServiceImpl}.
     */
    public static final String DISCORD_INVITE_REGEX = "https://discord\\.gg/[A-Za-z0-9]{2,32}";
    private static final Pattern DISCORD_INVITE = Pattern.compile(DISCORD_INVITE_REGEX);

    private NameValidator() {}

    /** Whether {@code s} is a valid Discord invite URL for a firm's advertised link. */
    public static boolean isValidDiscordInvite(String s) {
        return s != null && DISCORD_INVITE.matcher(s).matches();
    }

    public static boolean isValidFirmName(String s) {
        return s != null && FIRM_NAME.matcher(s).matches();
    }

    public static boolean isValidAccountName(String s) {
        return s != null && ACCOUNT_NAME.matcher(s).matches();
    }
}
