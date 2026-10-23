// Package auth implements accounts, devices, password hashing and the
// access/refresh token lifecycle (including refresh rotation with reuse
// detection).
package auth

import (
	"context"
	"errors"
	"log/slog"
	"runtime"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/listenly/listenly-server/internal/store"
)

// Service errors; the HTTP layer maps them to protocol error codes.
var (
	ErrBadUsername         = errors.New("bad_username")
	ErrWeakPassword        = errors.New("weak_password")
	ErrBadDeviceName       = errors.New("bad_request")
	ErrUsernameTaken       = errors.New("username_taken")
	ErrRegistrationClosed  = errors.New("registration_closed")
	ErrInvalidCredentials  = errors.New("invalid_credentials")
	ErrInvalidRefreshToken = errors.New("invalid_refresh_token")
	ErrUnauthorized        = errors.New("unauthorized")
	ErrNotFound            = errors.New("not_found")
)

// Options configure a Service.
type Options struct {
	Secret            []byte
	AllowRegistration bool
	Params            Params           // zero value → DefaultParams
	MaxHashing        int              // concurrent Argon2 computations; 0 → min(4, NumCPU)
	Now               func() time.Time // nil → time.Now
	Logger            *slog.Logger
}

// Service is the account/token service.
type Service struct {
	st         *store.Store
	secret     []byte
	allowReg   bool
	params     Params
	hashSem    chan struct{}
	now        func() time.Time
	log        *slog.Logger
	dummyHash  string
	touchEvery int64
}

// New creates a Service.
func New(st *store.Store, o Options) (*Service, error) {
	if len(o.Secret) < 32 {
		return nil, errors.New("auth: secret must be at least 32 bytes")
	}
	if o.Params == (Params{}) {
		o.Params = DefaultParams
	}
	if o.MaxHashing <= 0 {
		o.MaxHashing = max(1, min(4, runtime.NumCPU()))
	}
	if o.Now == nil {
		o.Now = time.Now
	}
	if o.Logger == nil {
		o.Logger = slog.Default()
	}
	s := &Service{
		st: st, secret: o.Secret, allowReg: o.AllowRegistration, params: o.Params,
		hashSem: make(chan struct{}, o.MaxHashing), now: o.Now, log: o.Logger, touchEvery: 60,
	}
	// Used to spend equal time when a username does not exist, so login
	// timing does not reveal which usernames are registered.
	var err error
	if s.dummyHash, err = HashPassword(NewID("dummy"), o.Params); err != nil {
		return nil, err
	}
	return s, nil
}

// Session is what register/login/refresh return to the client.
type Session struct {
	UserID           string
	DeviceID         string
	AccessToken      string
	AccessExpiresAt  time.Time
	RefreshToken     string
	RefreshExpiresAt time.Time
}

// Identity is an authenticated (user, device) pair.
type Identity struct {
	UserID   string
	DeviceID string
}

func (s *Service) hash(password string) (string, error) {
	s.hashSem <- struct{}{}
	defer func() { <-s.hashSem }()
	return HashPassword(password, s.params)
}

func (s *Service) verify(password, encoded string) bool {
	s.hashSem <- struct{}{}
	defer func() { <-s.hashSem }()
	ok, err := VerifyPassword(password, encoded)
	if err != nil {
		s.log.Error("password hash verification failed", "err", err)
		return false
	}
	return ok
}

// NormalizeUsername lower-cases ASCII letters and validates the result
// against [a-z0-9_.-]{3,32}. Non-ASCII input is rejected (not folded).
func NormalizeUsername(u string) (string, bool) {
	if len(u) < 3 || len(u) > 32 {
		return "", false
	}
	b := []byte(u)
	for i, c := range b {
		switch {
		case c >= 'A' && c <= 'Z':
			b[i] = c + ('a' - 'A')
		case c >= 'a' && c <= 'z', c >= '0' && c <= '9', c == '_', c == '.', c == '-':
		default:
			return "", false
		}
	}
	return string(b), true
}

func validPassword(p string) bool { return len(p) >= 8 && len(p) <= 128 }

