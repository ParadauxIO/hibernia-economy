// The in-game command that pays an account, for the account page's "Copy /pay
// command" action (spec §7.1). The syntax differs between servers and plugins,
// so each account type's template comes from the environment, with defaults
// that match this repository's plugins:
//
//   PAY_COMMAND_PERSONAL    /pay {player} <amount>                     Treasury /pay to a player
//   PAY_COMMAND_GOVERNMENT  /pay {account} <amount>                    Treasury /pay to a government account name
//   PAY_COMMAND_BUSINESS    /firm account pay into {firm} {id} <amount>  Business plugin
//
// Placeholders: {player} (owner name), {account} (account name), {firm} (firm
// name, or its id when the name has spaces), {id} (account number). A template
// set to an empty string turns the action off for that type. SYSTEM accounts
// never get one.

export interface PayTarget {
  accountId: number;
  accountType: string;
  accountName: string | null;
  ownerName: string | null;
  firm: { id: number; name: string } | null;
}

const DEFAULTS: Record<string, string> = {
  PERSONAL: '/pay {player} <amount>',
  GOVERNMENT: '/pay {account} <amount>',
  BUSINESS: '/firm account pay into {firm} {id} <amount>',
};

/** A single-token argument, or null when the value can't be typed as one. */
function token(value: string | null | undefined): string | null {
  const v = value?.trim();
  return v && !/\s/.test(v) ? v : null;
}

export function payTemplate(accountType: string, env: Record<string, string | undefined> = process.env): string | null {
  if (!Object.hasOwn(DEFAULTS, accountType)) return null;
  const configured = env[`PAY_COMMAND_${accountType}`];
  const template = configured === undefined ? DEFAULTS[accountType] : configured.trim();
  return template ? template : null;
}

/** The command for an account, or null when there is none (or a placeholder can't be filled). */
export function payCommand(target: PayTarget, env: Record<string, string | undefined> = process.env): string | null {
  const template = payTemplate(target.accountType, env);
  if (!template) return null;
  const values: Record<string, string | null> = {
    player: token(target.ownerName),
    account: token(target.accountName),
    firm: target.firm ? token(target.firm.name) ?? String(target.firm.id) : null,
    id: String(target.accountId),
  };
  const PLACEHOLDER = /\{(player|account|firm|id)\}/g;
  const used = [...template.matchAll(PLACEHOLDER)].map((m) => m[1] as string);
  if (used.some((key) => values[key] === null || values[key] === undefined)) return null;
  return template.replace(PLACEHOLDER, (_, key: string) => values[key] ?? '');
}
