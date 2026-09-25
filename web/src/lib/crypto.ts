// Client-side end-to-end encryption for chat messages. The server (see
// lib/api.ts's device key endpoints) only ever stores and relays public
// keys and ciphertext — it has no key material that could decrypt anything
// here, by construction.
//
// Scheme, per message:
//   1. Generate a random AES-256-GCM "content key" + IV, encrypt the text.
//   2. For every recipient device (every current member's every registered
//      device, plus the sender's own other devices), derive a per-device
//      wrapping key via ECDH(senderPriv, deviceRecipientPub) and use it to
//      wrap the content key with its own IV.
//   3. Ship ciphertext + iv + the array of per-device wrapped keys.
// Decrypting is the mirror: find your own device's entry, re-derive the
// same ECDH shared secret (ECDH is symmetric — recipientPriv+senderPub
// gives the same result), unwrap the content key, decrypt.
//
// This has no ratcheting/forward-secrecy beyond "one fresh AES key per
// message" — a real Signal-style double-ratchet is out of scope here.
import { deviceID } from "./ws";
import { api } from "./api";

const DB_NAME = "vc-e2e-keys";
const STORE = "keypair";
// Keypairs are per account (record "device:<userID>"), not per browser: on a
// shared machine the next person to sign in must not inherit — and publish
// under their own account — the previous user's private key. "device" is
// the old single, browser-wide record, adopted once by its real owner.
const LEGACY_RECORD_KEY = "device";
const recordKey = (userID: number) => `device:${userID}`;

interface StoredKeyPair {
  publicKey: CryptoKey;
  privateKey: CryptoKey;
}

interface WrappedKeyEntry {
  user_id: number;
  device_id: string;
  wrapped_key: string; // base64
  wrap_iv: string; // base64
  sender_pub_jwk: JsonWebKey;
}

function openKeyDB(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = () => req.result.createObjectStore(STORE);
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function idbGet<T>(store: string, key: string): Promise<T | undefined> {
  const db = await openKeyDB();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(store, "readonly").objectStore(store).get(key);
    tx.onsuccess = () => resolve(tx.result);
    tx.onerror = () => reject(tx.error);
  });
}

async function idbDelete(store: string, key: string): Promise<void> {
  const db = await openKeyDB();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(store, "readwrite").objectStore(store).delete(key);
    tx.onsuccess = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}

async function idbSet(store: string, key: string, value: unknown): Promise<void> {
  const db = await openKeyDB();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(store, "readwrite").objectStore(store).put(value, key);
    tx.onsuccess = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}

let cachedKeyPair: StoredKeyPair | null = null;
let registerPromise: Promise<void> | null = null;
let currentUserID: number | null = null;

// adoptLegacyKeyPair returns the old browser-wide keypair only if the server
// has it registered as this user's key for this device — i.e. it really was
// theirs — then moves it to the per-account record.
async function adoptLegacyKeyPair(userID: number): Promise<StoredKeyPair | undefined> {
  const legacy = await idbGet<StoredKeyPair>(STORE, LEGACY_RECORD_KEY);
  if (!legacy) return undefined;
  try {
    const pub = await crypto.subtle.exportKey("jwk", legacy.publicKey);
    const mine = (await api.deviceKeys([userID])).find((k) => k.device_id === deviceID());
    const registered = mine ? (JSON.parse(mine.public_key_jwk) as JsonWebKey) : null;
    if (!registered || registered.x !== pub.x || registered.y !== pub.y) return undefined;
  } catch {
    return undefined;
  }
  await idbSet(STORE, recordKey(userID), legacy);
  await idbDelete(STORE, LEGACY_RECORD_KEY);
  return legacy;
}

export function isCryptoSubtleAvailable(): boolean {
  return typeof crypto !== "undefined" && !!crypto.subtle;
}

