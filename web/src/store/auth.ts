import { create } from "zustand";
import { api } from "../lib/api";
import { clearLocalUserData } from "../lib/session";
import { applyTheme } from "../lib/theme";
import type { User } from "../lib/types";

interface AuthState {
  me: User | null;
  initialized: boolean;
  init: () => Promise<void>;
  login: (username: string, password: string) => Promise<string | null>;
  logout: () => Promise<void>;
  setMe: (me: User | null) => void;
}

export const useAuth = create<AuthState>((set) => ({
  me: null,
  initialized: false,

  init: async () => {
    try {
      const me = await api.me();
      if (me.preferences) {
        applyTheme(me.preferences);
      }
      set({ me, initialized: true });
    } catch {
      set({ me: null, initialized: true });
    }
  },

  login: async (username, password) => {
    try {
      const me = await api.login(username, password);
      if (me.preferences) {
        applyTheme(me.preferences);
      }
      set({ me });
      return null;
    } catch (err: any) {
      return err?.message ?? "Login failed";
    }
  },

  logout: async () => {
    try {
      await api.logout();
    } catch {
      /* session already gone */
    }
    set({ me: null });
    // This is explicitly a shared/kiosk-friendly LAN app - clear anything
    // that shouldn't linger for the next person to log in on this browser.
    clearLocalUserData();
  },

  setMe: (me) => {
    if (me?.preferences) {
      applyTheme(me.preferences);
    }
    set({ me });
  },
}));
