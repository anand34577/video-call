package db

import (
	"path/filepath"
	"testing"
	"time"
)

func openTestDB(t *testing.T) *DB {
	t.Helper()
	d, err := Open("sqlite", ":memory:")
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	t.Cleanup(func() { d.Close() })
	return d
}

// TestAnonymizeUserPreservesMessages is the regression check for the
// "deleting a user destroys the other party's chat history" bug: closing an
// account must not cascade-delete messages the account sent or received.
func TestAnonymizeUserPreservesMessages(t *testing.T) {
	d := openTestDB(t)
	alice, err := d.CreateUser("alice", "Alice", "hash", "user")
	if err != nil {
		t.Fatalf("CreateUser alice: %v", err)
	}
	bob, err := d.CreateUser("bob", "Bob", "hash", "user")
	if err != nil {
		t.Fatalf("CreateUser bob: %v", err)
	}
	msg, err := d.InsertMessage(alice.ID, &bob.ID, nil, nil, nil, "hello bob")
	if err != nil {
		t.Fatalf("InsertMessage: %v", err)
	}

	if err := d.AnonymizeUser(alice.ID); err != nil {
		t.Fatalf("AnonymizeUser: %v", err)
	}

	got, err := d.GetMessage(msg.ID)
	if err != nil {
		t.Fatalf("message vanished after anonymizing its sender: %v", err)
	}
	if got.Content != "hello bob" {
		t.Fatalf("message content changed: got %q", got.Content)
	}

	u, err := d.GetUserByID(alice.ID)
	if err != nil {
		t.Fatalf("anonymized user row should still exist: %v", err)
	}
	if !u.Disabled {
		t.Fatal("anonymized user should be disabled")
	}
	if u.PasswordHash == "hash" {
		t.Fatal("anonymized user's original password hash should be replaced")
	}
	if u.Username == "alice" {
		t.Fatal("anonymized user's original username should be replaced")
	}
}

func TestDeleteMessageSoftDeletes(t *testing.T) {
	d := openTestDB(t)
	alice, _ := d.CreateUser("alice", "Alice", "hash", "user")
	bob, _ := d.CreateUser("bob", "Bob", "hash", "user")
	msg, err := d.InsertMessage(alice.ID, &bob.ID, nil, nil, nil, "secret")
	if err != nil {
		t.Fatalf("InsertMessage: %v", err)
	}

	// Bob may not delete Alice's message.
	if _, err := d.DeleteMessage(msg.ID, bob.ID); err != ErrNotFound {
		t.Fatalf("non-sender delete should fail with ErrNotFound, got %v", err)
	}

	got, err := d.DeleteMessage(msg.ID, alice.ID)
	if err != nil {
		t.Fatalf("DeleteMessage: %v", err)
	}
	if got.Content != "" || got.DeletedAt == nil {
		t.Fatalf("expected cleared content and deleted_at, got %+v", got)
	}

	// Deleting again should no-op (already deleted).
	if _, err := d.DeleteMessage(msg.ID, alice.ID); err != ErrNotFound {
		t.Fatalf("re-delete should fail with ErrNotFound, got %v", err)
	}
}

func TestGroupMembership(t *testing.T) {
	d := openTestDB(t)
	owner, _ := d.CreateUser("owner", "Owner", "hash", "user")
	bob, _ := d.CreateUser("bob", "Bob", "hash", "user")
	carol, _ := d.CreateUser("carol", "Carol", "hash", "user")

	g, err := d.CreateGroup("Team", owner.ID, nil)
	if err != nil {
		t.Fatalf("CreateGroup: %v", err)
	}
	if err := d.AddGroupMembers(g.ID, []int64{bob.ID, carol.ID}); err != nil {
		t.Fatalf("AddGroupMembers: %v", err)
	}
	if member, err := d.IsGroupMember(g.ID, bob.ID); err != nil || !member {
		t.Fatalf("bob should be a member: %v %v", member, err)
	}
	if err := d.RemoveGroupMember(g.ID, bob.ID); err != nil {
		t.Fatalf("RemoveGroupMember: %v", err)
	}
	if member, err := d.IsGroupMember(g.ID, bob.ID); err != nil || member {
		t.Fatalf("bob should no longer be a member: %v %v", member, err)
	}
	if member, err := d.IsGroupMember(g.ID, carol.ID); err != nil || !member {
		t.Fatalf("carol should be unaffected: %v %v", member, err)
	}
	if err := d.RenameGroup(g.ID, "Renamed Team"); err != nil {
		t.Fatalf("RenameGroup: %v", err)
	}
	got, err := d.GetGroup(g.ID)
	if err != nil || got.Name != "Renamed Team" {
		t.Fatalf("rename did not stick: %+v %v", got, err)
	}
}

