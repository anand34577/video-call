/** Media capture, device management, ringtone and notification helpers. */

import { toast } from "../store/toast";

// getDisplayMedia (screen/tab capture — used for both screen-sharing and the
// local call-recording feature) isn't implemented by mobile browsers at all
// (Chrome/Firefox/Safari on Android and iOS). Callers use this to disable
// those buttons with an explanatory tooltip instead of a silent/confusing
// failure once tapped.
export function supportsScreenCapture(): boolean {
  return typeof navigator.mediaDevices?.getDisplayMedia === "function";
}

export interface DeviceList {
  mics: MediaDeviceInfo[];
  cams: MediaDeviceInfo[];
  speakers: MediaDeviceInfo[];
}

let cachedDevices: DeviceList | null = null;
const deviceListeners = new Set<() => void>();

export async function listDevices(force = false): Promise<DeviceList> {
  if (cachedDevices && !force) return cachedDevices;
  try {
    if (!navigator?.mediaDevices?.enumerateDevices) {
      cachedDevices = { mics: [], cams: [], speakers: [] };
      return cachedDevices;
    }
    const devices = await navigator.mediaDevices.enumerateDevices();
    cachedDevices = {
      mics: devices.filter((d) => d.kind === "audioinput"),
      cams: devices.filter((d) => d.kind === "videoinput"),
      speakers: devices.filter((d) => d.kind === "audiooutput"),
    };
  } catch {
    cachedDevices = { mics: [], cams: [], speakers: [] };
  }
  return cachedDevices;
}

export function onDevicesChanged(cb: () => void): () => void {
  const handler = () => {
    cachedDevices = null;
    cb();
  };
  deviceListeners.add(handler);
  navigator?.mediaDevices?.addEventListener?.("devicechange", handler);
  return () => {
    deviceListeners.delete(handler);
    navigator?.mediaDevices?.removeEventListener?.("devicechange", handler);
  };
}

const prefs = {
  mic: localStorage.getItem("vc.mic") || undefined,
  cam: localStorage.getItem("vc.cam") || undefined,
  speaker: localStorage.getItem("vc.speaker") || undefined,
};

export function getPreferredDevice(kind: "mic" | "cam" | "speaker"): string | undefined {
  return prefs[kind];
}

export function setPreferredDevice(kind: "mic" | "cam" | "speaker", id: string | undefined) {
  prefs[kind] = id;
  if (id) localStorage.setItem(`vc.${kind}`, id);
  else localStorage.removeItem(`vc.${kind}`);
  window.dispatchEvent(new Event("vc:device-preference"));
}

/** Acquire mic (+optional camera) honoring saved device preferences. */
export async function acquireLocalMedia(video: boolean): Promise<MediaStream> {
  if (typeof navigator === "undefined" || !navigator.mediaDevices?.getUserMedia) {
    throw new Error("Microphone and camera require a Secure Context (HTTPS or localhost). Please connect via HTTPS.");
  }
  const audio: MediaTrackConstraints | boolean = prefs.mic
    ? { deviceId: { ideal: prefs.mic } }
    : true;
  const videoConstraints: MediaTrackConstraints | boolean = video
    ? prefs.cam
      ? { deviceId: { ideal: prefs.cam }, width: { ideal: 1280 }, height: { ideal: 720 } }
      : { width: { ideal: 1280 }, height: { ideal: 720 } }
    : false;

  if (video) {
    try {
      return await navigator.mediaDevices.getUserMedia({ audio, video: videoConstraints });
    } catch {
      // Fallback to audio only if camera is blocked or unavailable
      return navigator.mediaDevices.getUserMedia({ audio, video: false });
    }
  }

  return navigator.mediaDevices.getUserMedia({ audio, video: false });
}

// ---- ringtone (WebAudio; no binary assets) ----

let ringCtx: AudioContext | null = null;
let ringTimer: ReturnType<typeof setTimeout> | null = null;

function playRingCycle() {
  const ctx = ringCtx!;
  const t0 = ctx.currentTime;
  // classic two-bell pattern: 440+480 Hz, 2s on / 4s off
  for (let i = 0; i < 4; i++) {
    const start = t0 + i * 0.5;
    const dur = 0.4;
    for (const freq of [440, 480]) {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.frequency.value = freq;
      osc.type = "sine";
      gain.gain.setValueAtTime(0.0001, start);
      gain.gain.exponentialRampToValueAtTime(0.18, start + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, start + dur);
      osc.connect(gain).connect(ctx.destination);
      osc.start(start);
      osc.stop(start + dur + 0.05);
    }
  }
  ringTimer = setTimeout(playRingCycle, 4000);
}

export function startRingtone() {
  if (ringTimer) return;
  try {
    ringCtx ??= new AudioContext();
    if (ringCtx.state === "suspended") void ringCtx.resume();
    playRingCycle();
  } catch {
    /* audio unavailable */
  }
}

export function stopRingtone() {
  if (ringTimer) {
    clearTimeout(ringTimer);
    ringTimer = null;
  }
}

// ---- notifications ----
//
// While Vision Call is the focused window, new messages show as an in-app
// toast. Otherwise a system notification is shown (through the service
// worker where possible, which is the only way that works on Android
// Chrome). Browsers only allow system notifications over HTTPS.

const clickHandlers = new Map<string, () => void>();

export function notificationsSupported(): boolean {
  return typeof Notification !== "undefined" && window.isSecureContext;
}

export function getNotificationPermissionStatus(): NotificationPermission {
  if (!notificationsSupported()) return "denied";
  return Notification.permission;
}

