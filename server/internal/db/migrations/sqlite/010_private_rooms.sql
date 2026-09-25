-- Standalone private call rooms: not tied to an existing chat group. Joined
-- by room code, optionally gated by a passcode, a specific invite list,
-- and/or host approval (see internal/signaling/private_rooms.go for the
-- join-request/admit/deny flow).
CREATE TABLE private_rooms (
  id               TEXT PRIMARY KEY, -- short shareable room code
  name             TEXT NOT NULL,
  owner_id         INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  passcode_hash    TEXT,
  require_approval INTEGER NOT NULL DEFAULT 0,
  created_at       TEXT NOT NULL
);

-- Presence of any row here switches a room from "anyone with the link/code"
-- to "only these users" — see IsPrivateRoomAllowed.
CREATE TABLE private_room_invites (
  room_id TEXT NOT NULL REFERENCES private_rooms(id) ON DELETE CASCADE,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  PRIMARY KEY (room_id, user_id)
);

CREATE INDEX idx_private_rooms_owner ON private_rooms(owner_id);
