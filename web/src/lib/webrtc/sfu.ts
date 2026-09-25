import { ws } from "../ws";
import { api } from "../api";
import { acquireLocalMedia, getPreferredDevice, setPreferredDevice, stopStream } from "../media";
import type { IceServer } from "../types";
import { startNetworkMonitor, adaptSenderToQuality, type NetQuality } from "./netmonitor";

const CAM_MAX_BITRATE = 1_500_000;
const SCREEN_MAX_BITRATE = 2_500_000;

export interface RemoteTracks {
  mic?: MediaStreamTrack;
  cam?: MediaStreamTrack;
  screen?: MediaStreamTrack;
}

/**
 * RoomClient is the SFU-side client: a publisher PC (client→SFU, offers driven
 * by onnegotiationneeded) and a subscriber PC (SFU→client, offers arrive from
 * the server). Forwarded tracks carry id mic/cam/screen and stream id = the
 * publisher's user id.
 */
export class RoomClient {
  roomId = "";
  pub: RTCPeerConnection | null = null;
  sub: RTCPeerConnection | null = null;
  localStream: MediaStream | null = null;
  screenStream: MediaStream | null = null;
  remoteTracks: Record<number, RemoteTracks> = {};

  private iceConfig: RTCConfiguration = { iceServers: [] };
  private micSender: RTCRtpSender | null = null;
  private camSender: RTCRtpSender | null = null;
  private screenSender: RTCRtpSender | null = null;
  private micTrack: MediaStreamTrack | null = null;
  private camTrack: MediaStreamTrack | null = null;
  private screenTrack: MediaStreamTrack | null = null;
  private makingOffer = false;
  private pendingPubCandidates: RTCIceCandidateInit[] = [];
  private pendingSubCandidates: RTCIceCandidateInit[] = [];
  private pubHasRemote = false;
  private subHasRemote = false;
  private lifecycle = 0;
  private resumePending = false;
  private rejoinPending = false;
  private onTracks?: () => void;
  private onSharingChange?: (sharing: boolean, stream: MediaStream | null) => void;
  private onNetworkQuality?: (q: NetQuality) => void;
  private onConnectionState?: (state: "connected" | "reconnecting") => void;
  private netStop?: () => void;

  constructor(opts: {
    onTracks?: () => void;
    onSharingChange?: (sharing: boolean, stream: MediaStream | null) => void;
    onNetworkQuality?: (q: NetQuality) => void;
    onConnectionState?: (state: "connected" | "reconnecting") => void;
  } = {}) {
    this.onTracks = opts.onTracks;
    this.onSharingChange = opts.onSharingChange;
    this.onNetworkQuality = opts.onNetworkQuality;
    this.onConnectionState = opts.onConnectionState;
  }

  // Worst-of the publisher/subscriber connection state, collapsed to the two
  // states the UI cares about: still fine, or needs a "Reconnecting…" banner
  // while recovery (below) runs.
  private reportConnectionState(pub: RTCPeerConnection, sub: RTCPeerConnection) {
    const states = [pub.connectionState, sub.connectionState];
    const ok = states.every((s) => s === "connected");
    this.onConnectionState?.(ok ? "connected" : "reconnecting");
  }

  private async loadIce() {
    try {
      const { iceServers } = await api.iceServers();
      this.iceConfig = { iceServers: (iceServers ?? []) as IceServer[] };
    } catch {
      /* LAN host candidates suffice */
    }
  }

