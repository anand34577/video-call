package db

import (
	"database/sql"
	"errors"
)

// GetAppSetting returns a DB-stored admin setting, or ErrNotFound if it was
// never set (or was reset back to default).
func (d *DB) GetAppSetting(key string) (string, error) {
	var value string
	err := d.QueryRow(`SELECT value FROM app_settings WHERE setting_key = ?`, key).Scan(&value)
	if errors.Is(err, sql.ErrNoRows) {
		return "", ErrNotFound
	}
	return value, err
}

// SetAppSetting upserts one setting. updatedBy is nil for a system-initiated
// write (there shouldn't be any today, but the column allows it).
func (d *DB) SetAppSetting(key, value string, updatedBy *int64) error {
	q := insertIgnoreSQL(d.dialect, `app_settings (setting_key, value, updated_at, updated_by)`, `?, ?, ?, ?`, `setting_key`)
	if _, err := d.Exec(q, key, value, now(), recID(updatedBy)); err != nil {
		return err
	}
	_, err := d.Exec(`UPDATE app_settings SET value = ?, updated_at = ?, updated_by = ? WHERE setting_key = ?`,
		value, now(), recID(updatedBy), key)
	return err
}

// DeleteAppSetting resets a key back to its environment/default value.
func (d *DB) DeleteAppSetting(key string) error {
	_, err := d.Exec(`DELETE FROM app_settings WHERE setting_key = ?`, key)
	return err
}

// ListAppSettings returns every DB-stored override, keyed by setting key.
func (d *DB) ListAppSettings() (map[string]string, error) {
	rows, err := d.Query(`SELECT setting_key, value FROM app_settings`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]string{}
	for rows.Next() {
		var k, v string
		if err := rows.Scan(&k, &v); err != nil {
			return nil, err
		}
		out[k] = v
	}
	return out, rows.Err()
}
