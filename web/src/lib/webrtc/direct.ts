import { ws } from "../ws";
import { acquireLocalMedia, getPreferredDevice, setPreferredDevice, stopStream } from "../media";
import type { UserBrief } from "../types";
import { startNetworkMonitor, adaptSenderToQuality, type NetQuality } from "./netmonitor";

const CAM_MAX_BITRATE = 1_500_000;
const SCREEN_MAX_BITRATE = 2_500_000;

/**
 * DirectCall is a 1:1 peer-to-peer RTCPeerConnection. The caller is the only
 * side that creates offers (initial + ICE restarts); the callee only answers,
 * which eliminates offer glare. Signaling payloads are relayed verbatim by the
 * server via webrtc:relay.
 */
export class DirectCall {
  pc: RTCPeerConnection | null = null;
  localStream: MediaStream | null = null;
  remoteStream = new MediaStream();
  callId = "";
  peerId = 0;
  isCaller = false;
  private camSender: RTCRtpSender | null = null;
  private screenSender: RTCRtpSender | null = null;
  private savedCamTrack: MediaStreamTrack | null = null;
  private screenStream: MediaStream | null = null;
  private pendingCandidates: RTCIceCandidateInit[] = [];
  private pendingLocalCandidates: RTCIceCandidateInit[] = [];
  private remoteDescSet = false;
  private iceConfig: RTCConfiguration = { iceServers: [] };
  private canOffer = false;
  private makingOffer = false;
  private closed = false;
  private restartingIce = false;
  private lifecycle = 0;
  private onRemoteStream?: () => void;
  private onState?: (state: RTCPeerConnectionState) => void;
  private onSharingChange?: (sharing: boolean, stream: MediaStream | null) => void;
  private onNetworkQuality?: (q: NetQuality) => void;
  private netStop?: () => void;

  constructor(opts: {
    onRemoteStream?: () => void;
    onState?: (state: RTCPeerConnectionState) => void;
    onSharingChange?: (sharing: boolean, stream: MediaStream | null) => void;
    onNetworkQuality?: (q: NetQuality) => void;
  }) {
    this.onRemoteStream = opts.onRemoteStream;
    this.onState = opts.onState;
    this.onSharingChange = opts.onSharingChange;
    this.onNetworkQuality = opts.onNetworkQuality;
  }

  async setupIce() {
    try {
      const { iceServers } = await (await fetch("/api/ice", { credentials: "include" })).json();
      this.iceConfig = { iceServers: iceServers ?? [] };
    } catch {
      /* host candidates are enough on a LAN */
    }
  }

  private async createPC() {
    await this.setupIce();
    const pc = new RTCPeerConnection(this.iceConfig);
    const lifecycle = this.lifecycle;
    this.pc = pc;
    this.netStop?.();
    this.netStop = startNetworkMonitor(
      () => [this.pc],
      (q) => {
        this.onNetworkQuality?.(q);
        adaptSenderToQuality(this.camSender, q, CAM_MAX_BITRATE);
        adaptSenderToQuality(this.screenSender, q, SCREEN_MAX_BITRATE);
      },
    );
    pc.onicecandidate = (e) => {
      if (e.candidate && lifecycle === this.lifecycle && this.pc === pc && !this.closed) {
        this.relay({ kind: "candidate", candidate: e.candidate.toJSON() });
      }
    };
    pc.ontrack = (e) => {
      if (lifecycle !== this.lifecycle || this.pc !== pc || this.closed) return;
      const addTrack = () => {
        if (lifecycle !== this.lifecycle || this.pc !== pc || this.closed) return;
        if (!this.remoteStream.getTracks().includes(e.track)) {
          this.remoteStream.addTrack(e.track);
          this.onRemoteStream?.();
        }
      };
      addTrack();
      e.track.onunmute = addTrack;
      e.track.onended = () => {
        if (lifecycle !== this.lifecycle || this.pc !== pc || this.closed) return;
        if (this.remoteStream.getTracks().includes(e.track)) {
          this.remoteStream.removeTrack(e.track);
          this.onRemoteStream?.();
        }
      };
    };
    pc.onconnectionstatechange = () => {
      if (lifecycle !== this.lifecycle || this.pc !== pc || this.closed) return;
      this.onState?.(pc.connectionState);
      if (pc.connectionState === "failed" && this.canOffer && !this.restartingIce) {
        // brief drop on LAN/VPN: restart ICE without tearing the call down
        this.restartingIce = true;
        pc.restartIce();
        void this.makeOffer().finally(() => {
          this.restartingIce = false;
        });
      }
    };
    return pc;
  }