// getDeviceKeyPair returns this browser's ECDH keypair, generating one
// (non-extractable private key — it can never be read back out, only used)
// the first time and persisting it in IndexedDB from then on.
async function getDeviceKeyPair(): Promise<StoredKeyPair> {
  if (!isCryptoSubtleAvailable()) {
    throw new Error("WebCrypto is unavailable in insecure HTTP contexts. Connect over HTTPS to enable encryption.");
  }
  if (cachedKeyPair) return cachedKeyPair;
  const userID = currentUserID;
  if (userID == null) throw new Error("E2E keys are not available before sign-in");
  const stored = (await idbGet<StoredKeyPair>(STORE, recordKey(userID))) ?? (await adoptLegacyKeyPair(userID));
  if (stored) {
    cachedKeyPair = stored;
    return stored;
  }
  const pair = (await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, false, [
    "deriveKey",
  ])) as CryptoKeyPair;
  // The public key must be extractable to export as JWK for the server;
  // generateKey's `extractable` flag applies to both halves of the pair, so
  // re-derive an extractable public key by exporting/reimporting it — the
  // private key from generateKey (extractable: false) never had that option.
  const rawPub = await crypto.subtle.exportKey("jwk", pair.publicKey);
  const extractablePub = await crypto.subtle.importKey(
    "jwk", rawPub, { name: "ECDH", namedCurve: "P-256" }, true, [],
  );
  const record: StoredKeyPair = { publicKey: extractablePub, privateKey: pair.privateKey };
  await idbSet(STORE, recordKey(userID), record);
  cachedKeyPair = record;
  return record;
}

// ensureRegistered publishes this device's public key to the server, once
// per app load. Safe to call repeatedly (idempotent server-side upsert).
export function ensureDeviceRegistered(userID: number): Promise<void> {
  if (!isCryptoSubtleAvailable()) {
    return Promise.resolve();
  }
  if (currentUserID !== userID) {
    currentUserID = userID;
    cachedKeyPair = null;
    registerPromise = null;
  }
  if (!registerPromise) {
    registerPromise = (async () => {
      const { publicKey } = await getDeviceKeyPair();
      const jwk = await crypto.subtle.exportKey("jwk", publicKey);
      await api.registerDeviceKey(deviceID(), JSON.stringify(jwk));
    })();
  }
  return registerPromise;
}

function b64encode(buf: ArrayBuffer): string {
  const bytes = new Uint8Array(buf);
  let binary = "";
  const chunkSize = 8192;
  for (let i = 0; i < bytes.length; i += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunkSize));
  }
  return btoa(binary);
}
function b64decode(b64: string): Uint8Array {
  return Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
}

async function deriveWrapKey(privateKey: CryptoKey, peerPublicJwk: JsonWebKey): Promise<CryptoKey> {
  const peerPub = await crypto.subtle.importKey("jwk", peerPublicJwk, { name: "ECDH", namedCurve: "P-256" }, false, []);
  return crypto.subtle.deriveKey(
    { name: "ECDH", public: peerPub }, privateKey, { name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"],
  );
}

export interface EncryptedPayload {
  ciphertext: string;
  iv: string;
  encKeys: string; // JSON-stringified WrappedKeyEntry[]
}

// canEncryptTo checks whether every target user has at least one registered
// device key — the gate for showing/using the "encrypt this conversation"
// toggle. Returns the userIDs that are NOT ready, for a useful error.
export async function usersMissingKeys(userIDs: number[]): Promise<number[]> {
  const keys = await api.deviceKeys(userIDs);
  const ready = new Set(keys.map((k) => k.user_id));
  return userIDs.filter((id) => !ready.has(id));
}

// encryptForRecipients encrypts plaintext for every device of every user in
// recipientUserIDs (which should include the sender's own id, so their
// other devices/history sync too — callers pass the full participant set).
export async function encryptForRecipients(plaintext: string, recipientUserIDs: number[]): Promise<EncryptedPayload> {
  const { publicKey, privateKey } = await getDeviceKeyPair();
  const ownPubJwk = await crypto.subtle.exportKey("jwk", publicKey);

  const allKeys = await api.deviceKeys(recipientUserIDs);
  if (allKeys.length === 0) {
    throw new Error("No recipient has encryption set up on any device yet");
  }

  const contentKey = await crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, true, ["encrypt", "decrypt"]);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ciphertextBuf = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, contentKey, new TextEncoder().encode(plaintext));
  const rawContentKey = new Uint8Array(await crypto.subtle.exportKey("raw", contentKey));

  // Every device gets a wrapped key, including the sender's own current
  // one — reloading the page (or another tab) later needs to re-derive the
  // plaintext from stored history same as any other device would.
  const encKeys: WrappedKeyEntry[] = [];
  for (const dk of allKeys) {
    try {
      const wrapKey = await deriveWrapKey(privateKey, JSON.parse(dk.public_key_jwk));
      const wrapIv = crypto.getRandomValues(new Uint8Array(12));
      const wrapped = await crypto.subtle.encrypt({ name: "AES-GCM", iv: wrapIv }, wrapKey, rawContentKey);
      encKeys.push({
        user_id: dk.user_id, device_id: dk.device_id,
        wrapped_key: b64encode(wrapped), wrap_iv: b64encode(wrapIv.buffer as ArrayBuffer),
        sender_pub_jwk: ownPubJwk as JsonWebKey,
      });
    } catch {
      // A malformed/incompatible key on one device shouldn't sink the whole send.
    }
  }

  return {
    ciphertext: b64encode(ciphertextBuf),
    iv: b64encode(iv.buffer as ArrayBuffer),
    encKeys: JSON.stringify(encKeys),
  };
}