  /** Join a conference room. Acquires media, wires PCs, signals room:join. */
  async join(roomId: string, video: boolean, resume = false, reuseMedia = false, passcode?: string) {
    const lifecycle = ++this.lifecycle;
    this.roomId = roomId;
    await this.loadIce();

    if (lifecycle !== this.lifecycle) throw new Error("join cancelled");

    const pub = new RTCPeerConnection(this.iceConfig);
    const sub = new RTCPeerConnection(this.iceConfig);
    this.pub = pub;
    this.sub = sub;

    // Restarted on every join()/rejoin so it always polls the live PCs.
    this.netStop?.();
    this.netStop = startNetworkMonitor(
      () => [this.pub, this.sub],
      (q) => {
        this.onNetworkQuality?.(q);
        adaptSenderToQuality(this.camSender, q, CAM_MAX_BITRATE);
        adaptSenderToQuality(this.screenSender, q, SCREEN_MAX_BITRATE);
      },
    );

    pub.onicecandidate = (e) => {
      if (e.candidate && lifecycle === this.lifecycle && this.pub === pub) ws.send("sfu:pub-ice", e.candidate.toJSON());
    };
    pub.onnegotiationneeded = () => {
      if (lifecycle === this.lifecycle && this.pub === pub) void this.publishOffer(pub, lifecycle);
    };
    // Recovery here previously only ran when the *signaling* WebSocket
    // reconnected (resume(), above) — a publisher or subscriber connection
    // can independently go "failed" (a transient UDP block, a Wi-Fi roam)
    // while the WebSocket stays up, and nothing used to notice. Both roles
    // share the same recovery path (full rejoin with the existing local
    // media) since, unlike a 1:1 call, the subscriber side's offers come
    // from the server — the client can't unilaterally ICE-restart just its
    // own half of that connection.
    pub.onconnectionstatechange = () => {
      if (lifecycle !== this.lifecycle || this.pub !== pub) return;
      this.reportConnectionState(pub, sub);
      if (pub.connectionState === "failed") {
        void this.rejoinWithExistingMedia().catch(() => {
          /* retry on the next signaling reconnect */
        });
      }
    };
    sub.onicecandidate = (e) => {
      if (e.candidate && lifecycle === this.lifecycle && this.sub === sub) ws.send("sfu:sub-ice", e.candidate.toJSON());
    };
    sub.onconnectionstatechange = () => {
      if (lifecycle !== this.lifecycle || this.sub !== sub) return;
      this.reportConnectionState(pub, sub);
      if (sub.connectionState === "failed") {
        void this.rejoinWithExistingMedia().catch(() => {
          /* retry on the next signaling reconnect */
        });
      }
    };
    sub.ontrack = (e) => {
      if (lifecycle !== this.lifecycle || this.sub !== sub) return;
      const uid = Number(e.streams[0]?.id);
      const trackID = e.track.id;
      if (!uid || trackID !== "mic" && trackID !== "cam" && trackID !== "screen") return;
      this.remoteTracks[uid] = { ...this.remoteTracks[uid], [trackID]: e.track };
      e.track.onunmute = () => {
        if (lifecycle === this.lifecycle && this.sub === sub) this.onTracks?.();
      };
      e.track.onended = () => {
        if (lifecycle !== this.lifecycle || this.sub !== sub) return;
        const current = this.remoteTracks[uid]?.[trackID];
        if (current === e.track) {
          const next = { ...this.remoteTracks[uid] };
          delete next[trackID];
          if (Object.keys(next).length > 0) this.remoteTracks[uid] = next;
          else delete this.remoteTracks[uid];
          this.onTracks?.();
        }
      };
      this.onTracks?.();
    };

    if (!resume && (!reuseMedia || !this.localStream)) {
      const stream = await acquireLocalMedia(video);
      if (lifecycle !== this.lifecycle || this.pub !== pub) {
        pub.close();
        sub.close();
        stopStream(stream);
        throw new Error("join cancelled");
      }
      this.localStream = stream;
      this.micTrack = stream.getAudioTracks()[0] ?? null;
      this.camTrack = video ? stream.getVideoTracks()[0] ?? null : null;
      if (this.micTrack) this.micSender = pub.addTrack(this.micTrack, stream);
      if (this.camTrack) this.camSender = pub.addTrack(this.camTrack, stream);
    } else if (!resume && reuseMedia && this.localStream) {
      this.micTrack = this.localStream.getAudioTracks()[0] ?? null;
      this.camTrack = this.localStream.getVideoTracks()[0] ?? null;
      if (this.micTrack) this.micSender = pub.addTrack(this.micTrack, this.localStream);
      if (this.camTrack) this.camSender = pub.addTrack(this.camTrack, this.localStream);
    }
    if (lifecycle !== this.lifecycle || this.pub !== pub) {
      pub.close();
      sub.close();
      throw new Error("join cancelled");
    }
    if (!ws.send("room:join", { room_id: roomId, video, resume, passcode })) {
      pub.close();
      sub.close();
      if (this.lifecycle === lifecycle && this.pub === pub) this.leave(false);
      throw new Error("realtime connection unavailable");
    }
    if (lifecycle !== this.lifecycle || this.pub !== pub) {
      pub.close();
      sub.close();
      throw new Error("join cancelled");
    }
    if (!resume && reuseMedia && this.screenStream && this.screenTrack) {
      // The server must see the state flag before it receives the new screen
      // track, otherwise it cannot distinguish it from the camera track.
      this.pushTrackState({ screen: true });
      this.screenSender = pub.addTrack(this.screenTrack, this.screenStream);
    }
    if (!resume && reuseMedia) this.pushTrackState({ screen: this.sharing });
    if (!resume) void this.publishOffer(pub, lifecycle);
  }