func validDeviceName(n string) bool {
	if !utf8.ValidString(n) {
		return false
	}
	c := utf8.RuneCountInString(n)
	if c < 1 || c > 64 {
		return false
	}
	for _, r := range n {
		if unicode.IsControl(r) {
			return false
		}
	}
	return true
}

// Register creates a user plus its first device and returns a session.
func (s *Service) Register(ctx context.Context, username, password, deviceName string) (Session, error) {
	if !s.allowReg {
		return Session{}, ErrRegistrationClosed
	}
	uname, ok := NormalizeUsername(username)
	if !ok {
		return Session{}, ErrBadUsername
	}
	if !validPassword(password) {
		return Session{}, ErrWeakPassword
	}
	if !validDeviceName(deviceName) {
		return Session{}, ErrBadDeviceName
	}
	if _, err := s.st.Read().UserByUsername(ctx, uname); err == nil {
		return Session{}, ErrUsernameTaken // cheap pre-check before spending an Argon2 hash
	} else if !errors.Is(err, store.ErrNotFound) {
		return Session{}, err
	}
	ph, err := s.hash(password)
	if err != nil {
		return Session{}, err
	}
	now := s.now()
	var sess Session
	err = s.st.Tx(ctx, func(r store.Repo) error {
		u := store.User{ID: NewID("u_"), Username: uname, PasswordHash: ph, CreatedAt: now.Unix()}
		if err := r.CreateUser(ctx, u); err != nil {
			if errors.Is(err, store.ErrConflict) {
				return ErrUsernameTaken
			}
			return err
		}
		sess, err = s.newDeviceSession(ctx, r, u.ID, deviceName, now)
		return err
	})
	return sess, err
}

// Login verifies credentials and creates a new device.
func (s *Service) Login(ctx context.Context, username, password, deviceName string) (Session, error) {
	uname, okName := NormalizeUsername(username)
	if !validDeviceName(deviceName) {
		return Session{}, ErrBadDeviceName
	}
	var u store.User
	var err error
	if okName && validPassword(password) {
		u, err = s.st.Read().UserByUsername(ctx, uname)
		if err != nil && !errors.Is(err, store.ErrNotFound) {
			return Session{}, err
		}
	}
	if u.ID == "" {
		s.verify(password, s.dummyHash)
		return Session{}, ErrInvalidCredentials
	}
	if !s.verify(password, u.PasswordHash) {
		return Session{}, ErrInvalidCredentials
	}
	now := s.now()
	var sess Session
	err = s.st.Tx(ctx, func(r store.Repo) error {
		var err error
		sess, err = s.newDeviceSession(ctx, r, u.ID, deviceName, now)
		return err
	})
	if errors.Is(err, store.ErrNotFound) {
		return Session{}, ErrInvalidCredentials // account deleted concurrently
	}
	return sess, err
}

func (s *Service) newDeviceSession(ctx context.Context, r store.Repo, userID, deviceName string, now time.Time) (Session, error) {
	d := store.Device{ID: NewID("d_"), UserID: userID, Name: deviceName, CreatedAt: now.Unix(), LastSeenAt: now.Unix()}
	if err := r.CreateDevice(ctx, d); err != nil {
		return Session{}, err
	}
	sess, _, err := s.issue(ctx, r, userID, d.ID, now)
	return sess, err
}

// issue mints a new access/refresh pair for the device and returns it with
// the new refresh token's hash and the pair's generation.
func (s *Service) issue(ctx context.Context, r store.Repo, userID, deviceID string, now time.Time) (Session, issued, error) {
	gen, err := r.NextTokenGen(ctx, deviceID)
	if err != nil {
		return Session{}, issued{}, err
	}
	at, atExp, err := signAccess(s.secret, userID, deviceID, gen, now)
	if err != nil {
		return Session{}, issued{}, err
	}
	rt, rh, err := newRefreshToken()
	if err != nil {
		return Session{}, issued{}, err
	}
	rtExp := now.Truncate(time.Second).Add(RefreshTTL)
	if err := r.InsertRefreshToken(ctx, store.RefreshToken{Hash: rh, DeviceID: deviceID, ExpiresAt: rtExp.Unix(), CreatedAt: now.Unix()}); err != nil {
		return Session{}, issued{}, err
	}
	return Session{
		UserID: userID, DeviceID: deviceID,
		AccessToken: at, AccessExpiresAt: atExp.UTC(),
		RefreshToken: rt, RefreshExpiresAt: rtExp.UTC(),
	}, issued{hash: rh, gen: gen}, nil
}