func TestEditMessage(t *testing.T) {
	d := openTestDB(t)
	alice, _ := d.CreateUser("alice", "Alice", "hash", "user")
	bob, _ := d.CreateUser("bob", "Bob", "hash", "user")
	msg, err := d.InsertMessage(alice.ID, &bob.ID, nil, nil, nil, "orignal")
	if err != nil {
		t.Fatalf("InsertMessage: %v", err)
	}

	if _, err := d.EditMessage(msg.ID, bob.ID, "hijacked"); err != ErrNotFound {
		t.Fatalf("non-sender edit should fail with ErrNotFound, got %v", err)
	}

	got, err := d.EditMessage(msg.ID, alice.ID, "corrected")
	if err != nil {
		t.Fatalf("EditMessage: %v", err)
	}
	if got.Content != "corrected" || got.EditedAt == nil {
		t.Fatalf("expected updated content and edited_at, got %+v", got)
	}

	if _, err := d.DeleteMessage(msg.ID, alice.ID); err != nil {
		t.Fatalf("DeleteMessage: %v", err)
	}
	if _, err := d.EditMessage(msg.ID, alice.ID, "resurrect"); err != ErrNotFound {
		t.Fatalf("editing a deleted message should fail with ErrNotFound, got %v", err)
	}
}

func TestToggleReaction(t *testing.T) {
	d := openTestDB(t)
	alice, _ := d.CreateUser("alice", "Alice", "hash", "user")
	bob, _ := d.CreateUser("bob", "Bob", "hash", "user")
	msg, _ := d.InsertMessage(alice.ID, &bob.ID, nil, nil, nil, "hi")

	added, err := d.ToggleReaction(msg.ID, bob.ID, "👍")
	if err != nil || !added {
		t.Fatalf("first toggle should add: %v %v", added, err)
	}
	added, err = d.ToggleReaction(msg.ID, bob.ID, "👍")
	if err != nil || added {
		t.Fatalf("second toggle should remove: %v %v", added, err)
	}
	if _, err := d.ToggleReaction(msg.ID, bob.ID, "👍"); err != nil {
		t.Fatalf("re-add: %v", err)
	}
	reactions, err := d.ReactionsForMessages([]int64{msg.ID})
	if err != nil || len(reactions[msg.ID]) != 1 {
		t.Fatalf("expected one reaction, got %+v (err %v)", reactions[msg.ID], err)
	}
}

func TestReplyPreview(t *testing.T) {
	d := openTestDB(t)
	alice, _ := d.CreateUser("alice", "Alice", "hash", "user")
	bob, _ := d.CreateUser("bob", "Bob", "hash", "user")
	original, _ := d.InsertMessage(alice.ID, &bob.ID, nil, nil, nil, "original message")
	reply, err := d.InsertMessage(bob.ID, &alice.ID, nil, nil, &original.ID, "replying")
	if err != nil {
		t.Fatalf("InsertMessage reply: %v", err)
	}
	if reply.ReplyToID == nil || *reply.ReplyToID != original.ID {
		t.Fatalf("expected reply_to_id %d, got %+v", original.ID, reply.ReplyToID)
	}
	previews, err := d.ReplyPreviews([]int64{original.ID})
	if err != nil || previews[original.ID] == nil || previews[original.ID].Content != "original message" {
		t.Fatalf("unexpected reply preview: %+v (err %v)", previews[original.ID], err)
	}
}

