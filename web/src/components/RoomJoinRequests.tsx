import { Check, X, DoorOpen } from "lucide-react";
import { useCalls } from "../store/calls";
import { Avatar } from "./ui";

// Always-mounted (like IncomingCallModal) so a "someone wants in" prompt for
// a private room you own can surface even if you're not currently in that
// call — e.g. you haven't started it yet, or you're elsewhere in the app.
export default function RoomJoinRequests() {
  const { roomJoinRequests, admitJoinRequest, denyJoinRequest } = useCalls();
  if (roomJoinRequests.length === 0) return null;

  return (
    <div className="fixed top-4 inset-x-4 sm:inset-x-auto sm:right-4 sm:left-auto z-[70] flex flex-col gap-2 w-auto sm:w-full sm:max-w-xs">
      {roomJoinRequests.map((req) => (
        <div
          key={`${req.roomId}-${req.from.id}`}
          className="rounded-xl bg-zinc-900 border border-zinc-800 shadow-2xl shadow-black/60 p-3 flex items-center gap-3 animate-sheet-in sm:animate-modal-in"
          role="alertdialog"
          aria-label={`${req.from.display_name} wants to join your room`}
        >
          <Avatar name={req.from.display_name} id={req.from.id} fileId={req.from.avatar_file_id} size="sm" />
          <div className="min-w-0 flex-1">
            <p className="text-sm font-semibold truncate flex items-center gap-1.5">
              <DoorOpen className="h-3.5 w-3.5 text-blue-400 shrink-0" />
              {req.from.display_name}
            </p>
            <p className="text-xs text-zinc-400 truncate">wants to join your room</p>
          </div>
          <div className="flex items-center gap-1.5 shrink-0">
            <button
              onClick={() => denyJoinRequest(req.roomId, req.from.id)}
              className="h-8 w-8 rounded-lg bg-rose-950/50 text-rose-400 flex items-center justify-center hover:bg-rose-950 transition"
              title="Deny"
              aria-label={`Deny ${req.from.display_name}`}
            >
              <X className="h-4 w-4" />
            </button>
            <button
              onClick={() => admitJoinRequest(req.roomId, req.from.id)}
              className="h-8 w-8 rounded-lg bg-emerald-950/50 text-emerald-400 flex items-center justify-center hover:bg-emerald-950 transition"
              title="Admit"
              aria-label={`Admit ${req.from.display_name}`}
            >
              <Check className="h-4 w-4" />
            </button>
          </div>
        </div>
      ))}
    </div>
  );
}