  /**
   * Re-sends "room:join" after a host admits a pending private-room
   * request. The first join() call already built the PCs, acquired media,
   * and sent an offer — but the server dropped that offer on the floor
   * since the participant didn't exist yet (still pending approval). So
   * this just resends the join signal (now pre-approved, passes the gate)
   * and re-sends the current offer so the server actually sees it.
   */
  resendJoin() {
    if (!this.roomId || !this.pub || !this.sub) return;
    const lifecycle = this.lifecycle;
    if (!ws.send("room:join", { room_id: this.roomId, video: !!this.camTrack?.enabled, resume: false })) return;
    void this.publishOffer(this.pub, lifecycle);
  }

  /** Re-attach signaling after a brief WS drop (PCs stay alive). */
  resume() {
    if (!this.roomId) return;
    if (!this.pub || !this.sub) {
      void this.rejoinWithExistingMedia().catch(() => {
        /* retry on the next signaling reconnect */
      });
      return;
    }
    if (this.pub?.connectionState === "closed" || this.pub?.connectionState === "failed" || this.sub?.connectionState === "closed" || this.sub?.connectionState === "failed") {
      void this.rejoinWithExistingMedia().catch(() => {
        /* retry on the next signaling reconnect */
      });
      return;
    }
    if (ws.send("room:join", { room_id: this.roomId, video: !!this.camTrack?.enabled, resume: true })) {
      this.resumePending = true;
      // Re-apply transient mute/camera/share state kept only in the browser.
      this.pushTrackState({ screen: this.sharing });
      // A track change may have happened while signaling was down and its
      // negotiationneeded offer was dropped. Re-send the current publisher
      // description after the signaling path is back.
      void this.publishOffer();
    }
  }

  /** Called by the store when the server answers a resume attempt. */
  handleRoomJoined(resumed: boolean) {
    if (!this.resumePending) return;
    this.resumePending = false;
    if (resumed) return;
    void this.rejoinWithExistingMedia().catch(() => {
      // The next WebSocket reconnect can retry the resume path.
    });
  }

  private async rejoinWithExistingMedia() {
    if (this.rejoinPending || !this.roomId || !this.localStream) return;
    this.rejoinPending = true;
    try {
      const roomID = this.roomId;
      const video = this.localStream.getVideoTracks().length > 0;
      this.makingOffer = false;
      this.pub?.close();
      this.sub?.close();
      this.pub = null;
      this.sub = null;
      this.micSender = null;
      this.camSender = null;
      this.screenSender = null;
      this.pendingPubCandidates = [];
      this.pendingSubCandidates = [];
      this.pubHasRemote = false;
      this.subHasRemote = false;
      this.remoteTracks = {};
      this.onTracks?.();
      await this.join(roomID, video, false, true);
    } finally {
      this.rejoinPending = false;
    }
  }

  private async publishOffer(pub = this.pub, lifecycle = this.lifecycle) {
    if (!pub || this.makingOffer) return;
    if (lifecycle !== this.lifecycle || this.pub !== pub) return;
    if (pub.signalingState === "have-local-offer" && pub.localDescription) {
      ws.send("sfu:pub-offer", pub.localDescription.toJSON());
      return;
    }
    if (pub.signalingState !== "stable") return;
    this.makingOffer = true;
    try {
      const offer = await pub.createOffer();
      await pub.setLocalDescription(offer);
      if (lifecycle === this.lifecycle && this.pub === pub && pub.localDescription) {
        ws.send("sfu:pub-offer", pub.localDescription.toJSON());
      }
    } catch {
      // The peer can close while negotiationneeded is still queued.
    } finally {
      this.makingOffer = false;
    }
  }

  async handlePubAnswer(sdp: RTCSessionDescriptionInit) {
    const pub = this.pub;
    const lifecycle = this.lifecycle;
    if (!pub) return;
    try {
      await pub.setRemoteDescription(sdp);
      if (lifecycle !== this.lifecycle || this.pub !== pub) return;
      this.pubHasRemote = true;
      for (const c of this.pendingPubCandidates) pub.addIceCandidate(c).catch(() => {});
      this.pendingPubCandidates = [];
    } catch {
      // Ignore answers for a publisher that was already replaced or closed.
    }
  }

