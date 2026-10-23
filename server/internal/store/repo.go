package store

import (
	"context"
	"database/sql"
	"errors"
	"strings"
)

type querier interface {
	ExecContext(ctx context.Context, query string, args ...any) (sql.Result, error)
	QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error)
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
}

// Repo exposes plain-SQL data access. It is bound either to the reader
// pool or to a write transaction (see Store.Tx).
type Repo struct{ q querier }

// User is a row of the users table.
type User struct {
	ID           string
	Username     string
	PasswordHash string
	Seq          int64
	CreatedAt    int64
}

// Device is a row of the devices table.
type Device struct {
	ID         string
	UserID     string
	Name       string
	CreatedAt  int64
	LastSeenAt int64
	RevokedAt  sql.NullInt64
	// MinAccessGen: access tokens whose "gen" claim is lower are rejected.
	MinAccessGen int64
}

// RefreshToken is a row of the refresh_tokens table.
type RefreshToken struct {
	Hash      string
	DeviceID  string
	ExpiresAt int64
	RotatedAt sql.NullInt64
	CreatedAt int64
	// ReplacedBy is the hash of the successor issued when this token was rotated.
	ReplacedBy sql.NullString
}

// Record is a stored sync record.
type Record struct {
	Collection string
	Key        string
	Value      []byte // compact JSON, nil means null
	Deleted    bool
	Clock      int64
	Device     string
	Seq        int64
}

func isUnique(err error) bool {
	return err != nil && strings.Contains(err.Error(), "UNIQUE constraint failed")
}

func notFound(err error) error {
	if errors.Is(err, sql.ErrNoRows) {
		return ErrNotFound
	}
	return err
}

// --- users ---

// CreateUser inserts a user; ErrConflict if the username exists.
func (r Repo) CreateUser(ctx context.Context, u User) error {
	_, err := r.q.ExecContext(ctx, `INSERT INTO users(id, username, password_hash, seq, created_at) VALUES (?, ?, ?, 0, ?)`,
		u.ID, u.Username, u.PasswordHash, u.CreatedAt)
	if isUnique(err) {
		return ErrConflict
	}
	return err
}

const userCols = `id, username, password_hash, seq, created_at`

func scanUser(row *sql.Row) (User, error) {
	var u User
	err := row.Scan(&u.ID, &u.Username, &u.PasswordHash, &u.Seq, &u.CreatedAt)
	return u, notFound(err)
}

// UserByUsername looks a user up by (lower-case) username.
func (r Repo) UserByUsername(ctx context.Context, username string) (User, error) {
	return scanUser(r.q.QueryRowContext(ctx, `SELECT `+userCols+` FROM users WHERE username = ?`, username))
}

// UserByID looks a user up by id.
func (r Repo) UserByID(ctx context.Context, id string) (User, error) {
	return scanUser(r.q.QueryRowContext(ctx, `SELECT `+userCols+` FROM users WHERE id = ?`, id))
}

// DeleteUser removes a user and, via ON DELETE CASCADE, its devices,
// refresh tokens and records.
func (r Repo) DeleteUser(ctx context.Context, id string) error {
	res, err := r.q.ExecContext(ctx, `DELETE FROM users WHERE id = ?`, id)
	return affected(res, err)
}

// SetUserSeq stores the user's highest assigned seq.
func (r Repo) SetUserSeq(ctx context.Context, id string, seq int64) error {
	res, err := r.q.ExecContext(ctx, `UPDATE users SET seq = ? WHERE id = ?`, seq, id)
	return affected(res, err)
}