const MAX_DECRYPT_CACHE = 500;
const decryptCache = new Map<number, string>();

// Drop decrypted plaintext held in memory when the active account changes —
// this app is designed to run on shared/kiosk-style devices, so decrypted
// content from a previous session must not linger in memory indefinitely.
export function clearDecryptCache(): void {
  decryptCache.clear();
  cachedKeyPair = null;
  registerPromise = null;
  currentUserID = null;
}

// decryptMessageContent returns the plaintext for an encrypted message on
// this device, or null if this device has no wrapped key for it (sent
// before this device registered, or the sender excluded it). Results are
// cached by message id since decryption is a few async crypto calls.
export async function decryptMessageContent(msg: { id: number; is_encrypted: boolean; content: string; enc_iv?: string | null; enc_keys?: string | null }): Promise<string | null> {
  if (!msg.is_encrypted) return msg.content;
  if (!isCryptoSubtleAvailable()) return "[Encrypted message — HTTPS required to decrypt]";
  if (decryptCache.has(msg.id)) {
    const val = decryptCache.get(msg.id)!;
    decryptCache.delete(msg.id);
    decryptCache.set(msg.id, val);
    return val;
  }
  const result = await decryptNow(msg);
  if (result !== null) {
    if (decryptCache.size >= MAX_DECRYPT_CACHE) {
      const oldestKey = decryptCache.keys().next().value;
      if (oldestKey !== undefined) {
        decryptCache.delete(oldestKey);
      }
    }
    decryptCache.set(msg.id, result);
  }
  return result;
}

async function decryptNow(msg: { content: string; enc_iv?: string | null; enc_keys?: string | null }): Promise<string | null> {
  if (!msg.enc_keys || !msg.enc_iv) return null;
  let entries: WrappedKeyEntry[];
  try {
    entries = JSON.parse(msg.enc_keys);
  } catch {
    return null;
  }
  const myDeviceId = deviceID();
  const mine = entries.find((e) => e.device_id === myDeviceId);
  if (!mine) return null;
  try {
    const { privateKey } = await getDeviceKeyPair();
    const wrapKey = await deriveWrapKey(privateKey, mine.sender_pub_jwk);
    const rawContentKey = await crypto.subtle.decrypt({ name: "AES-GCM", iv: b64decode(mine.wrap_iv) }, wrapKey, b64decode(mine.wrapped_key));
    const contentKey = await crypto.subtle.importKey("raw", rawContentKey, { name: "AES-GCM" }, false, ["decrypt"]);
    const plainBuf = await crypto.subtle.decrypt({ name: "AES-GCM", iv: b64decode(msg.enc_iv) }, contentKey, b64decode(msg.content));
    return new TextDecoder().decode(plainBuf);
  } catch {
    return null;
  }
}