type issued struct {
	hash string
	gen  int64
}

// Refresh exchanges a refresh token for a new session on the same device.
//
// Normal case: the presented token is unrotated; it is marked rotated
// (recording its successor) and a new pair is issued.
//
// Grace window: if the presented token is the one most recently rotated
// away — its recorded successor is still unrotated — and it was rotated at
// most RefreshGrace ago, the client is assumed to have lost the previous
// response. The successor pair is invalidated (refresh token deleted, its
// access token rejected via min_access_gen) and a fresh pair is issued, so
// at most one live successor exists. The window is measured from the
// original rotation and is not extended by retries.
//
// Anything else that presents a rotated token (outside the window, or an
// older ancestor whose successor was itself rotated) is treated as theft:
// the device is revoked, invalidating all of its refresh and access tokens.
//
// All of this runs in one write transaction; the store serialises write
// transactions, so concurrent retries are applied one after another and
// each supersedes the previous one.
func (s *Service) Refresh(ctx context.Context, token string) (Session, error) {
	if token == "" || len(token) > 256 {
		return Session{}, ErrInvalidRefreshToken
	}
	h := hashRefresh(token)
	now := s.now()
	var sess Session
	var reused, graced bool
	var deviceID string
	err := s.st.Tx(ctx, func(r store.Repo) error {
		t, err := r.RefreshTokenByHash(ctx, h)
		if errors.Is(err, store.ErrNotFound) {
			return ErrInvalidRefreshToken
		} else if err != nil {
			return err
		}
		if t.ExpiresAt <= now.Unix() {
			return ErrInvalidRefreshToken
		}
		deviceID = t.DeviceID
		d, err := r.DeviceByID(ctx, t.DeviceID)
		if errors.Is(err, store.ErrNotFound) || (err == nil && d.RevokedAt.Valid) {
			return ErrInvalidRefreshToken
		} else if err != nil {
			return err
		}

		if t.RotatedAt.Valid {
			if !s.inGrace(ctx, r, t, now) {
				reused = true
				return r.RevokeDevice(ctx, t.DeviceID, now.Unix()) // commit the revocation
			}
			graced = true
			if err := r.DeleteRefreshToken(ctx, t.ReplacedBy.String); err != nil {
				return err
			}
			var iss issued
			if sess, iss, err = s.issue(ctx, r, d.UserID, d.ID, now); err != nil {
				return err
			}
			// Every access token issued before this pair (including the
			// superseded successor's) is now invalid.
			if err := r.SetMinAccessGen(ctx, d.ID, iss.gen); err != nil {
				return err
			}
			if err := r.SetRefreshTokenSuccessor(ctx, h, iss.hash); err != nil {
				return err
			}
			return r.TouchDevice(ctx, d.ID, now.Unix(), 0)
		}

		var iss issued
		if sess, iss, err = s.issue(ctx, r, d.UserID, d.ID, now); err != nil {
			return err
		}
		if err := r.MarkRefreshTokenRotated(ctx, h, now.Unix(), iss.hash); err != nil {
			if errors.Is(err, store.ErrNotFound) {
				return ErrInvalidRefreshToken
			}
			return err
		}
		return r.TouchDevice(ctx, d.ID, now.Unix(), 0)
	})
	if err != nil {
		return Session{}, err
	}
	if reused {
		s.log.Warn("refresh token reuse detected; device revoked", "device", deviceID)
		return Session{}, ErrInvalidRefreshToken
	}
	if graced {
		s.log.Info("refresh retried within grace window; previous pair superseded", "device", deviceID)
	}
	return sess, nil
}

