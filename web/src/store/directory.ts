import { create } from "zustand";
import { api } from "../lib/api";
import type { User } from "../lib/types";

interface DirectoryState {
  users: User[];
  loaded: boolean;
  error: string | null;
  fetchUsers: () => Promise<void>;
  applyPresence: (userID: number, status: string) => void;
  presenceSync: (list: { user_id: number; status: string }[]) => void;
  userById: (id: number) => User | undefined;
}

export const useDirectory = create<DirectoryState>((set, get) => ({
  users: [],
  loaded: false,
  error: null,

  fetchUsers: async () => {
    try {
      const users = await api.users();
      set({ users, loaded: true, error: null });
    } catch (err: any) {
      // Keep the old list on screen (don't blank it out from under the
      // user), but surface the failure so the UI can distinguish "still
      // loading" / "genuinely empty" / "couldn't load" instead of always
      // rendering the same blank empty-state.
      set({ error: err?.message ?? "Couldn't load directory" });
    }
  },

  applyPresence: (userID, status) => {
    set({
      users: get().users.map((u) =>
        u.id === userID ? { ...u, status: status as User["status"] } : u,
      ),
    });
  },

  presenceSync: (list) => {
    const byID = new Map(list.map((p) => [p.user_id, p.status]));
    set({
      users: get().users.map((u) => ({
        ...u,
        status: (byID.get(u.id) as User["status"]) ?? "offline",
      })),
    });
  },

  userById: (id) => get().users.find((u) => u.id === id),
}));
