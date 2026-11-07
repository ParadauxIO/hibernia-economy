/** Collection-time ledger tag, shared with BalanceTaxBrackets.Bracket.ledgerTag(). */
export function bracketDetails(key: string) {
  const match = /^(-?\d+(?:[.]\d+)?):([*]|-?\d+(?:[.]\d+)?):(\d+(?:[.]\d+)?)$/.exec(key);
  if (!match) return null;
  const floor = match[1]!, ceiling = match[2]!, rate = match[3]!;
  // Shift the decimal point without a floating-point round trip.
  const [whole = '0', fraction = ''] = rate.split('.');
  const digits = fraction.padEnd(2, '0');
  const integer = BigInt(whole + digits.slice(0, 2)).toString();
  const decimals = digits.slice(2).replace(/0+$/, '');
  const dollars = (value: string) => {
    const [integer = '0', fraction] = value.split('.');
    return '$' + integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + (fraction ? '.' + fraction : '');
  };
  return { floor: dollars(floor), ceiling: ceiling === '*' ? null : dollars(ceiling), percent: `${integer}${decimals ? '.' + decimals : ''}%` };
}
