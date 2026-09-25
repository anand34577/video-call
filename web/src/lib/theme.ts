import { api } from "./api";

export type ThemeId = "light" | "dark" | "midnight" | "sunset" | "forest" | "cyberpunk";
export type AccentId = "blue" | "purple" | "emerald" | "rose" | "amber" | "cyan";
export type RadiusId = "rounded" | "compact" | "pill";

export interface UserPreferences {
  theme: ThemeId;
  accent_color: AccentId;
  radius: RadiusId;
}

export const DEFAULT_PREFERENCES: UserPreferences = {
  theme: "dark",
  accent_color: "blue",
  radius: "rounded",
};

export interface ThemeMeta {
  id: ThemeId;
  name: string;
  description: string;
  dark: boolean;
  bgHex: string;
  cardHex: string;
  accentHex: string;
}

export const THEMES: ThemeMeta[] = [
  {
    id: "dark",
    name: "Dark (Default)",
    description: "Deep charcoal with balanced contrast",
    dark: true,
    bgHex: "#0b0e14",
    cardHex: "#151b28",
    accentHex: "#3b82f6",
  },
  {
    id: "light",
    name: "Light",
    description: "Crisp modern light workspace",
    dark: false,
    bgHex: "#f4f4f5",
    cardHex: "#ffffff",
    accentHex: "#2563eb",
  },
  {
    id: "midnight",
    name: "Midnight OLED",
    description: "Pure pitch black for OLED displays",
    dark: true,
    bgHex: "#000000",
    cardHex: "#0d0d0d",
    accentHex: "#60a5fa",
  },
  {
    id: "sunset",
    name: "Sunset",
    description: "Warm twilight plum with rose tones",
    dark: true,
    bgHex: "#120b18",
    cardHex: "#1d1226",
    accentHex: "#f43f5e",
  },
  {
    id: "forest",
    name: "Nordic Forest",
    description: "Deep spruce pine with emerald glow",
    dark: true,
    bgHex: "#06110c",
    cardHex: "#0c2017",
    accentHex: "#10b981",
  },
  {
    id: "cyberpunk",
    name: "Cyberpunk",
    description: "High-tech electric navy with cyan",
    dark: true,
    bgHex: "#060913",
    cardHex: "#0e162c",
    accentHex: "#06b6d4",
  },
];

export interface AccentMeta {
  id: AccentId;
  name: string;
  hex: string;
}

export const ACCENTS: AccentMeta[] = [
  { id: "blue", name: "Electric Blue", hex: "#2563eb" },
  { id: "purple", name: "Royal Violet", hex: "#8b5cf6" },
  { id: "emerald", name: "Emerald Mint", hex: "#10b981" },
  { id: "rose", name: "Rose Ruby", hex: "#f43f5e" },
  { id: "amber", name: "Sunset Amber", hex: "#f59e0b" },
  { id: "cyan", name: "Neon Cyan", hex: "#06b6d4" },
];

export const RADII: { id: RadiusId; name: string; desc: string }[] = [
  { id: "rounded", name: "Smooth", desc: "Balanced modern corners" },
  { id: "compact", name: "Sharp", desc: "Crisp, technical corners" },
  { id: "pill", name: "Pill", desc: "Curved friendly rounded curves" },
];

let currentPreferences: UserPreferences = { ...DEFAULT_PREFERENCES };

export function getCurrentPreferences(): UserPreferences {
  return { ...currentPreferences };
}

/**
 * Applies theme variables and data attributes to the DOM,
 * updates local storage cache, and dispatches a change event.
 */
export function applyTheme(prefs: Partial<UserPreferences>) {
  const merged: UserPreferences = {
    theme: prefs.theme || currentPreferences.theme || "dark",
    accent_color: prefs.accent_color || currentPreferences.accent_color || "blue",
    radius: prefs.radius || currentPreferences.radius || "rounded",
  };
  currentPreferences = merged;

  const html = document.documentElement;
  html.dataset.theme = merged.theme;
  html.dataset.accent = merged.accent_color;
  html.dataset.radius = merged.radius;

  if (merged.theme === "light") {
    html.classList.remove("dark");
  } else {
    html.classList.add("dark");
  }

  // Update mobile browser chrome color
  const themeObj = THEMES.find((t) => t.id === merged.theme);
  const themeColorMeta = document.querySelector('meta[name="theme-color"]');
  if (themeColorMeta && themeObj) {
    themeColorMeta.setAttribute("content", themeObj.bgHex);
  }

  // Write fast boot cache
  try {
    localStorage.setItem("vc.theme.prefs", JSON.stringify(merged));
    localStorage.setItem("vc.theme", merged.theme === "light" ? "light" : "dark");
  } catch {}

  window.dispatchEvent(new CustomEvent("vc:theme-changed", { detail: merged }));
}

/**
 * Initializes theme on page load from initial cache, then fetches
 * canonical preferences from the database if signed in.
 */
export function initTheme() {
  try {
    const cached = localStorage.getItem("vc.theme.prefs");
    if (cached) {
      const parsed = JSON.parse(cached);
      applyTheme(parsed);
      return;
    }
    const legacy = localStorage.getItem("vc.theme");
    if (legacy === "light") {
      applyTheme({ theme: "light" });
      return;
    }
  } catch {}
  applyTheme(DEFAULT_PREFERENCES);
}

/**
 * Saves preferences directly into the database for the current authenticated user.
 */
export async function savePreferencesToDb(prefs: Partial<UserPreferences>): Promise<UserPreferences> {
  const merged: UserPreferences = {
    theme: prefs.theme || currentPreferences.theme || "dark",
    accent_color: prefs.accent_color || currentPreferences.accent_color || "blue",
    radius: prefs.radius || currentPreferences.radius || "rounded",
  };
  // Optimistically apply immediately for zero lag UI
  applyTheme(merged);

  try {
    const updated = await api.updateUserPreferences(merged);
    applyTheme(updated);
    return updated;
  } catch (err) {
    // If offline or network error, it remains applied locally
    console.warn("Could not persist theme preferences to database:", err);
    return merged;
  }
}
