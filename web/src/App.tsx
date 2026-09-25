import { useEffect } from "react";
import { Loader2 } from "lucide-react";
import { useAuth } from "./store/auth";
import { useDirectory } from "./store/directory";
import { useChats } from "./store/chats";
import { useCalls } from "./store/calls";
import { ws } from "./lib/ws";
import { setUnauthorizedHandler } from "./lib/api";
import { clearLocalUserData } from "./lib/session";
import { ensureDeviceRegistered } from "./lib/crypto";
import Login from "./pages/Login";
import ResetPassword from "./pages/ResetPassword";
import Shell from "./pages/Shell";
import CallOverlay from "./components/CallOverlay";
import IncomingCallModal from "./components/IncomingCallModal";
import RoomJoinRequests from "./components/RoomJoinRequests";
import { ToastHost } from "./components/ui";

// A password-reset email link (/reset-password?token=...) works whether or
// not the visitor is currently signed in, so App checks for it before the
// regular auth gate. No router needed for one static link shape.
function resetPasswordToken(): string | null {
  if (location.pathname !== "/reset-password") return null;
  return new URLSearchParams(location.search).get("token");
}

export default function App() {
  const { me, initialized, init, setMe } = useAuth();

  useEffect(() => {
    // restore theme before first paint of logged-in app
    const theme = localStorage.getItem("vc.theme");
    document.documentElement.classList.toggle("dark", theme !== "light");
    void init();
  }, [init]);

  // realtime wiring once logged in
  useEffect(() => {
    if (!me) return;

    // Shared by both ways a session can die while the app is open: the
    // server pushing a "force:logout" WS event, or any ordinary API call
    // simply coming back 401 (the session expired by age, or died some
    // other way the server never got to push over the socket). Guarded so
    // a burst of 401s (several in-flight requests failing at once) can't
    // fire this more than once before the reload actually happens.
    let signedOut = false;
    const forceSignOut = (reason: string) => {
      if (signedOut) return;
      signedOut = true;
      useCalls.getState().hangup();
      setMe(null);
      ws.disconnect();
      clearLocalUserData();
      sessionStorage.setItem("vc.logoutReason", reason);
      location.reload();
    };
    const onForceLogout = (data: { reason?: string }) => {
      forceSignOut(
        data?.reason === "signed_in_elsewhere"
          ? "You were signed out because your account was signed in on another device."
          : "You were signed out because your account was updated. Please sign in again.",
      );
    };
    const offForce = ws.on("force:logout", onForceLogout);
    setUnauthorizedHandler(() => forceSignOut("Your session expired. Please sign in again."));

    useDirectory.getState().fetchUsers();
    useChats.getState().registerWs();
    useChats.getState().fetchGroups();
    void useChats.getState().fetchRecent();
    useCalls.getState().registerWs();
    useCalls.getState().fetchHistory();
    void ensureDeviceRegistered(me.id); // publishes this device's E2E public key for this account
    ws.connect();

    // re-sync after reconnect
    const offOpen = ws.on("ws:open", () => {
      useDirectory.getState().fetchUsers();
      useChats.getState().fetchGroups();
      void useChats.getState().fetchRecent();
      useCalls.getState().fetchHistory();
    });
    const offPresence = ws.on("presence:update", (d) => {
      useDirectory.getState().applyPresence(d.user_id, d.status);
    });
    const offSync = ws.on("presence:sync", (d) => {
      useDirectory.getState().presenceSync(d.users ?? []);
    });

    return () => {
      offForce();
      setUnauthorizedHandler(null);
      offOpen();
      offPresence();
      offSync();
      // A logout unmounts the call overlay without giving the WebSocket
      // close handler a chance to release local media tracks.
      if (!useAuth.getState().me) useCalls.getState().hangup();
      ws.disconnect();
    };
  }, [me?.id, setMe]);

  const resetToken = resetPasswordToken();
  if (resetToken) return <ResetPassword token={resetToken} />;

  if (!initialized) {
    return (
      <div className="h-full flex items-center justify-center">
        <Loader2 className="h-6 w-6 animate-spin text-indigo-500" />
      </div>
    );
  }

  const isInsecureContext =
    typeof window !== "undefined" &&
    !window.isSecureContext &&
    location.hostname !== "localhost" &&
    location.hostname !== "127.0.0.1";

  if (!me) return <Login />;

  return (
    <>
      {isInsecureContext && (
        <div className="bg-amber-500/15 border-b border-amber-500/30 text-amber-800 dark:text-amber-200 text-xs px-4 py-2 flex items-center justify-between z-50 shrink-0">
          <span>
            <strong>LAN HTTP Notice:</strong> Browsers require HTTPS or localhost for microphone, camera, and E2E encryption. Connect via HTTPS or install the server certificate.
          </span>
          <a
            href="/cert.pem"
            download="visioncall-ca.crt"
            className="underline font-semibold ml-3 px-2 py-0.5 rounded bg-amber-200/50 dark:bg-amber-900/50 hover:bg-amber-300/50 text-amber-900 dark:text-amber-100"
          >
            Download CA Certificate
          </a>
        </div>
      )}
      <Shell />
      <CallOverlay />
      <IncomingCallModal />
      <RoomJoinRequests />
      <ToastHost />
    </>
  );
}