  private relay(data: unknown) {
    if (this.isCaller && !this.canOffer && (data as { kind?: string }).kind === "candidate") {
      this.pendingLocalCandidates.push((data as { candidate: RTCIceCandidateInit }).candidate);
      return;
    }
    ws.send("webrtc:relay", { to: this.peerId, call_id: this.callId, data });
  }

  /** Enable mid-call renegotiation and flush caller ICE collected pre-accept. */
  enableRenegotiation() {
    this.canOffer = true;
    for (const candidate of this.pendingLocalCandidates) {
      ws.send("webrtc:relay", {
        to: this.peerId,
        call_id: this.callId,
        data: { kind: "candidate", candidate },
      });
    }
    this.pendingLocalCandidates = [];
  }

  /** Caller: acquire media and prepare tracks (offer happens on accept). */
  async startAsCaller(peer: UserBrief, callId: string, video: boolean) {
    const lifecycle = ++this.lifecycle;
    this.isCaller = true;
    this.closed = false;
    this.peerId = peer.id;
    this.callId = callId;
    const stream = await acquireLocalMedia(video);
    if (lifecycle !== this.lifecycle || this.closed) {
      stopStream(stream);
      throw new Error("call cancelled");
    }
    this.localStream = stream;
    const pc = await this.createPC();
    if (lifecycle !== this.lifecycle || this.closed) {
      pc.close();
      stopStream(stream);
      this.localStream = null;
      throw new Error("call cancelled");
    }
    this.attachLocal(pc, video);
  }

  /** Callee: acquire media and prepare to answer. */
  async startAsCallee(peer: UserBrief, callId: string, video: boolean) {
    const lifecycle = ++this.lifecycle;
    this.isCaller = false;
    this.closed = false;
    this.peerId = peer.id;
    this.callId = callId;
    const stream = await acquireLocalMedia(video);
    if (lifecycle !== this.lifecycle || this.closed) {
      stopStream(stream);
      throw new Error("call cancelled");
    }
    this.localStream = stream;
    const pc = await this.createPC();
    if (lifecycle !== this.lifecycle || this.closed) {
      pc.close();
      stopStream(stream);
      this.localStream = null;
      throw new Error("call cancelled");
    }
    this.attachLocal(pc, video);
  }

  private attachLocal(pc: RTCPeerConnection, video: boolean) {
    const audio = this.localStream!.getAudioTracks()[0];
    if (audio) pc.addTrack(audio, this.localStream!);
    const cam = this.localStream!.getVideoTracks()[0];
    if (video && cam) {
      this.camSender = pc.addTrack(cam, this.localStream!);
      this.savedCamTrack = cam;
    }
  }

  async makeOffer() {
    const pc = this.pc;
    const lifecycle = this.lifecycle;
    if (!pc || !this.canOffer || this.makingOffer || pc.signalingState !== "stable") return;
    this.makingOffer = true;
    try {
      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      if (pc.localDescription && lifecycle === this.lifecycle && this.pc === pc && !this.closed) {
        this.relay({ kind: "offer", sdp: pc.localDescription });
      }
    } catch {
      // The call may have ended while the browser was creating the offer.
    } finally {
      this.makingOffer = false;
    }
  }

