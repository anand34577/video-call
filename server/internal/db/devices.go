package db

// DeviceKey is one device's registered E2E public key. The server stores
// and serves these verbatim — it has no way to use them (no private key),
// they're only useful to other clients doing the actual encryption.
type DeviceKey struct {
	UserID       int64  `json:"user_id"`
	DeviceID     string `json:"device_id"`
	PublicKeyJWK string `json:"public_key_jwk"`
}

// UpsertDeviceKey registers or replaces a device's public key. Called once
// per device per browser profile (keys are stable once generated; a fresh
// browser profile/cleared storage generates a new one, which naturally
// orphans old messages on that device — there's no way around that with a
// server that never holds private keys).
func (d *DB) UpsertDeviceKey(userID int64, deviceID, publicKeyJWK string) error {
	q := insertIgnoreSQL(d.dialect, `user_devices (user_id, device_id, public_key_jwk, created_at)`, `?, ?, ?, ?`, `user_id, device_id`)
	if _, err := d.Exec(q, userID, deviceID, publicKeyJWK, now()); err != nil {
		return err
	}
	// insertIgnoreSQL is INSERT-if-absent; an existing device re-registering
	// (e.g. after a key rotation) needs an explicit update too.
	_, err := d.Exec(`UPDATE user_devices SET public_key_jwk = ? WHERE user_id = ? AND device_id = ?`, publicKeyJWK, userID, deviceID)
	return err
}

// DeviceKeysForUsers returns every registered device key for the given
// users — the fan-out list a sender encrypts a message's content key to.
func (d *DB) DeviceKeysForUsers(userIDs []int64) ([]DeviceKey, error) {
	if len(userIDs) == 0 {
		return nil, nil
	}
	rows, err := d.Query(`SELECT user_id, device_id, public_key_jwk FROM user_devices WHERE user_id IN (`+placeholders(len(userIDs))+`)`, int64Args(userIDs)...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []DeviceKey
	for rows.Next() {
		var k DeviceKey
		if err := rows.Scan(&k.UserID, &k.DeviceID, &k.PublicKeyJWK); err != nil {
			return nil, err
		}
		out = append(out, k)
	}
	return out, rows.Err()
}