func TestPasswordResetTokenLifecycle(t *testing.T) {
	d := openTestDB(t)
	alice, _ := d.CreateUser("alice", "Alice", "hash", "user")

	token, err := d.CreatePasswordResetToken(alice.ID, time.Hour)
	if err != nil {
		t.Fatalf("CreatePasswordResetToken: %v", err)
	}
	if _, err := d.ConsumePasswordResetToken("wrong-token"); err != ErrNotFound {
		t.Fatalf("wrong token should fail with ErrNotFound, got %v", err)
	}
	uid, err := d.ConsumePasswordResetToken(token)
	if err != nil || uid != alice.ID {
		t.Fatalf("ConsumePasswordResetToken: uid=%d err=%v", uid, err)
	}
	// Replay must fail: a token is single-use.
	if _, err := d.ConsumePasswordResetToken(token); err != ErrNotFound {
		t.Fatalf("replayed token should fail with ErrNotFound, got %v", err)
	}

	expired, err := d.CreatePasswordResetToken(alice.ID, -time.Minute)
	if err != nil {
		t.Fatalf("CreatePasswordResetToken (expired): %v", err)
	}
	if _, err := d.ConsumePasswordResetToken(expired); err != ErrNotFound {
		t.Fatalf("expired token should fail with ErrNotFound, got %v", err)
	}
}

func TestPinMessage(t *testing.T) {
	d := openTestDB(t)
	alice, _ := d.CreateUser("alice", "Alice", "hash", "user")
	bob, _ := d.CreateUser("bob", "Bob", "hash", "user")
	msg, err := d.InsertMessage(alice.ID, &bob.ID, nil, nil, nil, "pin me")
	if err != nil {
		t.Fatalf("InsertMessage: %v", err)
	}
	if msg.PinnedAt != nil {
		t.Fatal("new message should not start pinned")
	}
	pinned, err := d.PinMessage(msg.ID, true)
	if err != nil || pinned.PinnedAt == nil {
		t.Fatalf("PinMessage(true): %+v %v", pinned, err)
	}
	list, err := d.PinnedInDM(alice.ID, bob.ID)
	if err != nil || len(list) != 1 || list[0].ID != msg.ID {
		t.Fatalf("PinnedInDM: %+v %v", list, err)
	}
	unpinned, err := d.PinMessage(msg.ID, false)
	if err != nil || unpinned.PinnedAt != nil {
		t.Fatalf("PinMessage(false): %+v %v", unpinned, err)
	}
	list, err = d.PinnedInDM(alice.ID, bob.ID)
	if err != nil || len(list) != 0 {
		t.Fatalf("expected no pins after unpin, got %+v (err %v)", list, err)
	}
}

func TestGroupRoles(t *testing.T) {
	d := openTestDB(t)
	owner, _ := d.CreateUser("owner", "Owner", "hash", "user")
	bob, _ := d.CreateUser("bob", "Bob", "hash", "user")
	g, err := d.CreateGroup("Team", owner.ID, []int64{bob.ID})
	if err != nil {
		t.Fatalf("CreateGroup: %v", err)
	}
	if role, err := d.GroupMemberRole(g.ID, owner.ID); err != nil || role != "owner" {
		t.Fatalf("creator should be owner, got %q (err %v)", role, err)
	}
	if role, err := d.GroupMemberRole(g.ID, bob.ID); err != nil || role != "member" {
		t.Fatalf("invitee should be member, got %q (err %v)", role, err)
	}
	if err := d.SetGroupMemberRole(g.ID, bob.ID, "admin"); err != nil {
		t.Fatalf("SetGroupMemberRole: %v", err)
	}
	if role, err := d.GroupMemberRole(g.ID, bob.ID); err != nil || role != "admin" {
		t.Fatalf("bob should now be admin, got %q (err %v)", role, err)
	}
	// The owner role is protected: this must be a no-op, not a demotion.
	if err := d.SetGroupMemberRole(g.ID, owner.ID, "member"); err != nil {
		t.Fatalf("SetGroupMemberRole(owner): %v", err)
	}
	if role, err := d.GroupMemberRole(g.ID, owner.ID); err != nil || role != "owner" {
		t.Fatalf("owner role must stay owner, got %q (err %v)", role, err)
	}
}