func affected(res sql.Result, err error) error {
	if err != nil {
		return err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if n == 0 {
		return ErrNotFound
	}
	return nil
}

// --- devices ---

// CreateDevice inserts a device.
// It returns ErrNotFound when the user no longer exists (e.g. the account was deleted concurrently).
func (r Repo) CreateDevice(ctx context.Context, d Device) error {
	_, err := r.q.ExecContext(ctx, `INSERT INTO devices(id, user_id, name, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?)`,
		d.ID, d.UserID, d.Name, d.CreatedAt, d.LastSeenAt)
	if err != nil && strings.Contains(err.Error(), "FOREIGN KEY constraint failed") {
		return ErrNotFound
	}
	return err
}

// DeviceByID returns a device.
func (r Repo) DeviceByID(ctx context.Context, id string) (Device, error) {
	var d Device
	err := r.q.QueryRowContext(ctx, `SELECT id, user_id, name, created_at, last_seen_at, revoked_at, min_access_gen FROM devices WHERE id = ?`, id).
		Scan(&d.ID, &d.UserID, &d.Name, &d.CreatedAt, &d.LastSeenAt, &d.RevokedAt, &d.MinAccessGen)
	return d, notFound(err)
}

// ListActiveDevices returns the user's non-revoked devices, oldest first.
func (r Repo) ListActiveDevices(ctx context.Context, userID string) ([]Device, error) {
	rows, err := r.q.QueryContext(ctx, `SELECT id, user_id, name, created_at, last_seen_at, revoked_at, min_access_gen FROM devices
		WHERE user_id = ? AND revoked_at IS NULL ORDER BY created_at, id`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Device
	for rows.Next() {
		var d Device
		if err := rows.Scan(&d.ID, &d.UserID, &d.Name, &d.CreatedAt, &d.LastSeenAt, &d.RevokedAt, &d.MinAccessGen); err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

// DeleteDevice deletes a device owned by userID (cascading its tokens).
func (r Repo) DeleteDevice(ctx context.Context, userID, deviceID string) error {
	res, err := r.q.ExecContext(ctx, `DELETE FROM devices WHERE id = ? AND user_id = ?`, deviceID, userID)
	return affected(res, err)
}

// TouchDevice updates last_seen_at if it is older than minAge seconds.
func (r Repo) TouchDevice(ctx context.Context, deviceID string, now, minAge int64) error {
	_, err := r.q.ExecContext(ctx, `UPDATE devices SET last_seen_at = ? WHERE id = ? AND last_seen_at <= ?`,
		now, deviceID, now-minAge)
	return err
}

// RevokeDevice marks the device revoked and deletes all its refresh tokens.
func (r Repo) RevokeDevice(ctx context.Context, deviceID string, now int64) error {
	if _, err := r.q.ExecContext(ctx, `UPDATE devices SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL`, now, deviceID); err != nil {
		return err
	}
	_, err := r.q.ExecContext(ctx, `DELETE FROM refresh_tokens WHERE device_id = ?`, deviceID)
	return err
}

// NextTokenGen increments and returns the device's token generation.
func (r Repo) NextTokenGen(ctx context.Context, deviceID string) (int64, error) {
	var g int64
	err := r.q.QueryRowContext(ctx, `UPDATE devices SET token_gen = token_gen + 1 WHERE id = ? RETURNING token_gen`, deviceID).Scan(&g)
	return g, notFound(err)
}

// SetMinAccessGen rejects all of the device's access tokens with gen < g.
func (r Repo) SetMinAccessGen(ctx context.Context, deviceID string, g int64) error {
	res, err := r.q.ExecContext(ctx, `UPDATE devices SET min_access_gen = MAX(min_access_gen, ?) WHERE id = ?`, g, deviceID)
	return affected(res, err)
}

// --- refresh tokens ---

// InsertRefreshToken stores a refresh token hash.
func (r Repo) InsertRefreshToken(ctx context.Context, t RefreshToken) error {
	_, err := r.q.ExecContext(ctx, `INSERT INTO refresh_tokens(hash, device_id, expires_at, created_at) VALUES (?, ?, ?, ?)`,
		t.Hash, t.DeviceID, t.ExpiresAt, t.CreatedAt)
	return err
}

// RefreshTokenByHash returns a refresh token row.
func (r Repo) RefreshTokenByHash(ctx context.Context, hash string) (RefreshToken, error) {
	var t RefreshToken
	err := r.q.QueryRowContext(ctx, `SELECT hash, device_id, expires_at, rotated_at, created_at, replaced_by FROM refresh_tokens WHERE hash = ?`, hash).
		Scan(&t.Hash, &t.DeviceID, &t.ExpiresAt, &t.RotatedAt, &t.CreatedAt, &t.ReplacedBy)
	return t, notFound(err)
}

// MarkRefreshTokenRotated marks a token as used and records its successor.
// It returns ErrNotFound if the token was already rotated.
func (r Repo) MarkRefreshTokenRotated(ctx context.Context, hash string, now int64, replacedBy string) error {
	res, err := r.q.ExecContext(ctx, `UPDATE refresh_tokens SET rotated_at = ?, replaced_by = ? WHERE hash = ? AND rotated_at IS NULL`,
		now, replacedBy, hash)
	return affected(res, err)
}

// SetRefreshTokenSuccessor re-points an already rotated token at a new
// successor without changing its rotated_at (grace-window retry).
func (r Repo) SetRefreshTokenSuccessor(ctx context.Context, hash, replacedBy string) error {
	res, err := r.q.ExecContext(ctx, `UPDATE refresh_tokens SET replaced_by = ? WHERE hash = ? AND rotated_at IS NOT NULL`, replacedBy, hash)
	return affected(res, err)
}

// DeleteRefreshToken removes a single token.
func (r Repo) DeleteRefreshToken(ctx context.Context, hash string) error {
	_, err := r.q.ExecContext(ctx, `DELETE FROM refresh_tokens WHERE hash = ?`, hash)
	return err
}

// LiveRefreshTokens counts the device's unrotated, unexpired refresh tokens.
func (r Repo) LiveRefreshTokens(ctx context.Context, deviceID string, now int64) (int, error) {
	var n int
	err := r.q.QueryRowContext(ctx, `SELECT COUNT(*) FROM refresh_tokens WHERE device_id = ? AND rotated_at IS NULL AND expires_at > ?`,
		deviceID, now).Scan(&n)
	return n, err
}

// DeleteExpiredRefreshTokens removes tokens whose expiry has passed.
func (r Repo) DeleteExpiredRefreshTokens(ctx context.Context, now int64) (int64, error) {
	res, err := r.q.ExecContext(ctx, `DELETE FROM refresh_tokens WHERE expires_at <= ?`, now)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}

// --- records ---

// RecordVersion returns the LWW version (clock, device) of a stored record.
func (r Repo) RecordVersion(ctx context.Context, userID, collection, key string) (clock int64, device string, err error) {
	err = r.q.QueryRowContext(ctx, `SELECT clock, device FROM records WHERE user_id = ? AND collection = ? AND key = ?`,
		userID, collection, key).Scan(&clock, &device)
	return clock, device, notFound(err)
}

// UpsertRecord inserts or replaces a record (rec.Seq must already be set).
func (r Repo) UpsertRecord(ctx context.Context, userID string, rec Record, now int64) error {
	var val any
	if rec.Value != nil {
		val = string(rec.Value)
	}
	_, err := r.q.ExecContext(ctx, `INSERT INTO records(user_id, collection, key, value, deleted, clock, device, seq, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(user_id, collection, key) DO UPDATE SET
		  value = excluded.value, deleted = excluded.deleted, clock = excluded.clock,
		  device = excluded.device, seq = excluded.seq, updated_at = excluded.updated_at`,
		userID, rec.Collection, rec.Key, val, rec.Deleted, rec.Clock, rec.Device, rec.Seq, now)
	return err
}

// RecordsAfter returns up to limit records with seq > cursor, seq ascending.
func (r Repo) RecordsAfter(ctx context.Context, userID string, cursor int64, limit int) ([]Record, error) {
	rows, err := r.q.QueryContext(ctx, `SELECT collection, key, value, deleted, clock, device, seq FROM records
		WHERE user_id = ? AND seq > ? ORDER BY seq LIMIT ?`, userID, cursor, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]Record, 0, min(limit, 64))
	for rows.Next() {
		var rec Record
		var val sql.NullString
		if err := rows.Scan(&rec.Collection, &rec.Key, &val, &rec.Deleted, &rec.Clock, &rec.Device, &rec.Seq); err != nil {
			return nil, err
		}
		if val.Valid {
			rec.Value = []byte(val.String)
		}
		out = append(out, rec)
	}
	return out, rows.Err()
}
