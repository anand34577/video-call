package backup

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"

	"visioncall/internal/db"
)

func openAt(t *testing.T, dir string) *db.DB {
	t.Helper()
	d, err := db.Open("sqlite", filepath.Join(dir, dbName))
	if err != nil {
		t.Fatal(err)
	}
	return d
}

// A full backup made on one server restores onto another: database,
// uploads and session key all come across, and the old data is kept aside.
func TestFullBackupRoundTrip(t *testing.T) {
	src := t.TempDir()
	d := openAt(t, src)
	if _, err := d.CreateUser("boss", "Boss", "hash", "admin"); err != nil {
		t.Fatal(err)
	}
	os.MkdirAll(filepath.Join(src, "files"), 0o755)
	os.WriteFile(filepath.Join(src, "files", "abc.png"), []byte("image"), 0o600)
	os.WriteFile(filepath.Join(src, secretName), []byte("secret-from-backup"), 0o600)

	var buf bytes.Buffer
	if err := WriteFull(&buf, d, src, "test"); err != nil {
		t.Fatal(err)
	}
	d.Close()
	zipPath := filepath.Join(t.TempDir(), "backup.zip")
	os.WriteFile(zipPath, buf.Bytes(), 0o600)

	dst := t.TempDir()
	other := openAt(t, dst)
	other.CreateUser("someone", "Someone", "hash", "admin")
	other.Close()
	os.WriteFile(filepath.Join(dst, secretName), []byte("old-secret"), 0o600)

	if err := StageZip(dst, zipPath); err != nil {
		t.Fatalf("StageZip: %v", err)
	}
	applied, err := ApplyPending(dst)
	if err != nil || !applied {
		t.Fatalf("ApplyPending = %v, %v", applied, err)
	}

	restored := openAt(t, dst)
	defer restored.Close()
	if _, err := restored.GetUserByUsername("boss"); err != nil {
		t.Fatalf("restored database is missing the backup's user: %v", err)
	}
	if b, _ := os.ReadFile(filepath.Join(dst, "files", "abc.png")); string(b) != "image" {
		t.Fatal("uploaded file was not restored")
	}
	if b, _ := os.ReadFile(filepath.Join(dst, secretName)); string(b) != "secret-from-backup" {
		t.Fatal("session key was not restored")
	}
	kept, _ := filepath.Glob(filepath.Join(dst, "pre-restore-*", dbName))
	if len(kept) != 1 {
		t.Fatal("the previous database was not kept aside")
	}
	if HasPending(dst) {
		t.Fatal("restore is still pending after being applied")
	}
}

// A backup nobody could sign in to is refused and nothing is staged.
func TestRestoreRefusesBackupWithoutAdmin(t *testing.T) {
	src := t.TempDir()
	d := openAt(t, src)
	d.CreateUser("user", "User", "hash", "user")
	snap, err := TakeSnapshot(d, src)
	d.Close()
	if err != nil {
		t.Fatal(err)
	}
	path, err := SnapshotPath(src, snap.Name)
	if err != nil {
		t.Fatal(err)
	}
	dst := t.TempDir()
	if err := StageDatabase(dst, path); err == nil {
		t.Fatal("a backup without an admin was accepted")
	}
	if HasPending(dst) {
		t.Fatal("a rejected backup was left staged")
	}
}

func TestSnapshotPathRejectsTraversal(t *testing.T) {
	for _, name := range []string{"../visioncall.db", "..\\x.db", "other.db", "visioncall-../../x.db"} {
		if _, err := SnapshotPath(t.TempDir(), name); err == nil {
			t.Errorf("accepted %q", name)
		}
	}
}
