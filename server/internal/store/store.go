// Package store owns the SQLite database: connection setup, embedded
// versioned migrations and a plain-SQL repository.
package store

import (
	"context"
	"database/sql"
	"embed"
	"errors"
	"fmt"
	"io/fs"
	"net/url"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"time"

	_ "modernc.org/sqlite" // pure-Go SQLite driver ("sqlite")
)

//go:embed migrations/*.sql
var migrationFS embed.FS

// ErrNotFound is returned when a row does not exist.
var ErrNotFound = errors.New("store: not found")

// ErrConflict is returned on a uniqueness violation (e.g. username taken).
var ErrConflict = errors.New("store: conflict")

// Store holds two pools over the same file: a single-connection writer
// (SQLite allows one writer at a time; serialising in-process avoids
// SQLITE_BUSY churn) whose transactions start with BEGIN IMMEDIATE, and a
// multi-connection reader pool that benefits from WAL concurrent reads.
type Store struct {
	writer *sql.DB
	reader *sql.DB
}

func dsn(path string, immediate bool) string {
	q := url.Values{}
	q.Add("_pragma", "busy_timeout(10000)")
	q.Add("_pragma", "journal_mode(WAL)")
	q.Add("_pragma", "foreign_keys(1)")
	q.Add("_pragma", "synchronous(NORMAL)")
	if immediate {
		q.Set("_txlock", "immediate")
	}
	return "file:" + path + "?" + q.Encode()
}

// Open opens (creating if needed) the database at path and applies
// pending migrations.
func Open(ctx context.Context, path string) (*Store, error) {
	w, err := sql.Open("sqlite", dsn(path, true))
	if err != nil {
		return nil, err
	}
	w.SetMaxOpenConns(1)
	w.SetMaxIdleConns(1)
	w.SetConnMaxLifetime(0)
	if err := w.PingContext(ctx); err != nil {
		w.Close()
		return nil, fmt.Errorf("open database: %w", err)
	}
	s := &Store{writer: w}
	if err := s.migrate(ctx); err != nil {
		w.Close()
		return nil, err
	}
	r, err := sql.Open("sqlite", dsn(path, false))
	if err != nil {
		w.Close()
		return nil, err
	}
	n := max(4, runtime.NumCPU())
	r.SetMaxOpenConns(n)
	r.SetMaxIdleConns(n)
	s.reader = r
	return s, nil
}

// Close closes both pools.
func (s *Store) Close() error {
	return errors.Join(s.reader.Close(), s.writer.Close())
}

// Ping checks database availability.
func (s *Store) Ping(ctx context.Context) error {
	var one int
	return s.reader.QueryRowContext(ctx, "SELECT 1").Scan(&one)
}

// Read returns a repository bound to the reader pool (no transaction).
func (s *Store) Read() Repo { return Repo{q: s.reader} }

// Tx runs fn inside a write transaction (BEGIN IMMEDIATE). The transaction
// is committed if fn returns nil and rolled back otherwise.
func (s *Store) Tx(ctx context.Context, fn func(Repo) error) error {
	tx, err := s.writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	if err := fn(Repo{q: tx}); err != nil {
		_ = tx.Rollback()
		return err
	}
	return tx.Commit()
}

type migration struct {
	version int
	name    string
	sql     string
}

func loadMigrations() ([]migration, error) {
	entries, err := fs.ReadDir(migrationFS, "migrations")
	if err != nil {
		return nil, err
	}
	var ms []migration
	for _, e := range entries {
		name := e.Name()
		if !strings.HasSuffix(name, ".sql") {
			continue
		}
		prefix, _, ok := strings.Cut(name, "_")
		if !ok {
			return nil, fmt.Errorf("migration %s: name must be NNNN_description.sql", name)
		}
		v, err := strconv.Atoi(prefix)
		if err != nil || v <= 0 {
			return nil, fmt.Errorf("migration %s: bad version", name)
		}
		body, err := fs.ReadFile(migrationFS, "migrations/"+name)
		if err != nil {
			return nil, err
		}
		ms = append(ms, migration{version: v, name: name, sql: string(body)})
	}
	sort.Slice(ms, func(i, j int) bool { return ms[i].version < ms[j].version })
	for i := 1; i < len(ms); i++ {
		if ms[i].version == ms[i-1].version {
			return nil, fmt.Errorf("duplicate migration version %d", ms[i].version)
		}
	}
	return ms, nil
}

func (s *Store) migrate(ctx context.Context) error {
	ms, err := loadMigrations()
	if err != nil {
		return err
	}
	if _, err := s.writer.ExecContext(ctx, `CREATE TABLE IF NOT EXISTS schema_migrations (
		version INTEGER PRIMARY KEY, name TEXT NOT NULL, applied_at INTEGER NOT NULL) STRICT`); err != nil {
		return fmt.Errorf("create schema_migrations: %w", err)
	}
	for _, m := range ms {
		err := s.Tx(ctx, func(r Repo) error {
			var n int
			if err := r.q.QueryRowContext(ctx, `SELECT COUNT(*) FROM schema_migrations WHERE version = ?`, m.version).Scan(&n); err != nil {
				return err
			}
			if n > 0 {
				return nil
			}
			if _, err := r.q.ExecContext(ctx, m.sql); err != nil {
				return err
			}
			_, err := r.q.ExecContext(ctx, `INSERT INTO schema_migrations(version, name, applied_at) VALUES (?, ?, ?)`,
				m.version, m.name, time.Now().Unix())
			return err
		})
		if err != nil {
			return fmt.Errorf("apply migration %s: %w", m.name, err)
		}
	}
	return nil
}

// SchemaVersion returns the highest applied migration version.
func (s *Store) SchemaVersion(ctx context.Context) (int, error) {
	var v sql.NullInt64
	err := s.reader.QueryRowContext(ctx, `SELECT MAX(version) FROM schema_migrations`).Scan(&v)
	return int(v.Int64), err
}

// BackupTo writes a consistent, self-contained snapshot of the database to
// dest (which must not exist) using VACUUM INTO. Safe while the server runs.
func (s *Store) BackupTo(ctx context.Context, dest string) error {
	_, err := s.writer.ExecContext(ctx, `VACUUM INTO ?`, dest)
	return err
}