// inGrace reports whether rotated token t may be replayed as a retry: it
// was rotated at most RefreshGrace ago and its successor is still the live
// (unrotated) token, i.e. t is the most recently rotated-away token.
func (s *Service) inGrace(ctx context.Context, r store.Repo, t store.RefreshToken, now time.Time) bool {
	if !t.ReplacedBy.Valid || now.Sub(time.Unix(t.RotatedAt.Int64, 0)) > RefreshGrace {
		return false
	}
	succ, err := r.RefreshTokenByHash(ctx, t.ReplacedBy.String)
	if err != nil || succ.RotatedAt.Valid || succ.DeviceID != t.DeviceID {
		return false
	}
	return true
}

// Authenticate validates a bearer access token and that its device is
// still active.
func (s *Service) Authenticate(ctx context.Context, token string) (Identity, error) {
	uid, did, gen, err := parseAccess(s.secret, token, s.now)
	if err != nil {
		return Identity{}, ErrUnauthorized
	}
	d, err := s.st.Read().DeviceByID(ctx, did)
	if errors.Is(err, store.ErrNotFound) {
		return Identity{}, ErrUnauthorized
	} else if err != nil {
		return Identity{}, err
	}
	if d.UserID != uid || d.RevokedAt.Valid || gen < d.MinAccessGen {
		return Identity{}, ErrUnauthorized
	}
	now := s.now().Unix()
	if now-d.LastSeenAt >= s.touchEvery {
		if err := s.st.Tx(ctx, func(r store.Repo) error { return r.TouchDevice(ctx, did, now, s.touchEvery) }); err != nil {
			s.log.Warn("update last_seen_at failed", "err", err)
		}
	}
	return Identity{UserID: uid, DeviceID: did}, nil
}

// Logout deletes the current device (and all its refresh tokens).
func (s *Service) Logout(ctx context.Context, id Identity) error {
	err := s.st.Tx(ctx, func(r store.Repo) error { return r.DeleteDevice(ctx, id.UserID, id.DeviceID) })
	if errors.Is(err, store.ErrNotFound) {
		return nil // already gone; logout is idempotent
	}
	return err
}

// Account is the /v1/me view.
type Account struct {
	UserID   string
	Username string
	Devices  []store.Device
}

// Me returns the account and its active devices.
func (s *Service) Me(ctx context.Context, id Identity) (Account, error) {
	r := s.st.Read()
	u, err := r.UserByID(ctx, id.UserID)
	if errors.Is(err, store.ErrNotFound) {
		return Account{}, ErrUnauthorized
	} else if err != nil {
		return Account{}, err
	}
	ds, err := r.ListActiveDevices(ctx, id.UserID)
	if err != nil {
		return Account{}, err
	}
	return Account{UserID: u.ID, Username: u.Username, Devices: ds}, nil
}

// DeleteDevice removes one of the caller's devices.
func (s *Service) DeleteDevice(ctx context.Context, id Identity, deviceID string) error {
	err := s.st.Tx(ctx, func(r store.Repo) error { return r.DeleteDevice(ctx, id.UserID, deviceID) })
	if errors.Is(err, store.ErrNotFound) {
		return ErrNotFound
	}
	return err
}

// DeleteAccount permanently deletes the account after re-checking the password.
func (s *Service) DeleteAccount(ctx context.Context, id Identity, password string) error {
	u, err := s.st.Read().UserByID(ctx, id.UserID)
	if errors.Is(err, store.ErrNotFound) {
		return ErrUnauthorized
	} else if err != nil {
		return err
	}
	if len(password) > 128 || !s.verify(password, u.PasswordHash) {
		return ErrInvalidCredentials
	}
	err = s.st.Tx(ctx, func(r store.Repo) error { return r.DeleteUser(ctx, id.UserID) })
	if errors.Is(err, store.ErrNotFound) {
		return nil
	}
	return err
}

// PurgeExpired deletes expired refresh tokens.
func (s *Service) PurgeExpired(ctx context.Context) (int64, error) {
	var n int64
	err := s.st.Tx(ctx, func(r store.Repo) error {
		var err error
		n, err = r.DeleteExpiredRefreshTokens(ctx, s.now().Unix())
		return err
	})
	return n, err
}
