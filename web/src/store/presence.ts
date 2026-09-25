import { create } from "zustand";
import { ws } from "../lib/ws";

// Tracks this device's own presence status. "dnd" is a manual override: once
// set, Shell's idle-based auto online/away timer leaves it alone until the
// user picks a different status themselves.
interface PresenceState {
  myStatus: "online" | "away" | "dnd";
  setMyStatus: (status: "online" | "away" | "dnd") => void;
}

export const usePresence = create<PresenceState>((set) => ({
  myStatus: "online",
  setMyStatus: (status) => {
    set({ myStatus: status });
    ws.send("presence:update", { status });
  },
}));