func TestSearchMessages(t *testing.T) {
	d := openTestDB(t)
	alice, _ := d.CreateUser("alice", "Alice", "hash", "user")
	bob, _ := d.CreateUser("bob", "Bob", "hash", "user")
	carol, _ := d.CreateUser("carol", "Carol", "hash", "user")
	if _, err := d.InsertMessage(alice.ID, &bob.ID, nil, nil, nil, "let's meet at the Lighthouse cafe"); err != nil {
		t.Fatalf("InsertMessage: %v", err)
	}
	if _, err := d.InsertMessage(bob.ID, &carol.ID, nil, nil, nil, "totally unrelated"); err != nil {
		t.Fatalf("InsertMessage: %v", err)
	}
	results, err := d.SearchMessages(alice.ID, SearchFilter{Query: "lighthouse", Limit: 10})
	if err != nil || len(results) != 1 {
		t.Fatalf("expected 1 case-insensitive match for alice, got %+v (err %v)", results, err)
	}
	if results, err := d.SearchMessages(carol.ID, SearchFilter{Query: "lighthouse", Limit: 10}); err != nil || len(results) != 0 {
		t.Fatalf("carol shouldn't see alice/bob's DM, got %+v (err %v)", results, err)
	}
	if results, err := d.SearchMessages(bob.ID, SearchFilter{SenderID: alice.ID, Limit: 10}); err != nil || len(results) != 1 {
		t.Fatalf("sender filter should find alice's message, got %+v (err %v)", results, err)
	}
	if results, err := d.SearchMessages(bob.ID, SearchFilter{HasFile: true, Limit: 10}); err != nil || len(results) != 0 {
		t.Fatalf("has_file filter should find nothing (no attachments in this test), got %+v (err %v)", results, err)
	}
}

func TestBackup(t *testing.T) {
	d := openTestDB(t)
	if _, err := d.CreateUser("alice", "Alice", "hash", "user"); err != nil {
		t.Fatalf("CreateUser: %v", err)
	}
	dest := filepath.Join(t.TempDir(), "backup.db")
	if err := d.Backup(dest); err != nil {
		t.Fatalf("Backup: %v", err)
	}
	restored, err := Open("sqlite", dest)
	if err != nil {
		t.Fatalf("Open(backup): %v", err)
	}
	defer restored.Close()
	u, err := restored.GetUserByUsername("alice")
	if err != nil || u.DisplayName != "Alice" {
		t.Fatalf("backup did not contain the user: %+v (err %v)", u, err)
	}
}

func TestSavedMessages(t *testing.T) {
	d := openTestDB(t)
	alice, _ := d.CreateUser("alice", "Alice", "hash", "user")
	bob, _ := d.CreateUser("bob", "Bob", "hash", "user")
	msg, err := d.InsertMessage(alice.ID, &bob.ID, nil, nil, nil, "remember this")
	if err != nil {
		t.Fatalf("InsertMessage: %v", err)
	}
	if ok, err := d.CanSeeMessage(msg.ID, alice.ID); err != nil || !ok {
		t.Fatalf("alice should see her own message: %v %v", ok, err)
	}
	if ok, err := d.CanSeeMessage(msg.ID, 999); err != nil || ok {
		t.Fatalf("stranger should not see the message: %v %v", ok, err)
	}
	if err := d.SaveMessage(bob.ID, msg.ID); err != nil {
		t.Fatalf("SaveMessage: %v", err)
	}
	// saving twice is a no-op, not an error
	if err := d.SaveMessage(bob.ID, msg.ID); err != nil {
		t.Fatalf("SaveMessage (again): %v", err)
	}
	saved, err := d.ListSavedMessages(bob.ID, 10)
	if err != nil || len(saved) != 1 || saved[0].ID != msg.ID {
		t.Fatalf("expected bob's saved list to contain the message, got %+v (err %v)", saved, err)
	}
	if saved, err := d.ListSavedMessages(alice.ID, 10); err != nil || len(saved) != 0 {
		t.Fatalf("alice never saved anything, got %+v (err %v)", saved, err)
	}
	if err := d.UnsaveMessage(bob.ID, msg.ID); err != nil {
		t.Fatalf("UnsaveMessage: %v", err)
	}
	if saved, err := d.ListSavedMessages(bob.ID, 10); err != nil || len(saved) != 0 {
		t.Fatalf("expected empty after unsave, got %+v (err %v)", saved, err)
	}
}