  /** Handle a relayed signaling payload from the peer. */
  async onRelay(data: any) {
    const pc = this.pc;
    if (!pc || this.closed) return;
    try {
      if (data.kind === "offer") {
        if (pc.signalingState === "have-local-offer") {
          await pc.setLocalDescription({ type: "rollback" });
        }
        await pc.setRemoteDescription(data.sdp);
        this.remoteDescSet = true;
        const answer = await pc.createAnswer();
        await pc.setLocalDescription(answer);
        this.relay({ kind: "answer", sdp: pc.localDescription });
        for (const c of this.pendingCandidates) pc.addIceCandidate(c).catch(() => {});
        this.pendingCandidates = [];
      } else if (data.kind === "answer") {
        if (pc.signalingState === "have-local-offer") {
          await pc.setRemoteDescription(data.sdp);
          this.remoteDescSet = true;
          for (const c of this.pendingCandidates) pc.addIceCandidate(c).catch(() => {});
          this.pendingCandidates = [];
        }
      } else if (data.kind === "candidate" && data.candidate) {
        if (this.remoteDescSet || pc.remoteDescription) {
          pc.addIceCandidate(data.candidate).catch(() => {});
        } else {
          // Keep candidates that arrive before their offer.
          this.pendingCandidates.push(data.candidate);
        }
      }
    } catch {
      // Ignore stale signaling frames during reconnect or hangup.
    }
  }

  setMic(on: boolean) {
    this.localStream?.getAudioTracks().forEach((t) => (t.enabled = on));
  }

  private localVideoConstraints(): MediaTrackConstraints {
    const deviceId = getPreferredDevice("cam");
    return deviceId
      ? { deviceId: { exact: deviceId }, width: { ideal: 1280 }, height: { ideal: 720 } }
      : { width: { ideal: 1280 }, height: { ideal: 720 } };
  }

  private async addCameraTrack() {
    if (!this.pc || !this.localStream) throw new Error("call is not active");
    if (this.localStream.getVideoTracks().length > 0) return;
    const temp = await navigator.mediaDevices.getUserMedia({ video: this.localVideoConstraints() });
    if (!this.pc || !this.localStream || this.closed) {
      stopStream(temp);
      throw new Error("call is not active");
    }
    const track = temp.getVideoTracks()[0];
    if (!track) {
      stopStream(temp);
      throw new Error("camera unavailable");
    }
    this.localStream.addTrack(track);
    if (this.screenStream) {
      // Keep the camera ready for restoration without creating a second
      // video sender while the screen is the visible p2p video track.
      this.savedCamTrack = track;
      return;
    }
    try {
      this.camSender = this.pc.addTrack(track, this.localStream);
    } catch (err) {
      this.localStream.removeTrack(track);
      track.stop();
      throw err;
    }
    this.savedCamTrack = track;
  }

  async setCam(on: boolean) {
    if (on && !this.localStream?.getVideoTracks().length) {
      await this.addCameraTrack();
      await this.makeOffer();
      return;
    }
    this.localStream?.getVideoTracks().forEach((t) => (t.enabled = on));
  }

  get hasVideoTrack() {
    return !!this.localStream?.getVideoTracks().length;
  }

  async startScreenShare() {
    const pc = this.pc;
    const lifecycle = this.lifecycle;
    if (!pc || this.closed) throw new Error("call is not active");
    if (this.screenStream) return;
    const stream = await navigator.mediaDevices.getDisplayMedia({ video: true });
    if (lifecycle !== this.lifecycle || this.pc !== pc || this.closed) {
      stopStream(stream);
      throw new Error("call is not active");
    }
    this.screenStream = stream;
    const screenTrack = stream.getVideoTracks()[0];
    if (!screenTrack) {
      stopStream(stream);
      this.screenStream = null;
      throw new Error("screen unavailable");
    }
    try {
      if (this.camSender && this.savedCamTrack) {
        await this.camSender.replaceTrack(screenTrack);
      } else {
        this.screenSender = pc.addTrack(screenTrack, this.screenStream);
      }
    } catch (err) {
      stopStream(stream);
      this.screenStream = null;
      throw err;
    }
    if (lifecycle !== this.lifecycle || this.pc !== pc || this.closed) {
      await this.stopScreenShare();
      throw new Error("call is not active");
    }
    screenTrack.onended = () => void this.stopScreenShare();
    this.onSharingChange?.(true, this.screenStream);
    await this.makeOffer();
  }

