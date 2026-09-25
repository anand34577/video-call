package db

import "testing"

// TestRebind is the self-check for the '?' -> '$N' rewrite that lets the
// app's sqlite-flavored queries run against Postgres unmodified.
func TestRebind(t *testing.T) {
	cases := []struct {
		dialect  Dialect
		in, want string
	}{
		{DialectSQLite, `SELECT * FROM t WHERE a = ? AND b = ?`, `SELECT * FROM t WHERE a = ? AND b = ?`},
		{DialectMySQL, `SELECT * FROM t WHERE a = ? AND b = ?`, `SELECT * FROM t WHERE a = ? AND b = ?`},
		// "groups" is reserved in MySQL 8: the table name gets backquoted there.
		{DialectMySQL, `SELECT 1 FROM groups g JOIN group_members gm ON gm.group_id = g.id`, "SELECT 1 FROM `groups` g JOIN group_members gm ON gm.group_id = g.id"},
		{DialectMySQL, `UPDATE groups SET name = ?`, "UPDATE `groups` SET name = ?"},
		{DialectPostgres, `SELECT * FROM t WHERE a = ? AND b = ?`, `SELECT * FROM t WHERE a = $1 AND b = $2`},
		{DialectPostgres, `SELECT * FROM t WHERE a = 'hello?' AND b = ?`, `SELECT * FROM t WHERE a = 'hello?' AND b = $1`},
		{DialectPostgres, `SELECT * FROM t WHERE a = 'it''s a question?' AND b = ?`, `SELECT * FROM t WHERE a = 'it''s a question?' AND b = $1`},
		{DialectPostgres, `SELECT 1`, `SELECT 1`},
	}
	for _, c := range cases {
		if got := rebind(c.dialect, c.in); got != c.want {
			t.Errorf("rebind(%s, %q) = %q, want %q", c.dialect, c.in, got, c.want)
		}
	}
}

// TestSplitStatementsCommentSemicolon guards against a real regression: a
// comment line whose own text ends in ';' right before the newline (e.g.
// "-- ... DEFAULT '';") must not be split off as its own empty statement —
// mysql rejects that with ER_EMPTY_QUERY (see migrations/mysql/010, 011).
func TestSplitStatementsCommentSemicolon(t *testing.T) {
	body := "-- sqlite already declares this column with DEFAULT '';\n" +
		"-- unrelated second comment line\n" +
		"ALTER TABLE messages MODIFY COLUMN content TEXT NOT NULL DEFAULT '';\n"
	got := splitStatements(body)
	want := []string{"ALTER TABLE messages MODIFY COLUMN content TEXT NOT NULL DEFAULT ''"}
	if len(got) != len(want) || got[0] != want[0] {
		t.Fatalf("splitStatements(%q) = %#v, want %#v", body, got, want)
	}
}

// TestInsertTableRE is the self-check for which INSERTs get rewritten with
// RETURNING id on Postgres (only tables the app calls LastInsertId() on).
func TestInsertTableRE(t *testing.T) {
	cases := []struct {
		query string
		want  string
	}{
		{`INSERT INTO users (a) VALUES (?)`, "users"},
		{`  insert into messages (a) values (?)`, "messages"},
		{`UPDATE users SET a = ?`, ""},
		{`INSERT INTO group_members (a) VALUES (?)`, "group_members"},
	}
	for _, c := range cases {
		m := insertTableRE.FindStringSubmatch(c.query)
		got := ""
		if m != nil {
			got = m[1]
		}
		if got != c.want {
			t.Errorf("insertTableRE(%q) table = %q, want %q", c.query, got, c.want)
		}
		if c.want != "" && got != "" {
			needsID := pgReturningIDTables[got]
			wantID := c.want == "users" || c.want == "messages"
			if needsID != wantID {
				t.Errorf("pgReturningIDTables[%q] = %v, want %v", got, needsID, wantID)
			}
		}
	}
}
