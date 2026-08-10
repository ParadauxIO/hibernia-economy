package io.paradaux.business.commands.resolvers;

/**
 * A firm-name command argument whose tab-completion suggests firms with an employee
 * or proprietor currently online (PAR-13). Any existing firm is still accepted on
 * resolve — suggestions are only hints.
 */
public record OnlineFirmName(String value) {}