func TestOIDCLinking(t *testing.T) {
	d := openTestDB(t)
	alice, _ := d.CreateUser("alice", "Alice", "hash", "user")
	bob, _ := d.CreateUser("bob", "Bob", "hash", "user")

	if _, err := d.GetUserByOIDC("https://idp", "sub-1"); err != ErrNotFound {
		t.Fatalf("expected ErrNotFound before linking, got %v", err)
	}
	if err := d.LinkOIDC(alice.ID, "https://idp", "sub-1"); err != nil {
		t.Fatalf("LinkOIDC: %v", err)
	}
	u, err := d.GetUserByOIDC("https://idp", "sub-1")
	if err != nil || u.ID != alice.ID {
		t.Fatalf("GetUserByOIDC: %+v %v", u, err)
	}
	if !u.OIDCLinked() {
		t.Fatal("expected OIDCLinked() true after linking")
	}
	// The same external identity can't be linked to a second local account.
	if err := d.LinkOIDC(bob.ID, "https://idp", "sub-1"); err != ErrOIDCAlreadyLinked {
		t.Fatalf("expected ErrOIDCAlreadyLinked, got %v", err)
	}
	if err := d.UnlinkOIDC(alice.ID); err != nil {
		t.Fatalf("UnlinkOIDC: %v", err)
	}
	if _, err := d.GetUserByOIDC("https://idp", "sub-1"); err != ErrNotFound {
		t.Fatalf("expected ErrNotFound after unlinking, got %v", err)
	}
	// Now bob can claim the freed identity.
	if err := d.LinkOIDC(bob.ID, "https://idp", "sub-1"); err != nil {
		t.Fatalf("LinkOIDC (bob after free): %v", err)
	}
}

func TestDeviceKeysAndEncryptedMessages(t *testing.T) {
	d := openTestDB(t)
	alice, _ := d.CreateUser("alice", "Alice", "hash", "user")
	bob, _ := d.CreateUser("bob", "Bob", "hash", "user")

	if err := d.UpsertDeviceKey(alice.ID, "dev-1", `{"kty":"EC","x":"a"}`); err != nil {
		t.Fatalf("UpsertDeviceKey: %v", err)
	}
	// Re-registering the same device (key rotation) should update, not conflict.
	if err := d.UpsertDeviceKey(alice.ID, "dev-1", `{"kty":"EC","x":"b"}`); err != nil {
		t.Fatalf("UpsertDeviceKey (rotate): %v", err)
	}
	if err := d.UpsertDeviceKey(bob.ID, "dev-2", `{"kty":"EC","x":"c"}`); err != nil {
		t.Fatalf("UpsertDeviceKey: %v", err)
	}
	keys, err := d.DeviceKeysForUsers([]int64{alice.ID, bob.ID})
	if err != nil || len(keys) != 2 {
		t.Fatalf("DeviceKeysForUsers: %+v (err %v)", keys, err)
	}
	for _, k := range keys {
		if k.UserID == alice.ID && k.PublicKeyJWK != `{"kty":"EC","x":"b"}` {
			t.Fatalf("expected rotated key, got %q", k.PublicKeyJWK)
		}
	}

	msg, err := d.InsertEncryptedMessage(alice.ID, &bob.ID, nil, nil, nil, "ciphertext-blob", "iv-blob", `[{"user_id":2,"device_id":"dev-2","wrapped_key":"x"}]`)
	if err != nil {
		t.Fatalf("InsertEncryptedMessage: %v", err)
	}
	if !msg.IsEncrypted || msg.Content != "ciphertext-blob" || msg.EncIV == nil || *msg.EncIV != "iv-blob" {
		t.Fatalf("unexpected encrypted message shape: %+v", msg)
	}
	// A content search must not match against ciphertext.
	if results, err := d.SearchMessages(bob.ID, SearchFilter{Query: "ciphertext", Limit: 10}); err != nil || len(results) != 0 {
		t.Fatalf("search should not match encrypted content, got %+v (err %v)", results, err)
	}
}

