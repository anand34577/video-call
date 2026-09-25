import { useRef } from "react";
import { Phone, PhoneOff, Video as VideoIcon, Users } from "lucide-react";
import { useCalls } from "../store/calls";
import { Avatar, useFocusTrap } from "./ui";

export default function IncomingCallModal() {
  const { incoming, roomInvite, acceptIncoming, declineIncoming, acceptRoomInvite, declineRoomInvite, status } = useCalls();
  const primaryAction = useRef<HTMLButtonElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const open = status === "idle" && !!(incoming || roomInvite);

  useFocusTrap(open, () => (incoming ? declineIncoming() : declineRoomInvite()), dialogRef, primaryAction);

  if (status !== "idle") return null;

  if (incoming) {
    return (
      <div className="fixed inset-0 z-[60] bg-black/80 backdrop-blur-md flex items-center justify-center p-4" role="dialog" aria-modal="true" aria-labelledby="incoming-call-title">
        <div ref={dialogRef} tabIndex={-1} className="w-full max-w-sm rounded-xl bg-zinc-900 border border-zinc-700/80 p-8 text-center shadow-2xl shadow-black/70 animate-modal-in outline-none">
          <div className="relative inline-block mb-5">
            <span className="absolute -inset-3 rounded-full bg-blue-500/20 animate-ping" />
            <Avatar name={incoming.from.display_name} id={incoming.from.id} fileId={incoming.from.avatar_file_id} size="xl" />
          </div>
          <h2 id="incoming-call-title" className="text-xl font-bold font-display tracking-tight text-zinc-100">{incoming.from.display_name}</h2>
          <p className="text-sm text-zinc-400 mt-1 mb-1">Incoming {incoming.video ? "video" : "voice"} call</p>
          <p className="text-xs text-zinc-500 mb-7 flex items-center justify-center gap-1.5">
            {incoming.video ? <VideoIcon className="h-3.5 w-3.5 text-blue-400" /> : <Phone className="h-3.5 w-3.5 text-blue-400" />}
            Vision Call
          </p>
          
          {incoming.video ? (
            <div className="flex justify-center items-center gap-3.5">
              <button
                type="button"
                onClick={() => void declineIncoming()}
                className="h-14 w-14 rounded-xl bg-rose-600 hover:bg-rose-500 active:scale-95 text-white flex flex-col items-center justify-center gap-1 transition shadow-lg shadow-rose-600/25 cursor-pointer"
                title="Decline call"
              >
                <PhoneOff className="h-5 w-5" />
                <span className="text-[10px] font-semibold">Decline</span>
              </button>
              <button
                type="button"
                onClick={() => void acceptIncoming(false)}
                className="h-14 w-14 rounded-xl bg-zinc-800 hover:bg-zinc-700 active:scale-95 text-zinc-200 flex flex-col items-center justify-center gap-1 transition border border-zinc-700 cursor-pointer"
                title="Answer with microphone only (camera off)"
              >
                <Phone className="h-5 w-5" />
                <span className="text-[10px] font-semibold">Voice</span>
              </button>
              <button
                type="button"
                onClick={() => void acceptIncoming(true)}
                ref={primaryAction}
                className="h-14 w-14 rounded-xl bg-emerald-700 hover:bg-emerald-600 active:scale-95 text-white flex flex-col items-center justify-center gap-1 transition shadow-lg shadow-emerald-600/25 cursor-pointer"
                title="Answer with video"
              >
                <VideoIcon className="h-5 w-5" />
                <span className="text-[10px] font-semibold">Video</span>
              </button>
            </div>
          ) : (
            <div className="flex justify-center gap-6">
              <button
                type="button"
                onClick={() => void declineIncoming()}
                className="h-14 w-14 rounded-xl bg-rose-600 hover:bg-rose-500 active:scale-95 text-white flex flex-col items-center justify-center gap-1 transition shadow-lg shadow-rose-600/25 cursor-pointer"
                title="Decline"
              >
                <PhoneOff className="h-5 w-5" />
                <span className="text-[10px] font-semibold">Decline</span>
              </button>
              <button
                type="button"
                onClick={() => void acceptIncoming()}
                ref={primaryAction}
                className="h-14 w-14 rounded-xl bg-emerald-700 hover:bg-emerald-600 active:scale-95 text-white flex flex-col items-center justify-center gap-1 transition shadow-lg shadow-emerald-600/25 cursor-pointer"
                title="Accept"
              >
                <Phone className="h-5 w-5" />
                <span className="text-[10px] font-semibold">Accept</span>
              </button>
            </div>
          )}
        </div>
      </div>
    );
  }

  if (roomInvite) {
    return (
      <div className="fixed inset-0 z-[60] bg-black/80 backdrop-blur-md flex items-center justify-center p-4" role="dialog" aria-modal="true" aria-labelledby="room-invite-title">
        <div ref={dialogRef} tabIndex={-1} className="w-full max-w-sm rounded-xl bg-zinc-900 border border-zinc-700/80 p-8 text-center shadow-2xl shadow-black/70 animate-modal-in outline-none">
          <div className="relative inline-flex h-20 w-20 rounded-xl bg-blue-600 items-center justify-center text-white mb-5 shadow-lg shadow-blue-600/25">
            <span className="absolute -inset-2 rounded-xl bg-blue-500/25 animate-ping" />
            <Users className="h-9 w-9 relative z-10" />
          </div>
          <h2 id="room-invite-title" className="text-xl font-bold font-display tracking-tight text-zinc-100">{roomInvite.groupName}</h2>
          <p className="text-sm text-zinc-400 mt-1 mb-7">
            {roomInvite.from.display_name} started a group call
          </p>
          <div className="flex justify-center gap-6">
            <button
              type="button"
              onClick={declineRoomInvite}
              className="h-14 w-14 rounded-xl bg-rose-600 hover:bg-rose-500 active:scale-95 text-white flex flex-col items-center justify-center gap-1 transition shadow-lg shadow-rose-600/25 cursor-pointer"
            >
              <PhoneOff className="h-5 w-5" />
              <span className="text-[10px] font-semibold">Ignore</span>
            </button>
            <button
              type="button"
              onClick={() => void acceptRoomInvite()}
              ref={primaryAction}
              className="h-14 w-14 rounded-xl bg-emerald-700 hover:bg-emerald-600 active:scale-95 text-white flex flex-col items-center justify-center gap-1 transition shadow-lg shadow-emerald-600/25 cursor-pointer"
            >
              <Phone className="h-5 w-5" />
              <span className="text-[10px] font-semibold">Join</span>
            </button>
          </div>
        </div>
      </div>
    );
  }

  return null;
}
