import type {
  AdminStats,
  AuditEntry,
  Call,
  DeviceKey,
  Group,
  IceServer,
  Message,
  PrivateRoom,
  SettingView,
  User,
} from "./types";
import type { UserPreferences } from "./theme";

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

// csrfToken reads the double-submit CSRF cookie the server sets alongside
// the (HttpOnly) session cookie — see auth.RequireCSRF on the server.
function csrfToken(): string | undefined {
  const m = document.cookie.match(/(?:^|;\s*)vc_csrf=([^;]+)/);
  return m ? decodeURIComponent(m[1]) : undefined;
}

// A 401 from most endpoints means "the session died mid-use" (expired,
// revoked, the account was disabled) and should redirect to Login instead
// of surfacing as a generic per-action error toast. The handful of paths
// below are expected to 401 while legitimately logged out (the initial
// "am I logged in" check, the login form itself, password reset, OIDC) and
// must not trigger that redirect.
const PATHS_EXPECTED_UNAUTHENTICATED = ["/api/me", "/api/login", "/api/oidc/", "/api/password-reset", "/api/reset-password"];

// "/api/me" must match exactly: as a prefix it also matched "/api/messages/…",
// so an expired session there never redirected to Login.
function expectedUnauthenticated(path: string): boolean {
  return PATHS_EXPECTED_UNAUTHENTICATED.some((p) => (p === "/api/me" ? path === p || path.startsWith("/api/me?") : path.startsWith(p)));
}

let onUnauthorized: (() => void) | null = null;
// Registered once by the app root (see App.tsx) so any API call anywhere —
// not just the ones a component happens to await and check — can trigger a
// clean redirect to Login the moment the server says the session is gone.
export function setUnauthorizedHandler(fn: (() => void) | null) {
  onUnauthorized = fn;
}

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  const method = (init?.method ?? "GET").toUpperCase();
  const headers: Record<string, string> = {};
  if (init?.body && !(init.body instanceof FormData)) headers["Content-Type"] = "application/json";
  if (method !== "GET" && method !== "HEAD") {
    const token = csrfToken();
    if (token) headers["X-CSRF-Token"] = token;
  }
  const res = await fetch(path, {
    credentials: "include",
    ...init,
    headers,
  });
  if (!res.ok) {
    let msg = res.statusText;
    try {
      const body = await res.json();
      if (body.error) msg = body.error;
    } catch {
      /* non-json error */
    }
    if (res.status === 401 && !expectedUnauthenticated(path)) {
      onUnauthorized?.();
    }
    throw new ApiError(res.status, msg);
  }
  return res.json();
}