func TestGroupUnreadCounts(t *testing.T) {
	d := openTestDB(t)
	alice, _ := d.CreateUser("alice", "Alice", "hash", "user")
	bob, _ := d.CreateUser("bob", "Bob", "hash", "user")
	g, err := d.CreateGroup("Team", alice.ID, []int64{bob.ID})
	if err != nil {
		t.Fatalf("CreateGroup: %v", err)
	}
	if _, err := d.InsertMessage(alice.ID, nil, &g.ID, nil, nil, "hello"); err != nil {
		t.Fatalf("InsertMessage: %v", err)
	}
	counts, err := d.GroupUnreadCounts(bob.ID)
	if err != nil || counts[g.ID] != 1 {
		t.Fatalf("expected 1 unread for bob, got %+v (err %v)", counts, err)
	}
	if err := d.MarkGroupRead(g.ID, bob.ID); err != nil {
		t.Fatalf("MarkGroupRead: %v", err)
	}
	counts, err = d.GroupUnreadCounts(bob.ID)
	if err != nil || counts[g.ID] != 0 {
		t.Fatalf("expected 0 unread after marking read, got %+v (err %v)", counts, err)
	}
	// The sender's own message never counts as unread for them.
	aliceCounts, err := d.GroupUnreadCounts(alice.ID)
	if err != nil || aliceCounts[g.ID] != 0 {
		t.Fatalf("sender should have 0 unread, got %+v (err %v)", aliceCounts, err)
	}
}

func TestCanAccessFileGroupAvatar(t *testing.T) {
	d := openTestDB(t)
	alice, _ := d.CreateUser("alice", "Alice", "hash", "user")
	bob, _ := d.CreateUser("bob", "Bob", "hash", "user")
	charlie, _ := d.CreateUser("charlie", "Charlie", "hash", "user")

	file, err := d.InsertFile(alice.ID, "avatar.png", "/path/to/avatar.png", 1024, "image/png")
	if err != nil {
		t.Fatalf("InsertFile: %v", err)
	}

	group, err := d.CreateGroup("Designers", alice.ID, []int64{bob.ID})
	if err != nil {
		t.Fatalf("CreateGroup: %v", err)
	}
	if err := d.SetGroupAvatar(group.ID, &file.ID); err != nil {
		t.Fatalf("SetGroupAvatar: %v", err)
	}

	// Bob is a group member - must have access to group avatar
	canBob, err := d.CanAccessFile(file.ID, bob.ID)
	if err != nil || !canBob {
		t.Fatalf("Bob (group member) should be able to access group avatar: canBob=%v, err=%v", canBob, err)
	}

	// Charlie is not a group member - must NOT have access
	canCharlie, err := d.CanAccessFile(file.ID, charlie.ID)
	if err != nil || canCharlie {
		t.Fatalf("Charlie (non-member) should NOT be able to access group avatar: canCharlie=%v, err=%v", canCharlie, err)
	}

	// FilePathsForUploader must not return avatar if group still references it
	paths, err := d.FilePathsForUploader(alice.ID)
	if err != nil {
		t.Fatalf("FilePathsForUploader: %v", err)
	}
	for _, p := range paths {
		if p == "/path/to/avatar.png" {
			t.Fatalf("FilePathsForUploader should not return active group avatar path")
		}
	}
}