export async function requestNotificationPermission(): Promise<NotificationPermission> {
  if (!notificationsSupported()) return "denied";
  if (Notification.permission === "default") {
    try {
      return await Notification.requestPermission();
    } catch {
      /* ignore */
    }
  }
  return Notification.permission;
}

// The service worker tells the page which notification was clicked.
if (typeof navigator !== "undefined" && "serviceWorker" in navigator) {
  navigator.serviceWorker.addEventListener("message", (e) => {
    if (e.data?.type !== "notification-click") return;
    window.focus();
    clickHandlers.get(e.data.tag)?.();
  });
}

let chimeCtx: AudioContext | null = null;

/** A short, soft two-note chime for new messages. */
export function playMessageChime() {
  try {
    chimeCtx ??= new AudioContext();
    const ctx = chimeCtx;
    if (ctx.state === "suspended") void ctx.resume();
    const t0 = ctx.currentTime;
    [880, 1320].forEach((freq, i) => {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      const start = t0 + i * 0.09;
      osc.type = "sine";
      osc.frequency.value = freq;
      gain.gain.setValueAtTime(0.0001, start);
      gain.gain.exponentialRampToValueAtTime(0.08, start + 0.015);
      gain.gain.exponentialRampToValueAtTime(0.0001, start + 0.25);
      osc.connect(gain).connect(ctx.destination);
      osc.start(start);
      osc.stop(start + 0.3);
    });
  } catch {
    /* audio unavailable */
  }
}

export interface NotifyOptions {
  /** Groups repeated notifications, e.g. one per conversation. */
  tag?: string;
  /** Opens whatever the notification is about. */
  onClick?: () => void;
  /** Show a toast when the app is in front (default true). */
  inApp?: boolean;
  /** Play the message chime (default true). */
  sound?: boolean;
}

export function notify(title: string, body: string, opts: NotifyOptions = {}) {
  const { tag = "visioncall", onClick, inApp = true, sound = true } = opts;
  const inFront = document.visibilityState === "visible" && document.hasFocus();
  if (sound) playMessageChime();

  if (inFront || getNotificationPermissionStatus() !== "granted") {
    // No system notification needed or possible: show a toast instead. It's
    // still on screen when the user comes back to the tab.
    if (inApp) toast.message(title, body, onClick);
    return;
  }

  if (onClick) clickHandlers.set(tag, onClick);
  const options: NotificationOptions = { body, tag, icon: "/icon-192.png", badge: "/icon-192.png" };
  const showDirect = () => {
    try {
      const n = new Notification(title, options);
      n.onclick = () => {
        window.focus();
        onClick?.();
        n.close();
      };
    } catch {
      if (inApp) toast.message(title, body, onClick);
    }
  };
  if ("serviceWorker" in navigator && navigator.serviceWorker.controller) {
    navigator.serviceWorker.ready.then((reg) => reg.showNotification(title, options)).catch(showDirect);
  } else {
    showDirect();
  }
}

// ---- audio meter & speaker test ----

export function createAudioMeter(stream: MediaStream, onVolume: (vol: number) => void): () => void {
  const audioTracks = stream.getAudioTracks();
  if (!audioTracks.length) return () => {};

  try {
    const AudioContextClass = window.AudioContext || (window as any).webkitAudioContext;
    const ctx = new AudioContextClass();
    const source = ctx.createMediaStreamSource(stream);
    const analyser = ctx.createAnalyser();
    analyser.fftSize = 256;
    analyser.smoothingTimeConstant = 0.5;
    source.connect(analyser);

    const buffer = new Uint8Array(analyser.frequencyBinCount);
    let animId: number;

    const tick = () => {
      analyser.getByteFrequencyData(buffer);
      let sum = 0;
      for (let i = 0; i < buffer.length; i++) {
        sum += buffer[i];
      }
      const avg = sum / buffer.length;
      const normalized = Math.min(100, Math.round((avg / 128) * 100));
      onVolume(normalized);
      animId = requestAnimationFrame(tick);
    };
    animId = requestAnimationFrame(tick);

    return () => {
      cancelAnimationFrame(animId);
      source.disconnect();
      analyser.disconnect();
      void ctx.close().catch(() => {});
    };
  } catch {
    return () => {};
  }
}

export function playTestSound(): void {
  try {
    const AudioContextClass = window.AudioContext || (window as any).webkitAudioContext;
    const ctx = new AudioContextClass();
    const sinkID = getPreferredDevice("speaker");
    if (sinkID && (ctx as any).setSinkId) {
      void (ctx as any).setSinkId(sinkID).catch(() => {});
    }
    const t0 = ctx.currentTime;
    const notes = [523.25, 659.25, 783.99, 1046.50]; // C5, E5, G5, C6 arpeggio
    notes.forEach((freq, idx) => {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      const start = t0 + idx * 0.12;
      const dur = 0.25;
      osc.frequency.value = freq;
      osc.type = "sine";
      gain.gain.setValueAtTime(0.0001, start);
      gain.gain.exponentialRampToValueAtTime(0.2, start + 0.03);
      gain.gain.exponentialRampToValueAtTime(0.0001, start + dur);
      osc.connect(gain).connect(ctx.destination);
      osc.start(start);
      osc.stop(start + dur + 0.05);
    });
    setTimeout(() => void ctx.close().catch(() => {}), 1000);
  } catch {
    /* audio unavailable */
  }
}

// ---- misc ----

export function stopStream(stream: MediaStream | null | undefined) {
  stream?.getTracks().forEach((t) => t.stop());
}

