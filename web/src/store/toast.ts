import { create } from "zustand";

export interface Toast {
  id: number;
  variant: "success" | "error" | "info" | "message";
  message: string;
  /** Bold first line, used by message notifications. */
  title?: string;
  /** Runs when the toast is clicked, e.g. to open the conversation. */
  onClick?: () => void;
  duration: number;
}

interface ToastState {
  toasts: Toast[];
  push: (t: Omit<Toast, "id" | "duration"> & { duration?: number }) => void;
  dismiss: (id: number) => void;
}

let nextId = 1;
const MAX_VISIBLE = 4;

export const useToast = create<ToastState>((set) => ({
  toasts: [],
  push: (t) => {
    const id = nextId++;
    const duration = t.duration ?? (t.variant === "error" ? 6000 : 4000);
    set((state) => ({ toasts: [...state.toasts, { ...t, id, duration }].slice(-MAX_VISIBLE) }));
    setTimeout(() => set((state) => ({ toasts: state.toasts.filter((x) => x.id !== id) })), duration);
  },
  dismiss: (id) => set((state) => ({ toasts: state.toasts.filter((t) => t.id !== id) })),
}));

// Module-level helpers so non-component code (stores, api error handlers)
// can raise a toast without hooking into the store.
export const toast = {
  success: (message: string) => useToast.getState().push({ variant: "success", message }),
  error: (message: string) => useToast.getState().push({ variant: "error", message }),
  info: (message: string) => useToast.getState().push({ variant: "info", message }),
  /** In-app notification for a new message or call while the app is open. */
  message: (title: string, message: string, onClick?: () => void) =>
    useToast.getState().push({ variant: "message", title, message, onClick, duration: 5000 }),
};
