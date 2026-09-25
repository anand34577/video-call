/** Shared network-quality polling + adaptive-bitrate helpers for direct.ts and sfu.ts. */

export type NetQuality = "good" | "fair" | "poor";

interface Sample {
  packetsLost: number;
  packetsReceived: number;
  rtt: number;
}

async function sampleInbound(pc: RTCPeerConnection | null): Promise<Sample | null> {
  if (!pc || pc.connectionState === "closed") return null;
  try {
    const report = await pc.getStats();
    let packetsLost = 0;
    let packetsReceived = 0;
    let rtt = 0;
    report.forEach((r: any) => {
      if (r.type === "inbound-rtp" && !r.isRemote) {
        packetsLost += r.packetsLost ?? 0;
        packetsReceived += r.packetsReceived ?? 0;
      }
      if (r.type === "candidate-pair" && r.state === "succeeded" && typeof r.currentRoundTripTime === "number") {
        rtt = Math.max(rtt, r.currentRoundTripTime);
      }
    });
    return { packetsLost, packetsReceived, rtt };
  } catch {
    return null;
  }
}

function scoreQuality(s: Sample | null): NetQuality {
  if (!s) return "good";
  const total = s.packetsLost + s.packetsReceived;
  const lossRatio = total > 0 ? s.packetsLost / total : 0;
  if (lossRatio > 0.08 || s.rtt > 0.4) return "poor";
  if (lossRatio > 0.03 || s.rtt > 0.2) return "fair";
  return "good";
}

// Polls whichever PeerConnections getPCs() currently returns (a live
// getter, not a snapshot, so it keeps working across a rejoin that swaps in
// fresh PCs) and reports the worst-of quality every intervalMs. Returns a
// stop function.
export function startNetworkMonitor(
  getPCs: () => (RTCPeerConnection | null)[],
  onQuality: (q: NetQuality) => void,
  intervalMs = 3000,
): () => void {
  let stopped = false;
  const tick = async () => {
    if (stopped) return;
    const samples = await Promise.all(getPCs().map(sampleInbound));
    if (stopped) return;
    const qualities = samples.map(scoreQuality);
    const worst: NetQuality = qualities.includes("poor") ? "poor" : qualities.includes("fair") ? "fair" : "good";
    onQuality(worst);
  };
  void tick();
  const id = setInterval(() => void tick(), intervalMs);
  return () => {
    stopped = true;
    clearInterval(id);
  };
}

// Caps outgoing video bitrate/resolution when the network is struggling and
// restores the original encoding once it recovers, so a call stays usable
// (lower-res but live) instead of stalling on a slow link.
// Note: one blunt maxBitrate/scale step per quality tier, not real
// simulcast/SVC — good enough to keep a call working on a bad connection;
// upgrade to simulcast only if per-viewer adaptive quality is ever needed.
export function adaptSenderToQuality(sender: RTCRtpSender | null | undefined, quality: NetQuality, originalMaxBitrate: number) {
  if (!sender?.track || sender.track.kind !== "video") return;
  const params = sender.getParameters();
  if (!params.encodings?.length) return;
  const enc = params.encodings[0];
  if (quality === "poor") {
    enc.maxBitrate = 250_000;
    enc.scaleResolutionDownBy = 2;
  } else if (quality === "fair") {
    enc.maxBitrate = 600_000;
    enc.scaleResolutionDownBy = 1.5;
  } else {
    enc.maxBitrate = originalMaxBitrate;
    enc.scaleResolutionDownBy = 1;
  }
  void sender.setParameters(params).catch(() => {});
}
