// Note: minimal service worker — its job is PWA installability (home
// screen icon, standalone window) and a friendlier reload after a brief
// network blip, not offline messaging. It never touches /api or /ws, so
// auth, chat, and calls always go straight to the live server.
const CACHE = "vc-shell-v1";
const SHELL_PATHS = ["/", "/manifest.json"];
// Each deploy adds new content-hashed assets under the same cache name;
// keep only the most recent entries so the cache doesn't grow forever.
const MAX_ENTRIES = 40;

async function trim(cache) {
  const keys = await cache.keys();
  for (const req of keys.slice(0, Math.max(0, keys.length - MAX_ENTRIES))) {
    if (!SHELL_PATHS.includes(new URL(req.url).pathname)) await cache.delete(req);
  }
}

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL_PATHS)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (event.request.method !== "GET") return;
  if (url.pathname.startsWith("/api/") || url.pathname === "/ws") return;

  // Network-first for the app shell so a redeploy is picked up immediately;
  // fall back to cache only when the network is actually unreachable.
  event.respondWith(
    fetch(event.request)
      .then((res) => {
        if (res.ok) {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put(event.request, copy).then(() => trim(c)));
        }
        return res;
      })
      .catch(() => caches.match(event.request).then((cached) => cached || caches.match("/"))),
  );
});
