import { create } from "zustand";

export interface Toast {
  id: number;
  variant: "success" | "error" | "info";
  message: string;
}

interface ToastState {
  toasts: Toast[];
  push: (variant: Toast["variant"], message: string) => void;
  dismiss: (id: number) => void;
}

let nextId = 1;

export const useToast = create<ToastState>((set) => ({
  toasts: [],
  push: (variant, message) => {
    const id = nextId++;
    set((state) => ({ toasts: [...state.toasts, { id, variant, message }] }));
    setTimeout(() => set((state) => ({ toasts: state.toasts.filter((t) => t.id !== id) })), 4000);
  },
  dismiss: (id) => set((state) => ({ toasts: state.toasts.filter((t) => t.id !== id) })),
}));

// Convenience module-level helper so non-component code (lib/api error
// handlers, store actions) can raise a toast without hooking into the store.
export const toast = {
  success: (message: string) => useToast.getState().push("success", message),
  error: (message: string) => useToast.getState().push("error", message),
  info: (message: string) => useToast.getState().push("info", message),
};
