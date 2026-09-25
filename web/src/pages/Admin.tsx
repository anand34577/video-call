import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { HardDrive, PhoneCall, RefreshCw, UserPlus, Users, Activity, Search, Eye, EyeOff, Trash2, AlertTriangle, Loader2, ScrollText, Settings as SettingsIcon, Lock, RotateCcw, PhoneCall as CallIcon, FolderCog, Mail, KeyRound, ShieldCheck, Network, Server } from "lucide-react";
import { api } from "../lib/api";
import { useDirectory } from "../store/directory";
import { useAuth } from "../store/auth";
import { Alert, Avatar, Modal, PresenceDot, Switch, Tabs, SkeletonList, EmptyState, btnDestructive, btnGhost, btnPrimary, btnSecondary, inputCls } from "../components/ui";
import { fmtBytes, presenceLabel } from "../lib/util";
import type { AdminStats, AuditEntry, SettingView, User } from "../lib/types";

type AdminTab = "users" | "audit" | "settings";

// Admin's active tab lives at "#admin/<tab>" (bare "#admin" = users) so a
// reload lands back on the same tab instead of always resetting to Users.
function parseAdminTab(): AdminTab {
  const hash = window.location.hash.replace("#", "");
  const sub = hash.startsWith("admin/") ? hash.slice("admin/".length) : "";
  return sub === "audit" || sub === "settings" ? sub : "users";
}

const GROUP_ICON: Record<string, typeof Users> = {
  "Calling & Media": CallIcon,
  "Files & Storage": FolderCog,
  "Email & Password Reset": Mail,
  "Single Sign-On (SSO)": KeyRound,
  "Security & Sessions": ShieldCheck,
  "Network & TLS": Network,
  "Server & Logging": Server,
};

const SOURCE_LABEL: Record<SettingView["source"], string> = {
  env: "Environment / .env",
  db: "Set here",
  default: "Default",
};

