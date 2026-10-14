/**
 * Theme and language follow Home Assistant (TR-UI-05). The panel runs in a same-origin iframe, so
 * it copies HA's theme variables from the parent document. Outside HA (or when the parent is not
 * reachable) the defaults in styles.css apply, with `prefers-color-scheme` for dark mode.
 */

const THEME_VARIABLES = [
  '--primary-color',
  '--accent-color',
  '--primary-text-color',
  '--secondary-text-color',
  '--disabled-text-color',
  '--text-primary-color',
  '--primary-background-color',
  '--secondary-background-color',
  '--card-background-color',
  '--ha-card-background',
  '--divider-color',
  '--error-color',
  '--warning-color',
  '--success-color',
  '--info-color',
  '--switch-checked-color',
  '--input-fill-color',
  '--ha-card-border-radius',
];

interface HassLike {
  language?: string;
  locale?: { language?: string };
  themes?: { darkMode?: boolean };
}

function parentDocument(): Document | null {
  try {
    if (window.parent === window) return null;
    return window.parent.document;
  } catch {
    return null;
  }
}

function hass(doc: Document): HassLike | null {
  const el = doc.querySelector('home-assistant') as (Element & { hass?: HassLike }) | null;
  return el?.hass ?? null;
}

let language = navigator.language || 'en';

/** Locale for dates and numbers: HA's language, else the browser's. */
export function locale(): string {
  return language;
}

export function syncTheme(): void {
  const doc = parentDocument();
  const root = document.documentElement;
  if (!doc) return;
  try {
    const style = getComputedStyle(doc.documentElement);
    for (const name of THEME_VARIABLES) {
      const value = style.getPropertyValue(name).trim();
      if (value) root.style.setProperty(name, value);
      else root.style.removeProperty(name);
    }
    const h = hass(doc);
    const dark = h?.themes?.darkMode;
    if (dark !== undefined) {
      root.dataset.theme = dark ? 'dark' : 'light';
      root.style.colorScheme = dark ? 'dark' : 'light';
    }
    const lang = h?.locale?.language ?? h?.language;
    if (lang) {
      language = lang;
      root.lang = lang;
    }
  } catch {
    // Parent not readable: keep the defaults.
  }
}

/** Keeps following theme changes in HA (theme picker, dark mode switch). */
export function watchTheme(): void {
  syncTheme();
  const doc = parentDocument();
  if (doc) {
    try {
      new MutationObserver(() => syncTheme()).observe(doc.documentElement, {
        attributes: true,
        attributeFilter: ['style', 'class'],
      });
      new MutationObserver(() => syncTheme()).observe(doc.head, { childList: true });
    } catch {
      // Ignore: the parent is not observable.
    }
  }
  window.addEventListener('focus', syncTheme);
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') syncTheme();
  });
}
