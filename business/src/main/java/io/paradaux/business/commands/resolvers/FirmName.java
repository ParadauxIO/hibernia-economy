package io.paradaux.business.commands.resolvers;

/**
 * A firm-name command argument whose tab-completion suggests the firms the sender
 * owns or is employed by (PAR-13). Any existing firm is still accepted on resolve —
 * suggestions are only hints.
 */
public record FirmName(String value) {}