  async handlePubICE(candidate: RTCIceCandidateInit) {
    if (!this.pub) return;
    if (this.pubHasRemote || this.pub.remoteDescription) {
      this.pub.addIceCandidate(candidate).catch(() => {});
    } else {
      this.pendingPubCandidates.push(candidate);
    }
  }

  async handleSubOffer(sdp: RTCSessionDescriptionInit) {
    const sub = this.sub;
    const lifecycle = this.lifecycle;
    if (!sub) return;
    try {
      await sub.setRemoteDescription(sdp);
      if (lifecycle !== this.lifecycle || this.sub !== sub) return;
      this.subHasRemote = true;
      for (const c of this.pendingSubCandidates) sub.addIceCandidate(c).catch(() => {});
      this.pendingSubCandidates = [];
      const answer = await sub.createAnswer();
      await sub.setLocalDescription(answer);
      if (lifecycle === this.lifecycle && this.sub === sub && sub.localDescription) {
        ws.send("sfu:sub-answer", sub.localDescription.toJSON());
      }
    } catch {
      // Ignore stale subscriber offers during leave/reconnect.
    }
  }

  async handleSubICE(candidate: RTCIceCandidateInit) {
    if (!this.sub) return;
    if (this.subHasRemote || this.sub.remoteDescription) {
      this.sub.addIceCandidate(candidate).catch(() => {});
    } else {
      this.pendingSubCandidates.push(candidate);
    }
  }

  // ---- controls ----

  get micOn() {
    return !!this.micTrack?.enabled;
  }

  get camOn() {
    return !!this.camTrack?.enabled;
  }

  get sharing() {
    return !!this.screenStream;
  }

  setMic(on: boolean) {
    if (this.micTrack) this.micTrack.enabled = on;
    this.pushTrackState();
  }

  async setCam(on: boolean) {
    const lifecycle = this.lifecycle;
    const pub = this.pub;
    const local = this.localStream;
    if (!pub || !local) throw new Error("call is not active");
    if (on && !this.camTrack) {
      // Joined audio-only; enabling camera requires a new track + renegotiation.
      const deviceId = getPreferredDevice("cam");
      const temp = await navigator.mediaDevices.getUserMedia({
        video: deviceId
          ? { deviceId: { exact: deviceId }, width: { ideal: 1280 }, height: { ideal: 720 } }
          : { width: { ideal: 1280 }, height: { ideal: 720 } },
      });
      if (lifecycle !== this.lifecycle || this.pub !== pub || this.localStream !== local) {
        stopStream(temp);
        throw new Error("call is not active");
      }
      const track = temp.getVideoTracks()[0];
      if (track) {
        this.camTrack = track;
        local.addTrack(track);
        try {
          this.camSender = pub.addTrack(track, local);
        } catch (err) {
          local.removeTrack(track);
          track.stop();
          this.camTrack = null;
          throw err;
        }
      } else {
        stopStream(temp);
        throw new Error("camera unavailable");
      }
    } else if (this.camTrack) {
      this.camTrack.enabled = on;
    }
    if (lifecycle === this.lifecycle && this.pub === pub) this.pushTrackState();
  }

  async startScreenShare() {
    const lifecycle = this.lifecycle;
    const pub = this.pub;
    if (!pub) throw new Error("call is not active");
    if (this.screenStream) return;
    const stream = await navigator.mediaDevices.getDisplayMedia({ video: true });
    if (lifecycle !== this.lifecycle || this.pub !== pub) {
      stopStream(stream);
      throw new Error("call is not active");
    }
    this.screenStream = stream;
    this.screenTrack = stream.getVideoTracks()[0];
    if (!this.screenTrack) {
      stopStream(stream);
      this.screenStream = null;
      throw new Error("screen unavailable");
    }
    // announce BEFORE publishing so the SFU labels the next video track "screen"
    this.pushTrackState({ screen: true });
    try {
      this.screenSender = pub.addTrack(this.screenTrack, stream);
    } catch (err) {
      stopStream(stream);
      this.screenStream = null;
      this.screenTrack = null;
      this.pushTrackState({ screen: false });
      throw err;
    }
    this.screenTrack.onended = () => void this.stopScreenShare();
    this.onSharingChange?.(true, this.screenStream);
  }

