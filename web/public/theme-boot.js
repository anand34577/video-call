// Runs before the app bundle (see index.html) so the saved theme applies
// before first paint. Mirrors lib/theme.ts applyTheme's DOM writes.
(function () {
  try {
    var h = document.documentElement;
    var p = JSON.parse(localStorage.getItem("vc.theme.prefs") || "null");
    var theme = (p && p.theme) || localStorage.getItem("vc.theme") || "dark";
    h.classList.toggle("dark", theme !== "light");
    if (p) {
      h.dataset.theme = p.theme;
      h.dataset.accent = p.accent_color;
      h.dataset.radius = p.radius;
    }
  } catch (e) {}
})();
