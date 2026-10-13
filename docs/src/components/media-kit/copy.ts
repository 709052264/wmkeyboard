/**
 * Click-to-copy for the media kit: any button with `data-wm-copy` copies that
 * attribute's value (or, given `data-wm-copy-from`, the text of the element
 * with that id) and says so for a moment. One delegated listener for the page.
 */
const KEY = '__wmCopyBound';
const w = window as unknown as Record<string, boolean>;
if (!w[KEY]) {
	w[KEY] = true;
	document.addEventListener('click', async (event) => {
		const button = (event.target as Element | null)?.closest<HTMLElement>('[data-wm-copy]');
		if (!button) return;
		const from = button.dataset.wmCopyFrom;
		const text = from ? (document.getElementById(from)?.innerText ?? '') : (button.dataset.wmCopy ?? '');
		try {
			await navigator.clipboard.writeText(text.trim());
		} catch {
			return;
		}
		const label = button.querySelector<HTMLElement>('[data-wm-copy-label]');
		if (!label) return;
		const before = label.dataset.before ?? label.textContent ?? '';
		label.dataset.before = before;
		label.textContent = 'Copied';
		button.classList.add('is-copied');
		window.setTimeout(() => {
			label.textContent = before;
			button.classList.remove('is-copied');
		}, 1400);
	});
}