  async stopScreenShare() {
    if (this.screenSender) {
      try {
        this.pub?.removeTrack(this.screenSender);
      } catch {
        /* already gone */
      }
      this.screenSender = null;
    }
    stopStream(this.screenStream);
    this.screenStream = null;
    this.screenTrack = null;
    if (!this.pub) {
      this.onSharingChange?.(false, null);
      return;
    }
    this.pushTrackState({ screen: false });
    this.onSharingChange?.(false, null);
  }

  private pushTrackState(overrides?: { screen?: boolean; video_on?: boolean; muted?: boolean }) {
    ws.send("sfu:track-state", {
      muted: overrides?.muted ?? !this.micOn,
      video_on: overrides?.video_on ?? this.camOn,
      screen: overrides?.screen ?? this.sharing,
    });
  }

  async switchDevice(kind: "mic" | "cam", deviceId: string) {
    const lifecycle = this.lifecycle;
    const pub = this.pub;
    const local = this.localStream;
    if (!pub || !local) throw new Error("call is not active");
    if (kind === "mic") {
      const temp = await navigator.mediaDevices.getUserMedia({ audio: { deviceId: { exact: deviceId } } });
      if (lifecycle !== this.lifecycle || this.pub !== pub || this.localStream !== local) {
        stopStream(temp);
        throw new Error("call is not active");
      }
      const t = temp.getAudioTracks()[0];
      if (!t) {
        stopStream(temp);
        return;
      }
      t.enabled = this.micTrack?.enabled ?? false;
      try {
        if (this.micSender) await this.micSender.replaceTrack(t);
        else this.micSender = pub.addTrack(t, local);
      } catch (err) {
        t.stop();
        throw err;
      }
      if (lifecycle !== this.lifecycle || this.pub !== pub || this.localStream !== local) {
        t.stop();
        throw new Error("call is not active");
      }
      this.micTrack?.stop();
      this.micTrack = t;
      local.getAudioTracks().forEach((old) => {
        old.stop();
        local.removeTrack(old);
      });
      local.addTrack(t);
      setPreferredDevice("mic", deviceId);
    } else {
      const temp = await navigator.mediaDevices.getUserMedia({ video: { deviceId: { exact: deviceId } } });
      if (lifecycle !== this.lifecycle || this.pub !== pub || this.localStream !== local) {
        stopStream(temp);
        throw new Error("call is not active");
      }
      const t = temp.getVideoTracks()[0];
      if (!t) {
        stopStream(temp);
        return;
      }
      t.enabled = this.camTrack?.enabled ?? true;
      let addedTrack = false;
      if (this.screenStream) {
        // Keep the active screen sender and replace the camera sender when it
        // exists; audio-only calls publish a new camera sender separately.
        if (this.camSender) {
          try {
            await this.camSender.replaceTrack(t);
          } catch (err) {
            t.stop();
            throw err;
          }
        } else {
          try {
            this.camSender = pub.addTrack(t, local);
            addedTrack = true;
          } catch (err) {
            t.stop();
            throw err;
          }
        }
      } else if (this.camSender) {
        try {
          await this.camSender.replaceTrack(t);
        } catch (err) {
          t.stop();
          throw err;
        }
      } else {
        try {
          this.camSender = pub.addTrack(t, local);
          addedTrack = true;
        } catch (err) {
          t.stop();
          throw err;
        }
      }
      if (lifecycle !== this.lifecycle || this.pub !== pub || this.localStream !== local) {
        t.stop();
        throw new Error("call is not active");
      }
      this.camTrack?.stop();
      this.camTrack = t;
      local.getVideoTracks().forEach((old) => {
        old.stop();
        local.removeTrack(old);
      });
      local.addTrack(t);
      setPreferredDevice("cam", deviceId);
      if (addedTrack) {
        await this.publishOffer();
      }
      this.pushTrackState({ video_on: t.enabled });
    }
  }

  leave(notifyServer = true) {
    this.lifecycle++;
    this.resumePending = false;
    this.netStop?.();
    this.netStop = undefined;
    if (notifyServer && this.roomId) ws.send("room:leave", {});
    stopStream(this.localStream);
    stopStream(this.screenStream);
    this.pub?.close();
    this.sub?.close();
    this.pub = null;
    this.sub = null;
    this.micSender = this.camSender = this.screenSender = null;
    this.remoteTracks = {};
    this.localStream = null;
    this.screenStream = null;
    this.micTrack = this.camTrack = this.screenTrack = null;
    this.onSharingChange?.(false, null);
    this.roomId = "";
    this.pendingPubCandidates = [];
    this.pendingSubCandidates = [];
    this.pubHasRemote = false;
    this.subHasRemote = false;
  }
}
