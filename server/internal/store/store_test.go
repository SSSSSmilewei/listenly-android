package store

import (
	"context"
	"errors"
	"path/filepath"
	"testing"
)

func openTest(t *testing.T) (*Store, string) {
	t.Helper()
	p := filepath.Join(t.TempDir(), "t.db")
	s, err := Open(context.Background(), p)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return s, p
}

func TestMigrationsIdempotentAndPragmas(t *testing.T) {
	ctx := context.Background()
	s, p := openTest(t)
	v, err := s.SchemaVersion(ctx)
	if err != nil || v != 2 {
		t.Fatalf("version=%d err=%v", v, err)
	}
	s.Close()
	s2, err := Open(ctx, p)
	if err != nil {
		t.Fatal(err)
	}
	defer s2.Close()
	if v, _ := s2.SchemaVersion(ctx); v != 2 {
		t.Fatalf("version after reopen = %d", v)
	}
	var mode string
	if err := s2.reader.QueryRowContext(ctx, "PRAGMA journal_mode").Scan(&mode); err != nil || mode != "wal" {
		t.Fatalf("journal_mode=%q err=%v", mode, err)
	}
	var fk int
	if err := s2.reader.QueryRowContext(ctx, "PRAGMA foreign_keys").Scan(&fk); err != nil || fk != 1 {
		t.Fatalf("foreign_keys=%d err=%v", fk, err)
	}
	ms, err := loadMigrations()
	if err != nil || len(ms) == 0 {
		t.Fatalf("loadMigrations: %v", err)
	}
}

func TestUserConflictAndCascade(t *testing.T) {
	ctx := context.Background()
	s, _ := openTest(t)
	u := User{ID: "u_1", Username: "alice", PasswordHash: "x", CreatedAt: 1}
	if err := s.Tx(ctx, func(r Repo) error { return r.CreateUser(ctx, u) }); err != nil {
		t.Fatal(err)
	}
	err := s.Tx(ctx, func(r Repo) error { return r.CreateUser(ctx, User{ID: "u_2", Username: "alice", PasswordHash: "x"}) })
	if !errors.Is(err, ErrConflict) {
		t.Fatalf("want ErrConflict, got %v", err)
	}
	err = s.Tx(ctx, func(r Repo) error {
		if err := r.CreateDevice(ctx, Device{ID: "d_1", UserID: "u_1", Name: "p", CreatedAt: 1, LastSeenAt: 1}); err != nil {
			return err
		}
		if err := r.InsertRefreshToken(ctx, RefreshToken{Hash: "h", DeviceID: "d_1", ExpiresAt: 99, CreatedAt: 1}); err != nil {
			return err
		}
		return r.UpsertRecord(ctx, "u_1", Record{Collection: "answer", Key: "k", Value: []byte(`{}`), Clock: 1, Device: "d_1", Seq: 1}, 1)
	})
	if err != nil {
		t.Fatal(err)
	}
	// Device for a missing user violates the foreign key.
	if err := s.Tx(ctx, func(r Repo) error {
		return r.CreateDevice(ctx, Device{ID: "d_x", UserID: "u_missing", Name: "p"})
	}); err == nil {
		t.Fatal("foreign key not enforced")
	}
	if err := s.Tx(ctx, func(r Repo) error { return r.DeleteUser(ctx, "u_1") }); err != nil {
		t.Fatal(err)
	}
	r := s.Read()
	if _, err := r.DeviceByID(ctx, "d_1"); !errors.Is(err, ErrNotFound) {
		t.Fatal("device not cascaded")
	}
	if _, err := r.RefreshTokenByHash(ctx, "h"); !errors.Is(err, ErrNotFound) {
		t.Fatal("token not cascaded")
	}
	if recs, _ := r.RecordsAfter(ctx, "u_1", 0, 10); len(recs) != 0 {
		t.Fatal("records not cascaded")
	}
}

func TestRollbackOnError(t *testing.T) {
	ctx := context.Background()
	s, _ := openTest(t)
	boom := errors.New("boom")
	err := s.Tx(ctx, func(r Repo) error {
		if err := r.CreateUser(ctx, User{ID: "u_1", Username: "bob", PasswordHash: "x"}); err != nil {
			return err
		}
		return boom
	})
	if !errors.Is(err, boom) {
		t.Fatal(err)
	}
	if _, err := s.Read().UserByID(ctx, "u_1"); !errors.Is(err, ErrNotFound) {
		t.Fatal("not rolled back")
	}
}

func TestRotatedTokenGuard(t *testing.T) {
	ctx := context.Background()
	s, _ := openTest(t)
	err := s.Tx(ctx, func(r Repo) error {
		_ = r.CreateUser(ctx, User{ID: "u_1", Username: "c", PasswordHash: "x"})
		_ = r.CreateDevice(ctx, Device{ID: "d_1", UserID: "u_1", Name: "p"})
		_ = r.InsertRefreshToken(ctx, RefreshToken{Hash: "h", DeviceID: "d_1", ExpiresAt: 10})
		if err := r.MarkRefreshTokenRotated(ctx, "h", 5, "h2"); err != nil {
			return err
		}
		if err := r.MarkRefreshTokenRotated(ctx, "h", 6, "h3"); !errors.Is(err, ErrNotFound) {
			t.Errorf("second rotation should fail, got %v", err)
		}
		n, err := r.DeleteExpiredRefreshTokens(ctx, 10)
		if n != 1 {
			t.Errorf("purged %d", n)
		}
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
}

func TestBackupTo(t *testing.T) {
	ctx := context.Background()
	s, p := openTest(t)
	if err := s.Tx(ctx, func(r Repo) error { return r.CreateUser(ctx, User{ID: "u_1", Username: "bk", PasswordHash: "x"}) }); err != nil {
		t.Fatal(err)
	}
	dest := filepath.Join(filepath.Dir(p), "backup.db")
	if err := s.BackupTo(ctx, dest); err != nil {
		t.Fatal(err)
	}
	if err := s.BackupTo(ctx, dest); err == nil {
		t.Fatal("overwrote existing backup")
	}
	b, err := Open(ctx, dest)
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	if u, err := b.Read().UserByID(ctx, "u_1"); err != nil || u.Username != "bk" {
		t.Fatalf("backup content: %+v %v", u, err)
	}
}

func TestCreateDeviceForMissingUserIsNotFound(t *testing.T) {
	ctx := context.Background()
	s, _ := openTest(t)
	err := s.Tx(ctx, func(r Repo) error {
		return r.CreateDevice(ctx, Device{ID: "d_x", UserID: "u_gone", Name: "n", CreatedAt: 1, LastSeenAt: 1})
	})
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("want ErrNotFound, got %v", err)
	}
}