function SettingRow({ s, onSaved }: { s: SettingView; onSaved: () => void }) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(s.value);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const startEdit = () => {
    setDraft(s.kind === "secret" ? "" : s.value);
    setError(null);
    setEditing(true);
  };

  const save = async (value = draft) => {
    setBusy(true);
    setError(null);
    try {
      await api.updateSetting(s.key, value);
      setEditing(false);
      onSaved();
    } catch (err: any) {
      setError(err?.message ?? "Could not save");
    }
    setBusy(false);
  };

  const reset = async () => {
    setBusy(true);
    setError(null);
    try {
      await api.resetSetting(s.key);
      onSaved();
    } catch (err: any) {
      setError(err?.message ?? "Could not reset");
    }
    setBusy(false);
  };

  const lockTitle = s.source === "env" ? "Set by an environment variable or .env file — edit that, then restart" : "Fixed at server start — change via environment/.env and restart";

  // Boolean settings are a straight on/off switch with no separate edit
  // step — flipping it saves immediately, same as any other toggle in the app.
  if (s.kind === "bool") {
    const on = s.value === "true";
    return (
      <div className="px-4 py-3 border-b border-line/60 last:border-0 flex items-center justify-between gap-3">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2 flex-wrap">
            <p className="text-sm font-medium">{s.label}</p>
            <span className={`text-[10px] font-semibold uppercase px-1.5 py-0.5 rounded-full ${s.source === "db" ? "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300" : s.source === "env" ? "bg-zinc-100 text-zinc-500 dark:bg-zinc-800 text-zinc-400" : "bg-zinc-100 text-zinc-400 dark:bg-zinc-800 text-zinc-500"}`}>
              {SOURCE_LABEL[s.source]}
            </span>
            {!s.editable && (
              <span title={lockTitle} className="text-zinc-400">
                <Lock className="h-3 w-3" />
              </span>
            )}
          </div>
          {s.description && <p className="text-xs text-zinc-400 mt-0.5">{s.description}</p>}
          {error && <p className="text-xs text-rose-500 mt-1">{error}</p>}
        </div>
        <div className="flex items-center gap-2 shrink-0">
          {busy && <Loader2 className="h-3.5 w-3.5 animate-spin text-zinc-400" />}
          <Switch checked={on} disabled={!s.editable || busy} onChange={(next) => void save(String(next))} label={s.label} />
        </div>
      </div>
    );
  }

  return (
    <div className="px-4 py-3 border-b border-line/60 last:border-0">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2 flex-wrap">
            <p className="text-sm font-medium">{s.label}</p>
            <span className={`text-[10px] font-semibold uppercase px-1.5 py-0.5 rounded-full ${s.source === "db" ? "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300" : s.source === "env" ? "bg-zinc-100 text-zinc-500 dark:bg-zinc-800 text-zinc-400" : "bg-zinc-100 text-zinc-400 dark:bg-zinc-800 text-zinc-500"}`}>
              {SOURCE_LABEL[s.source]}
            </span>
            {!s.editable && (
              <span title={lockTitle} className="text-zinc-400">
                <Lock className="h-3 w-3" />
              </span>
            )}
          </div>
          {s.description && <p className="text-xs text-zinc-400 mt-0.5">{s.description}</p>}
          {!editing && (
            <p className="text-sm font-mono mt-1 text-zinc-600 dark:text-zinc-300 break-all">
              {s.value === "" ? <span className="italic text-zinc-400">empty</span> : s.value}
            </p>
          )}
          {editing && (
            <div className="mt-1.5 flex items-center gap-1.5 flex-wrap">
              <input
                autoFocus
                type={s.kind === "secret" ? "password" : s.kind === "int" ? "number" : "text"}
                className={`${inputCls} flex-1 min-w-[12rem] py-1 text-xs`}
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                placeholder={s.kind === "secret" ? "New value (leave blank to cancel)" : undefined}
                onKeyDown={(e) => e.key === "Enter" && void save()}
              />
              <button onClick={() => void save()} disabled={busy} className="text-xs font-medium px-3 py-1.5 rounded-lg bg-blue-600 hover:bg-blue-500 text-white flex items-center gap-1.5 disabled:opacity-50">
                {busy && <Loader2 className="h-3.5 w-3.5 animate-spin" />} Save
              </button>
              <button onClick={() => setEditing(false)} disabled={busy} className="text-xs font-medium px-3 py-1.5 rounded-lg hover:bg-zinc-100 dark:hover:bg-zinc-800 disabled:opacity-50">
                Cancel
              </button>
            </div>
          )}
          {error && <p className="text-xs text-rose-500 mt-1">{error}</p>}
        </div>
        {s.editable && !editing && (
          <div className="flex items-center gap-1 shrink-0">
            <button onClick={startEdit} className="text-xs px-2.5 py-1.5 rounded-lg hover:bg-zinc-100 dark:hover:bg-zinc-800 font-medium">
              Edit
            </button>
            {s.source === "db" && (
              <button onClick={() => void reset()} disabled={busy} title="Reset to default" aria-label="Reset to default" className="h-7 w-7 rounded-lg hover:bg-zinc-100 dark:hover:bg-zinc-800 flex items-center justify-center text-zinc-400 disabled:opacity-50">
                <RotateCcw className="h-3.5 w-3.5" />
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

function ServerSettings() {
  const [settings, setSettings] = useState<SettingView[]>([]);
  const [loading, setLoading] = useState(true);

  // Only the very first load (and an explicit Refresh click) shows the
  // "Loading…" placeholder — reloading after saving one setting must not
  // collapse the whole list down to a spinner and back, which is what was
  // causing the page to visibly jump every time a setting was changed.
  const load = (showSpinner = false) => {
    if (showSpinner) setLoading(true);
    api.listSettings().then(setSettings).catch(() => {}).finally(() => setLoading(false));
  };
  const reloadAfterEdit = () => load(false);

  useEffect(() => load(true), []);

  const groups = useMemo(() => {
    const byGroup = new Map<string, SettingView[]>();
    for (const s of settings) {
      if (!byGroup.has(s.group)) byGroup.set(s.group, []);
      byGroup.get(s.group)!.push(s);
    }
    return byGroup;
  }, [settings]);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <SettingsIcon className="h-4 w-4 text-zinc-400" />
          <h2 className="font-semibold text-base">Server Settings</h2>
        </div>
        <button onClick={() => load(true)} className={btnGhost}>
          <RefreshCw className="h-4 w-4" /> Refresh
        </button>
      </div>
      <p className="text-xs text-zinc-400">
        Priority: environment variable / Docker <code>environment:</code> &gt; <code>.env</code> file &gt; set here &gt; default.
        Settings marked <Lock className="h-3 w-3 inline" /> are pinned by the environment/.env and can only be changed there, then a restart.
      </p>
      {loading ? (
        <p className="p-6 text-center text-xs text-zinc-400 flex items-center justify-center gap-1.5">
          <Loader2 className="h-3.5 w-3.5 animate-spin" /> Loading…
        </p>
      ) : (
        Array.from(groups.entries()).map(([group, items]) => {
          const GroupIcon = GROUP_ICON[group] ?? SettingsIcon;
          return (
            <section key={group} className="rounded-xl border border-line bg-surface overflow-hidden shadow-sm">
              <div className="px-4 py-2.5 border-b border-line bg-surface-hover/40 flex items-center gap-2">
                <GroupIcon className="h-4 w-4 text-brand" />
                <h3 className="text-sm font-semibold">{group}</h3>
              </div>
              {items.map((s) => (
                <SettingRow key={s.key} s={s} onSaved={reloadAfterEdit} />
              ))}
            </section>
          );
        })
      )}
    </div>
  );
}

function AuditLog() {
  const [entries, setEntries] = useState<AuditEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [done, setDone] = useState(false);
  const [query, setQuery] = useState("");

  const mounted = useRef(true);
  useEffect(() => () => { mounted.current = false; }, []);

  const load = async (before?: number) => {
    const page = await api.auditLog(before);
    if (!mounted.current) return;
    setEntries((prev) => (before ? [...prev, ...page] : page));
    if (page.length < 100) setDone(true);
  };

  useEffect(() => {
    setLoading(true);
    load().finally(() => { if (mounted.current) setLoading(false); });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const loadMore = async () => {
    if (entries.length === 0) return;
    setLoadingMore(true);
    await load(entries[entries.length - 1].id).finally(() => { if (mounted.current) setLoadingMore(false); });
  };

  const filtered = query.trim()
    ? entries.filter((e) =>
        `${e.actor_name} ${e.action} ${e.target_type} ${e.detail} ${e.ip}`.toLowerCase().includes(query.trim().toLowerCase()),
      )
    : entries;

  return (
    <div className="rounded-xl border border-line bg-surface overflow-hidden shadow-sm">
      <div className="p-4 border-b border-line flex flex-col sm:flex-row sm:items-center gap-3">
        <div className="flex items-center gap-2">
          <ScrollText className="h-4 w-4 text-ink-muted" />
          <h2 className="font-semibold text-base">Audit Log</h2>
        </div>
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Filter actions, actors, IPs…"
          aria-label="Filter audit log"
          className="sm:ml-auto rounded-lg border border-line bg-surface text-ink px-3 py-1.5 text-xs w-full sm:w-64 outline-none focus:border-brand focus:ring-2 focus:ring-brand/20"
        />
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs text-ink-muted border-b border-line bg-surface-hover/40">
              <th className="px-4 py-2.5 font-medium">When</th>
              <th className="px-4 py-2.5 font-medium">Actor</th>
              <th className="px-4 py-2.5 font-medium">Action</th>
              <th className="px-4 py-2.5 font-medium">Target</th>
              <th className="px-4 py-2.5 font-medium">Detail</th>
              <th className="px-4 py-2.5 font-medium">IP</th>
            </tr>
          </thead>
          <tbody>
            {filtered.map((e) => (
              <tr key={e.id} className="border-b border-line/60 last:border-0 hover:bg-surface-hover/50 transition">
                <td className="px-4 py-2 text-xs text-ink-muted whitespace-nowrap">{new Date(e.created_at).toLocaleString()}</td>
                <td className="px-4 py-2 text-xs">{e.actor_name}</td>
                <td className="px-4 py-2 text-xs font-mono">{e.action}</td>
                <td className="px-4 py-2 text-xs text-ink-muted">{e.target_type}{e.target_id ? ` #${e.target_id}` : ""}</td>
                <td className="px-4 py-2 text-xs text-ink-muted max-w-xs truncate" title={e.detail}>{e.detail}</td>
                <td className="px-4 py-2 text-xs text-ink-muted">{e.ip}</td>
              </tr>
            ))}
            {!loading && filtered.length === 0 && (
              <tr>
                <td colSpan={6} className="text-center py-8 text-ink-muted text-xs">{query ? "No entries match this filter." : "No audit entries yet."}</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      {!done && entries.length > 0 && (
        <div className="p-3 flex justify-center border-t border-line">
          <button onClick={() => void loadMore()} disabled={loadingMore} className={btnGhost}>
            {loadingMore ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
            Load more
          </button>
        </div>
      )}
    </div>
  );
}

function StatCard({ icon: Icon, label, value }: { icon: typeof Users; label: string; value: string }) {
  return (
    <div className="rounded-xl border border-line bg-surface p-4 flex items-center gap-4 shadow-2xs hover:shadow-md hover:border-brand/40 transition-all duration-150">
      <div className="h-11 w-11 rounded-xl bg-brand/15 text-brand flex items-center justify-center shrink-0 shadow-2xs">
        <Icon className="h-5 w-5" />
      </div>
      <div className="min-w-0">
        <p className="text-xl font-bold tracking-tight text-ink leading-tight">{value}</p>
        <p className="text-xs text-ink-muted mt-0.5 font-medium">{label}</p>
      </div>
    </div>
  );
}

export default function Admin() {
  const me = useAuth((s) => s.me)!;
  const { users, fetchUsers, loaded: usersLoaded, error: usersError } = useDirectory();
  const [stats, setStats] = useState<AdminStats | null>(null);
  const [editing, setEditing] = useState<User | null>(null);
  const [creating, setCreating] = useState(false);
  const [userToDelete, setUserToDelete] = useState<User | null>(null);
  const [userToDisable, setUserToDisable] = useState<User | null>(null);
  const [disablingBusy, setDisablingBusy] = useState(false);
  const [deletingBusy, setDeletingBusy] = useState(false);
  const [savingBusy, setSavingBusy] = useState(false);
  const [unlinkingSso, setUnlinkingSso] = useState(false);

  const [search, setSearch] = useState("");
  const [roleFilter, setRoleFilter] = useState<"all" | "admin" | "user" | "disabled">("all");

  const [editForm, setEditForm] = useState({ display_name: "", role: "user", password: "", email: "" });
  const [showEditPwd, setShowEditPwd] = useState(false);

  const [createForm, setCreateForm] = useState({ username: "", display_name: "", password: "", role: "user", email: "" });
  const [showCreatePwd, setShowCreatePwd] = useState(false);

  const [error, setError] = useState<string | null>(null);
  const [tab, setTabState] = useState<AdminTab>(parseAdminTab);
  // Kept in the URL hash (admin/audit, admin/settings) so a reload - or a
  // bookmark/shared link - lands back on the same tab instead of always
  // resetting to Users.
  const setTab = (t: AdminTab) => {
    setTabState(t);
    window.location.hash = t === "users" ? "admin" : `admin/${t}`;
  };
  useEffect(() => {
    const onHashChange = () => setTabState(parseAdminTab());
    window.addEventListener("hashchange", onHashChange);
    return () => window.removeEventListener("hashchange", onHashChange);
  }, []);

  const loadStats = async () => {
    try {
      setStats(await api.adminStats());
    } catch {
      /* ignore */
    }
  };

  useEffect(() => {
    void fetchUsers();
    void loadStats();
    const t = setInterval(() => void loadStats(), 10000);
    return () => clearInterval(t);
  }, [fetchUsers]);

  const filteredUsers = useMemo(() => {
    return users.filter((u) => {
      const matchSearch = search
        ? u.display_name.toLowerCase().includes(search.toLowerCase()) ||
          u.username.toLowerCase().includes(search.toLowerCase())
        : true;
      let matchFilter = true;
      if (roleFilter === "admin") matchFilter = u.role === "admin";
      else if (roleFilter === "user") matchFilter = u.role === "user" && !u.disabled;
      else if (roleFilter === "disabled") matchFilter = !!u.disabled;
      return matchSearch && matchFilter;
    });
  }, [users, search, roleFilter]);

  const openEdit = (u: User) => {
    setEditing(u);
    setError(null);
    setShowEditPwd(false);
    setEditForm({ display_name: u.display_name, role: u.role, password: "", email: u.email ?? "" });
  };

  const unlinkUserSso = async () => {
    if (!editing) return;
    setUnlinkingSso(true);
    setError(null);
    try {
      await api.adminOidcUnlink(editing.id);
      setEditing({ ...editing, oidc_linked: false });
      void fetchUsers();
    } catch (err: any) {
      setError(err?.message ?? "Could not unlink SSO");
    }
    setUnlinkingSso(false);
  };

  const saveEdit = async (e: FormEvent) => {
    e.preventDefault();
    if (!editing) return;
    setError(null);
    setSavingBusy(true);
    try {
      await api.updateUser(editing.id, {
        display_name: editForm.display_name,
        role: editForm.role,
        email: editForm.email,
        ...(editForm.password ? { password: editForm.password } : {}),
      });
      setEditing(null);
      void fetchUsers();
    } catch (err: any) {
      setError(err?.message ?? "Could not save changes");
    }
    setSavingBusy(false);
  };

  const saveCreate = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setSavingBusy(true);
    try {
      await api.createUser(createForm);
      setCreating(false);
      setCreateForm({ username: "", display_name: "", password: "", role: "user", email: "" });
      void fetchUsers();
    } catch (err: any) {
      setError(err?.message ?? "Could not create user");
    }
    setSavingBusy(false);
  };

  const toggleDisabled = async (u: User) => {
    setError(null);
    setDisablingBusy(true);
    try {
      await api.updateUser(u.id, { disabled: !u.disabled });
      setUserToDisable(null);
      void fetchUsers();
    } catch (err: any) {
      setError(err?.message ?? "Could not update account");
    }
    setDisablingBusy(false);
  };

  const confirmDelete = async () => {
    if (!userToDelete) return;
    setError(null);
    setDeletingBusy(true);
    try {
      await api.deleteUser(userToDelete.id);
      setUserToDelete(null);
      void fetchUsers();
    } catch (err: any) {
      setError(err?.message ?? "Could not delete account");
    }
    setDeletingBusy(false);
  };

  return (
    <div className="flex-1 overflow-y-auto">
      <div className="max-w-5xl mx-auto p-4 md:p-6 space-y-6">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-xl font-bold">Admin Panel</h1>
            <p className="text-xs text-zinc-400">Manage users, view usage metrics, and audit storage</p>
          </div>
          <button onClick={() => void loadStats()} className={btnGhost}>
            <RefreshCw className="h-4 w-4" /> Refresh
          </button>
        </div>

        {error && !editing && !creating && !userToDelete && <Alert variant="error">{error}</Alert>}

        {/* Top-level sections: kept as distinct tabs, not one long scroll,
            so Users / Audit / Settings each read as their own page. */}
        <Tabs
          className="border-b border-line pb-1"
          active={tab}
          onChange={(id) => setTab(id as AdminTab)}
          tabs={[
            { key: "users", label: "Users", icon: <Users className="h-4 w-4" /> },
            { key: "audit", label: "Audit Log", icon: <ScrollText className="h-4 w-4" /> },
            { key: "settings", label: "Server Settings", icon: <SettingsIcon className="h-4 w-4" /> },
          ]}
        />

        {tab === "users" && stats && (
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
            <StatCard icon={Users} label="Total accounts" value={String(stats.users)} />
            <StatCard icon={Activity} label="Online now" value={String(stats.online)} />
            <StatCard icon={PhoneCall} label="Active calls" value={String(stats.active_calls)} />
            <StatCard icon={HardDrive} label={`Storage used (${stats.db_driver})`} value={fmtBytes(stats.storage.total_bytes)} />
          </div>
        )}

        {tab === "users" && (
        <div className="rounded-xl border border-line bg-surface overflow-hidden shadow-sm">
          <div className="p-4 border-b border-line flex flex-col sm:flex-row sm:items-center justify-between gap-3">
            <div className="flex flex-wrap items-center gap-3">
              <h2 className="font-semibold text-base text-ink">Users ({filteredUsers.length})</h2>
              <div className="flex flex-wrap gap-1">
                {(["all", "admin", "user", "disabled"] as const).map((rf) => (
                  <button
                    key={rf}
                    onClick={() => setRoleFilter(rf)}
                    className={`px-2.5 py-0.5 rounded-lg text-xs font-medium capitalize transition ${
                      roleFilter === rf
                        ? "bg-brand text-white shadow-xs"
                        : "text-ink-secondary hover:bg-surface-hover"
                    }`}
                  >
                    {rf}
                  </button>
                ))}
              </div>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <div className="relative flex-1 sm:w-48">
                <Search className="absolute left-2.5 top-2.5 h-3.5 w-3.5 text-ink-muted" />
                <input
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  placeholder="Search…"
                  className={`${inputCls} pl-8 py-1.5 text-xs`}
                />
              </div>
              <button onClick={() => { setCreating(true); setError(null); setShowCreatePwd(false); }} className={btnPrimary}>
                <UserPlus className="h-4 w-4" /> New User
              </button>
            </div>
          </div>

          {!usersLoaded && !usersError && <SkeletonList rows={5} />}
          {usersError && users.length === 0 && (
            <EmptyState icon={<AlertTriangle className="h-5 w-5" />} title="Couldn't load users" hint={usersError} />
          )}
          {usersLoaded && filteredUsers.length === 0 && (
            <EmptyState icon={<Users className="h-5 w-5" />} title="No matching users" hint="Try a different search term or filter." />
          )}
          {usersLoaded && filteredUsers.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs text-ink-muted border-b border-line bg-surface-hover/40">
                  <th className="px-4 py-2.5 font-medium">User</th>
                  <th className="px-4 py-2.5 font-medium">Role</th>
                  <th className="px-4 py-2.5 font-medium">Status</th>
                  <th className="px-4 py-2.5 font-medium text-right">Actions</th>
                </tr>
              </thead>
              <tbody>
                {filteredUsers.map((u) => (
                  <tr key={u.id} className="border-b border-line/60 last:border-0 hover:bg-surface-hover/50 transition">
                    <td className="px-4 py-2.5">
                      <div className="flex items-center gap-2.5">
                        <Avatar name={u.display_name} id={u.id} fileId={u.avatar_file_id} size="sm" />
                        <div>
                          <p className="font-medium text-ink">
                            {u.display_name}
                            {u.disabled && <span className="ml-2 text-[10px] uppercase text-rose-500 font-bold bg-rose-500/10 px-1.5 py-0.5 rounded">disabled</span>}
                            {u.oidc_linked && <span className="ml-2 text-[10px] uppercase text-brand font-bold bg-brand/15 px-1.5 py-0.5 rounded">SSO</span>}
                          </p>
                          <p className="text-xs text-ink-muted">@{u.username}</p>
                        </div>
                      </div>
                    </td>
                    <td className="px-4 py-2.5">
                      <span className={`text-xs font-semibold rounded-full px-2 py-0.5 ${u.role === "admin" ? "bg-brand/20 text-brand" : "bg-surface-hover text-ink-muted"}`}>
                        {u.role}
                      </span>
                    </td>
                    <td className="px-4 py-2.5">
                      <div className="flex items-center gap-1.5 text-xs text-ink-muted">
                        <PresenceDot status={u.status} /> {presenceLabel(u.status)}
                      </div>
                    </td>
                    <td className="px-4 py-2.5">
                      <div className="flex justify-end gap-1.5">
                        <button onClick={() => openEdit(u)} className="text-xs px-3 py-2 rounded-lg hover:bg-surface-hover font-medium min-h-[36px] text-ink">
                          Edit
                        </button>
                        <button
                          onClick={() => { setError(null); setUserToDisable(u); }}
                          disabled={u.id === me.id}
                          className="text-xs px-3 py-2 rounded-lg hover:bg-surface-hover font-medium disabled:opacity-30 min-h-[36px] text-ink"
                        >
                          {u.disabled ? "Enable" : "Disable"}
                        </button>
                        <button
                          onClick={() => { setError(null); setUserToDelete(u); }}
                          disabled={u.id === me.id}
                          className="text-xs px-3 py-2 rounded-lg text-rose-500 hover:bg-rose-500/10 font-medium disabled:opacity-30 min-h-[36px]"
                        >
                          Delete
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          )}
        </div>
        )}

        {tab === "audit" && <AuditLog />}

        {tab === "settings" && <ServerSettings />}

        {/* Edit User Modal */}
        <Modal open={!!editing} onClose={() => setEditing(null)} title={`Edit ${editing?.display_name ?? ""}`}>
          <form onSubmit={saveEdit} className="space-y-4">
            <div>
              <label htmlFor="edit-user-name" className="block text-xs font-semibold text-zinc-500 mb-1">Display Name</label>
              <input id="edit-user-name" className={inputCls} value={editForm.display_name} onChange={(e) => setEditForm({ ...editForm, display_name: e.target.value })} />
            </div>
            <div>
              <label htmlFor="edit-user-role" className="block text-xs font-semibold text-zinc-500 mb-1">Role</label>
              <select id="edit-user-role" className={inputCls} value={editForm.role} onChange={(e) => setEditForm({ ...editForm, role: e.target.value })} disabled={editing?.id === me.id}>
                <option value="user">User</option>
                <option value="admin">Admin</option>
              </select>
            </div>
            <div>
              <label htmlFor="edit-user-email" className="block text-xs font-semibold text-zinc-500 mb-1">
                Email <span className="font-normal normal-case text-zinc-400">(enables self-service password reset)</span>
              </label>
              <input
                id="edit-user-email"
                type="email"
                className={inputCls}
                value={editForm.email}
                onChange={(e) => setEditForm({ ...editForm, email: e.target.value })}
                placeholder="name@example.com"
              />
            </div>
            {editing?.oidc_linked && (
              <div className="flex items-center justify-between rounded-xl border border-line px-3 py-2">
                <p className="text-xs text-zinc-500">SSO-linked account</p>
                <button
                  type="button"
                  onClick={() => void unlinkUserSso()}
                  disabled={unlinkingSso}
                  className="text-xs font-medium text-rose-600 dark:text-rose-400 hover:underline disabled:opacity-50"
                >
                  {unlinkingSso ? "Unlinking…" : "Unlink SSO"}
                </button>
              </div>
            )}
            <div>
              <label htmlFor="edit-user-password" className="block text-xs font-semibold text-zinc-500 mb-1">
                Reset Password (leave blank to keep current)
              </label>
              <div className="relative">
                <input
                  id="edit-user-password"
                  className={inputCls}
                  type={showEditPwd ? "text" : "password"}
                  value={editForm.password}
                  onChange={(e) => setEditForm({ ...editForm, password: e.target.value })}
                  placeholder="New password (min 8 chars)"
                  autoComplete="new-password"
                />
                <button
                  type="button"
                  onClick={() => setShowEditPwd(!showEditPwd)}
                  aria-label={showEditPwd ? "Hide password" : "Show password"}
                  className="absolute right-3 top-2.5 text-zinc-400 hover:text-zinc-600"
                >
                  {showEditPwd ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
            </div>
            {error && <Alert variant="error">{error}</Alert>}
            <button className={`${btnPrimary} w-full`} disabled={savingBusy}>
              {savingBusy ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
              Save Changes
            </button>
          </form>
        </Modal>

        {/* Create User Modal */}
        <Modal open={creating} onClose={() => setCreating(false)} title="Create User">
          <form onSubmit={saveCreate} className="space-y-4">
            <div>
              <label htmlFor="create-user-username" className="block text-xs font-semibold text-zinc-500 mb-1">Username</label>
              <input id="create-user-username" className={inputCls} value={createForm.username} onChange={(e) => setCreateForm({ ...createForm, username: e.target.value })} autoFocus />
            </div>
            <div>
              <label htmlFor="create-user-name" className="block text-xs font-semibold text-zinc-500 mb-1">Display Name</label>
              <input id="create-user-name" className={inputCls} value={createForm.display_name} onChange={(e) => setCreateForm({ ...createForm, display_name: e.target.value })} />
            </div>
            <div>
              <label htmlFor="create-user-password" className="block text-xs font-semibold text-zinc-500 mb-1">Password</label>
              <div className="relative">
                <input
                  id="create-user-password"
                  className={inputCls}
                  type={showCreatePwd ? "text" : "password"}
                  value={createForm.password}
                  onChange={(e) => setCreateForm({ ...createForm, password: e.target.value })}
                  autoComplete="new-password"
                  placeholder="Min 8 characters"
                />
                <button
                  type="button"
                  onClick={() => setShowCreatePwd(!showCreatePwd)}
                  aria-label={showCreatePwd ? "Hide password" : "Show password"}
                  className="absolute right-3 top-2.5 text-zinc-400 hover:text-zinc-600"
                >
                  {showCreatePwd ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
            </div>
            <div>
              <label htmlFor="create-user-role" className="block text-xs font-semibold text-zinc-500 mb-1">Role</label>
              <select id="create-user-role" className={inputCls} value={createForm.role} onChange={(e) => setCreateForm({ ...createForm, role: e.target.value })}>
                <option value="user">User</option>
                <option value="admin">Admin</option>
              </select>
            </div>
            <div>
              <label htmlFor="create-user-email" className="block text-xs font-semibold text-zinc-500 mb-1">
                Email <span className="font-normal normal-case text-zinc-400">(optional; enables self-service password reset)</span>
              </label>
              <input
                id="create-user-email"
                type="email"
                className={inputCls}
                value={createForm.email}
                onChange={(e) => setCreateForm({ ...createForm, email: e.target.value })}
                placeholder="name@example.com"
              />
            </div>
            {error && <Alert variant="error">{error}</Alert>}
            <button className={`${btnPrimary} w-full`} disabled={savingBusy || !createForm.username || createForm.password.length < 8}>
              {savingBusy ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
              Create User
            </button>
          </form>
        </Modal>
        {/* Disable / Enable Confirmation */}
        <Modal open={!!userToDisable} onClose={() => setUserToDisable(null)} title={`${userToDisable?.disabled ? "Enable" : "Disable"} account?`}>
          <div className="space-y-4">
            <p className="text-sm text-zinc-600 dark:text-zinc-300">
              {userToDisable?.disabled
                ? `${userToDisable?.display_name} will be able to sign in again.`
                : `${userToDisable?.display_name} will be signed out immediately and unable to sign in.`}
            </p>
            <div className="flex justify-end gap-2">
              <button onClick={() => setUserToDisable(null)} className={btnSecondary} disabled={disablingBusy}>
                Cancel
              </button>
              <button
                onClick={() => userToDisable && void toggleDisabled(userToDisable)}
                disabled={disablingBusy}
                className={btnPrimary}
              >
                {disablingBusy ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
                {userToDisable?.disabled ? "Enable" : "Disable"}
              </button>
            </div>
          </div>
        </Modal>

        {/* Delete Confirmation Modal */}
        <Modal open={!!userToDelete} onClose={() => setUserToDelete(null)} title="Delete Account">
          <div className="space-y-4">
            <div className="flex items-start gap-3 bg-rose-50 dark:bg-rose-950/40 p-3.5 rounded-xl text-rose-800 dark:text-rose-200 border border-rose-200 dark:border-rose-900">
              <AlertTriangle className="h-5 w-5 shrink-0 text-rose-600" />
              <div className="text-xs space-y-1">
                <p className="font-semibold">Are you sure you want to delete {userToDelete?.display_name} (@{userToDelete?.username})?</p>
                <p className="text-rose-600 dark:text-rose-300">This action cannot be undone. The account is signed out, disabled, and stripped of its name, avatar, and login — but messages and calls stay in the conversation history of whoever they were with.</p>
              </div>
            </div>
            {error && <Alert variant="error">{error}</Alert>}
            <div className="flex justify-end gap-2 pt-2">
              <button onClick={() => setUserToDelete(null)} className={btnGhost}>
                Cancel
              </button>
              <button
                onClick={confirmDelete}
                disabled={deletingBusy}
                className={btnDestructive}
              >
                {deletingBusy ? <Loader2 className="h-4 w-4 animate-spin" /> : <Trash2 className="h-4 w-4" />}
                {deletingBusy ? "Deleting…" : "Delete Account"}
              </button>
            </div>
          </div>
        </Modal>
      </div>
    </div>
  );
}