export const api = {
  login: (username: string, password: string) =>
    req<User>("/api/login", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    }),
  logout: () => req<{ ok: boolean }>("/api/logout", { method: "POST" }),
  me: () => req<User>("/api/me"),
  userPreferences: () => req<UserPreferences>("/api/users/me/preferences"),
  updateUserPreferences: (body: Partial<UserPreferences>) =>
    req<UserPreferences>("/api/users/me/preferences", {
      method: "PUT",
      body: JSON.stringify(body),
    }),

  users: () => req<User[] | null>("/api/users").then((u) => u ?? []),
  createUser: (body: {
    username: string;
    display_name: string;
    password: string;
    role: string;
    email?: string;
  }) => req<User>("/api/users", { method: "POST", body: JSON.stringify(body) }),
  updateUser: (
    id: number,
    body: {
      display_name?: string;
      role?: string;
      disabled?: boolean;
      password?: string;
      email?: string;
    },
  ) => req<User>(`/api/users/${id}`, { method: "PATCH", body: JSON.stringify(body) }),
  deleteUser: (id: number) =>
    req<{ ok: boolean }>(`/api/users/${id}`, { method: "DELETE" }),
  updateSelf: (body: {
    display_name?: string;
    avatar_file_id?: number | null;
    email?: string;
    current_password?: string;
    new_password?: string;
  }) =>
    req<User | { status: string }>("/api/users/me", {
      method: "PATCH",
      body: JSON.stringify(body),
    }),

  oidcConfig: () => req<{ enabled: boolean; button_label?: string }>("/api/oidc/config"),
  registerDeviceKey: (deviceId: string, publicKeyJwk: string) =>
    req<{ ok: boolean }>("/api/devices/keys", { method: "PUT", body: JSON.stringify({ device_id: deviceId, public_key_jwk: publicKeyJwk }) }),
  deviceKeys: (userIds: number[]) =>
    req<DeviceKey[] | null>(`/api/devices/keys?user_ids=${userIds.join(",")}`).then((d) => d ?? []),
  oidcUnlink: () => req<{ ok: boolean }>("/api/oidc/link", { method: "DELETE" }),
  adminOidcUnlink: (userId: number) => req<{ ok: boolean }>(`/api/users/${userId}/oidc-link`, { method: "DELETE" }),

  passwordResetEnabled: () => req<{ enabled: boolean }>("/api/password-reset/enabled"),
  requestPasswordReset: (email: string) =>
    req<{ message: string }>("/api/password-reset/request", {
      method: "POST",
      body: JSON.stringify({ email }),
    }),
  confirmPasswordReset: (token: string, newPassword: string) =>
    req<{ ok: boolean }>("/api/password-reset/confirm", {
      method: "POST",
      body: JSON.stringify({ token, new_password: newPassword }),
    }),

  directMessages: (peerID: number, before?: number) =>
    req<Message[] | null>(
      `/api/messages/${peerID}${before ? `?before=${before}&limit=50` : ""}`,
    ).then((m) => m ?? []),
  recentConversations: () =>
    req<{ dms: Message[] | null; groups: Message[] | null }>("/api/conversations/recent").then((r) => ({ dms: r.dms ?? [], groups: r.groups ?? [] })),
  groups: () => req<Group[] | null>("/api/groups").then((g) => g ?? []),
  createGroup: (name: string, memberIDs: number[]) =>
    req<Group>("/api/groups", {
      method: "POST",
      body: JSON.stringify({ name, member_ids: memberIDs }),
    }),
  deleteGroup: (id: number) =>
    req<{ ok: boolean }>(`/api/groups/${id}`, { method: "DELETE" }),
  renameGroup: (id: number, name: string) =>
    req<Group>(`/api/groups/${id}`, { method: "PATCH", body: JSON.stringify({ name }) }),
  // avatar_file_id: 0 clears the group icon (the server treats "unset"
  // — omitted from the body — as "leave unchanged", so clearing needs an
  // explicit sentinel rather than JSON null, which looks the same as omitted).
  updateGroup: (id: number, body: { name?: string; topic?: string; avatar_file_id?: number }) =>
    req<Group>(`/api/groups/${id}`, { method: "PATCH", body: JSON.stringify(body) }),
  addGroupMembers: (id: number, memberIDs: number[]) =>
    req<Group>(`/api/groups/${id}/members`, {
      method: "POST",
      body: JSON.stringify({ member_ids: memberIDs }),
    }),
  removeGroupMember: (id: number, userID: number) =>
    req<{ ok: boolean }>(`/api/groups/${id}/members/${userID}`, { method: "DELETE" }),
  setGroupMemberRole: (id: number, userID: number, role: "admin" | "member") =>
    req<Group>(`/api/groups/${id}/members/${userID}`, { method: "PATCH", body: JSON.stringify({ role }) }),
  groupMessages: (groupID: number, before?: number) =>
    req<Message[] | null>(
      `/api/groups/${groupID}/messages${before ? `?before=${before}&limit=50` : ""}`,
    ).then((m) => m ?? []),
  groupReadState: (groupID: number) => req<Record<string, number>>(`/api/groups/${groupID}/read-state`),

  pinnedMessages: (target: { groupID: number } | { peerID: number }) =>
    req<Message[] | null>(
      `/api/pinned?${"groupID" in target ? `group_id=${target.groupID}` : `peer_id=${target.peerID}`}`,
    ).then((m) => m ?? []),
  searchMessages: (filter: { q?: string; senderId?: number; since?: string; until?: string; hasFile?: boolean; limit?: number }) => {
    const params = new URLSearchParams();
    if (filter.q) params.set("q", filter.q);
    if (filter.senderId) params.set("sender_id", String(filter.senderId));
    if (filter.since) params.set("since", filter.since);
    if (filter.until) params.set("until", filter.until);
    if (filter.hasFile) params.set("has_file", "true");
    if (filter.limit) params.set("limit", String(filter.limit));
    return req<Message[] | null>(`/api/search?${params.toString()}`).then((m) => m ?? []);
  },
  savedMessages: () => req<Message[] | null>("/api/saved").then((m) => m ?? []),
  saveMessage: (id: number) => req<{ ok: boolean }>(`/api/messages/${id}/save`, { method: "PUT" }),
  unsaveMessage: (id: number) => req<{ ok: boolean }>(`/api/messages/${id}/save`, { method: "DELETE" }),
  exportUrl: (target: { groupID: number } | { peerID: number }) =>
    `/api/export?${"groupID" in target ? `group_id=${target.groupID}` : `peer_id=${target.peerID}`}`,

  calls: (limit = 50) =>
    req<Call[] | null>(`/api/calls?limit=${limit}`).then((c) => c ?? []),

  createRoom: (body: { name: string; passcode?: string; require_approval: boolean; invited_user_ids: number[] }) =>
    req<PrivateRoom>("/api/rooms", { method: "POST", body: JSON.stringify(body) }),
  myRooms: () => req<PrivateRoom[] | null>("/api/rooms").then((r) => r ?? []),
  getRoom: (id: string) => req<PrivateRoom>(`/api/rooms/${encodeURIComponent(id)}`),
  deleteRoom: (id: string) => req<{ ok: boolean }>(`/api/rooms/${encodeURIComponent(id)}`, { method: "DELETE" }),

  uploadFile: (file: File) => {
    const form = new FormData();
    form.append("file", file);
    return req<{ id: number; name: string; mime: string; size: number }>(
      "/api/files",
      { method: "POST", body: form },
    );
  },

  iceServers: () => req<{ iceServers: IceServer[] }>("/api/ice"),
  adminStats: () => req<AdminStats>("/api/admin/stats"),
  listSettings: () => req<SettingView[]>("/api/admin/settings"),
  updateSetting: (key: string, value: string) =>
    req<{ ok: boolean }>(`/api/admin/settings/${encodeURIComponent(key)}`, { method: "PUT", body: JSON.stringify({ value }) }),
  resetSetting: (key: string) =>
    req<{ ok: boolean }>(`/api/admin/settings/${encodeURIComponent(key)}`, { method: "DELETE" }),
  auditLog: (before?: number) =>
    req<AuditEntry[] | null>(`/api/admin/audit${before ? `?before=${before}` : ""}`).then((a) => a ?? []),
};