  async stopScreenShare() {
    if (this.screenStream) {
      stopStream(this.screenStream);
      this.screenStream = null;
    }
    if (!this.pc) {
      this.onSharingChange?.(false, null);
      return;
    }
    if (this.screenSender) {
      try {
        this.pc.removeTrack(this.screenSender);
      } catch {
        /* peer connection may already be closing */
      }
      this.screenSender = null;
      if (this.savedCamTrack && this.camSender) {
        try {
          await this.camSender.replaceTrack(this.savedCamTrack);
        } catch {
          /* peer connection may already be closing */
        }
      } else if (this.savedCamTrack && this.localStream) {
        try {
          this.camSender = this.pc.addTrack(this.savedCamTrack, this.localStream);
        } catch {
          /* peer connection may already be closing */
        }
      }
    } else if (this.camSender && this.savedCamTrack) {
      try {
        await this.camSender.replaceTrack(this.savedCamTrack);
      } catch {
        /* peer connection may already be closing */
      }
    } else if (this.camSender) {
      try {
        this.pc.removeTrack(this.camSender);
      } catch {
        /* peer connection may already be closing */
      }
      this.camSender = null;
    }
    this.onSharingChange?.(false, null);
    await this.makeOffer();
  }

  /** Switch mic/camera mid-call; returns whether a device was actually swapped. */
  async switchDevice(kind: "mic" | "cam", deviceId: string) {
    if (!this.pc || !this.localStream) return;
    if (kind === "mic") {
      const temp = await navigator.mediaDevices.getUserMedia({
        audio: { deviceId: { exact: deviceId } },
      });
      if (!this.pc || !this.localStream || this.closed) {
        stopStream(temp);
        throw new Error("call is not active");
      }
      const newTrack = temp.getAudioTracks()[0];
      if (!newTrack) {
        stopStream(temp);
        return;
      }
      const old = this.localStream.getAudioTracks()[0];
      newTrack.enabled = old?.enabled ?? false;
      const sender = this.pc.getSenders().find((s) => s.track?.kind === "audio");
      let added = false;
      try {
        if (sender) await sender.replaceTrack(newTrack);
        else {
          this.pc.addTrack(newTrack, this.localStream);
          added = true;
        }
      } catch (err) {
        newTrack.stop();
        throw err;
      }
      if (old) {
        old.stop();
        this.localStream.removeTrack(old);
      }
      this.localStream.addTrack(newTrack);
      setPreferredDevice("mic", deviceId);
      if (added) await this.makeOffer();
    } else {
      const temp = await navigator.mediaDevices.getUserMedia({
        video: { deviceId: { exact: deviceId } },
      });
      if (!this.pc || !this.localStream || this.closed) {
        stopStream(temp);
        throw new Error("call is not active");
      }
      const newTrack = temp.getVideoTracks()[0];
      if (!newTrack) {
        stopStream(temp);
        return;
      }
      const old = this.localStream.getVideoTracks()[0];
      newTrack.enabled = old?.enabled ?? true;
      if (this.screenStream) {
        // When the screen occupies the camera sender, keep the replacement
        // camera for restoration. If screen has its own sender, replace the
        // separate camera sender when one exists; otherwise publish it after
        // screen sharing stops.
        if (this.screenSender && this.camSender) {
          try {
            await this.camSender.replaceTrack(newTrack);
          } catch (err) {
            newTrack.stop();
            throw err;
          }
        }
        this.savedCamTrack = newTrack;
      } else if (this.camSender) {
        try {
          await this.camSender.replaceTrack(newTrack);
        } catch (err) {
          newTrack.stop();
          throw err;
        }
        this.savedCamTrack = newTrack;
      } else {
        try {
          this.camSender = this.pc.addTrack(newTrack, this.localStream);
        } catch (err) {
          newTrack.stop();
          throw err;
        }
        this.savedCamTrack = newTrack;
        await this.makeOffer();
      }
      if (old) {
        old.stop();
        this.localStream.removeTrack(old);
      }
      this.localStream.addTrack(newTrack);
      setPreferredDevice("cam", deviceId);
    }
  }

  close() {
    this.lifecycle++;
    this.closed = true;
    this.canOffer = false;
    this.makingOffer = false;
    this.restartingIce = false;
    this.netStop?.();
    this.netStop = undefined;
    this.pendingCandidates = [];
    this.pendingLocalCandidates = [];
    stopStream(this.screenStream);
    this.screenStream = null;
    stopStream(this.localStream);
    this.pc?.close();
    this.pc = null;
    this.camSender = null;
    this.screenSender = null;
    this.savedCamTrack = null;
    this.remoteStream.getTracks().forEach((t) => t.stop());
    this.remoteStream = new MediaStream();
  }
}
