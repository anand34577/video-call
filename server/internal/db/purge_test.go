package db

import "testing"

// Deleting an account marks it deleted and demotes it, so it can't be
// counted as an admin or re-enabled later.
func TestAnonymizeUserMarksDeleted(t *testing.T) {
	d := openTestDB(t)
	admin, _ := d.CreateUser("boss", "Boss", "hash", "admin")
	if err := d.AnonymizeUser(admin.ID); err != nil {
		t.Fatal(err)
	}
	u, err := d.GetUserByID(admin.ID)
	if err != nil {
		t.Fatal(err)
	}
	if !u.Deleted || u.Role != "user" {
		t.Fatalf("got deleted=%v role=%q, want deleted user", u.Deleted, u.Role)
	}
	if n, _ := d.CountEnabledAdmins(); n != 0 {
		t.Fatalf("deleted admin still counted: %d", n)
	}
}

// Permanent deletion removes the user and their messages, but a group they
// created survives under another member.
func TestPurgeUser(t *testing.T) {
	d := openTestDB(t)
	alice, _ := d.CreateUser("alice", "Alice", "hash", "user")
	bob, _ := d.CreateUser("bob", "Bob", "hash", "user")
	carol, _ := d.CreateUser("carol", "Carol", "hash", "user")

	dm, err := d.InsertMessage(bob.ID, &alice.ID, nil, nil, nil, "hi alice")
	if err != nil {
		t.Fatal(err)
	}
	shared, err := d.CreateGroup("team", alice.ID, []int64{bob.ID, carol.ID})
	if err != nil {
		t.Fatal(err)
	}
	solo, err := d.CreateGroup("just me", alice.ID, nil)
	if err != nil {
		t.Fatal(err)
	}
	fromAlice, _ := d.InsertMessage(alice.ID, nil, &shared.ID, nil, nil, "from alice")
	fromBob, _ := d.InsertMessage(bob.ID, nil, &shared.ID, nil, nil, "from bob")

	if err := d.PurgeUser(alice.ID); err != nil {
		t.Fatalf("PurgeUser: %v", err)
	}

	if _, err := d.GetUserByID(alice.ID); err != ErrNotFound {
		t.Fatalf("user still exists: %v", err)
	}
	if _, err := d.GetMessage(dm.ID); err == nil {
		t.Fatal("direct message with the deleted user survived")
	}
	if _, err := d.GetMessage(fromAlice.ID); err == nil {
		t.Fatal("the deleted user's group message survived")
	}
	if _, err := d.GetMessage(fromBob.ID); err != nil {
		t.Fatalf("another member's group message was lost: %v", err)
	}
	g, err := d.GetGroup(shared.ID)
	if err != nil {
		t.Fatalf("shared group was deleted: %v", err)
	}
	if g.CreatedBy != bob.ID {
		t.Fatalf("group owner = %d, want bob (%d)", g.CreatedBy, bob.ID)
	}
	if _, err := d.GetGroup(solo.ID); err == nil {
		t.Fatal("empty group should be removed")
	}
}
