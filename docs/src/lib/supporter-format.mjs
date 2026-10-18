/**
 * How one supporter reads on the Supporters page. Shared by the page's build
 * step and its "Check for new supporters" script, so a name fetched later
 * looks exactly like one baked in.
 *
 * The app shows names only; amounts and messages are for this page.
 */

/** "12 Oct 2026". */
export const dateOf = (iso) =>
	new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });

/** "৳300", "$4.99": the currency's symbol, and decimals only when there are any. */
export function amountOf(amount, currency) {
	if (typeof amount !== 'number' || !currency) return '';
	try {
		return new Intl.NumberFormat('en', {
			style: 'currency',
			currency,
			currencyDisplay: 'narrowSymbol',
			minimumFractionDigits: Number.isInteger(amount) ? 0 : 2,
			maximumFractionDigits: 2,
		}).format(amount);
	} catch {
		return `${currency} ${amount}`;
	}
}

/** "Tip · ৳300 · 12 Oct 2026", leaving out whatever is not known. */
export function metaOf(s) {
	const parts = [s.kind === 'member' ? 'Member' : 'Tip'];
	const amount = amountOf(s.amount, s.currency);
	if (amount) parts.push(s.kind === 'member' ? `${amount} a month` : amount);
	parts.push(dateOf(s.at));
	return parts.join(' · ');
}
