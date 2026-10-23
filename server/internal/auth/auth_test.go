package auth

import (
	"context"
	"errors"
	"fmt"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/listenly/listenly-server/internal/store"
)

var testParams = Params{MemoryKiB: 64, Time: 1, Threads: 1, SaltLen: 16, KeyLen: 32}

type fakeClock struct {
	mu sync.Mutex
	t  time.Time
}

func (c *fakeClock) Now() time.Time      { c.mu.Lock(); defer c.mu.Unlock(); return c.t }
func (c *fakeClock) Add(d time.Duration) { c.mu.Lock(); c.t = c.t.Add(d); c.mu.Unlock() }

var secret = []byte("0123456789abcdef0123456789abcdef")

func newSvc(t *testing.T, allowReg bool) (*Service, *fakeClock, *store.Store) {
	t.Helper()
	st, err := store.Open(context.Background(), filepath.Join(t.TempDir(), "a.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	clk := &fakeClock{t: time.Date(2026, 10, 22, 8, 0, 0, 0, time.UTC)}
	s, err := New(st, Options{Secret: secret, AllowRegistration: allowReg, Params: testParams, Now: clk.Now})
	if err != nil {
		t.Fatal(err)
	}
	return s, clk, st
}

func TestPasswordHash(t *testing.T) {
	h, err := HashPassword("correct horse", testParams)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(h, "$argon2id$v=19$m=64,t=1,p=1$") {
		t.Fatalf("hash format: %s", h)
	}
	if ok, _ := VerifyPassword("correct horse", h); !ok {
		t.Fatal("verify failed")
	}
	if ok, _ := VerifyPassword("wrong horse", h); ok {
		t.Fatal("wrong password verified")
	}
	h2, _ := HashPassword("correct horse", testParams)
	if h == h2 {
		t.Fatal("salt not random")
	}
	for _, bad := range []string{"", "$argon2i$v=19$m=64,t=1,p=1$AA$AA", "$argon2id$v=19$m=99999999,t=1,p=1$AA$AA", "plain"} {
		if _, err := VerifyPassword("x", bad); err == nil {
			t.Fatalf("malformed hash accepted: %q", bad)
		}
	}
	// Production defaults match the protocol.
	if DefaultParams.MemoryKiB != 65536 || DefaultParams.Time != 1 || DefaultParams.Threads != 4 {
		t.Fatal("DefaultParams drifted from protocol")
	}
}

func TestNormalizeUsername(t *testing.T) {
	cases := map[string]string{"Listener": "listener", "a_b.c-9": "a_b.c-9", "ABC": "abc"}
	for in, want := range cases {
		if got, ok := NormalizeUsername(in); !ok || got != want {
			t.Errorf("%q → %q,%v", in, got, ok)
		}
	}
	for _, bad := range []string{"ab", strings.Repeat("a", 33), "has space", "ünï", "Kate", "a/b", ""} {
		if _, ok := NormalizeUsername(bad); ok {
			t.Errorf("%q accepted", bad)
		}
	}
}

func TestRegisterLoginValidation(t *testing.T) {
	ctx := context.Background()
	s, _, _ := newSvc(t, true)
	sess, err := s.Register(ctx, "Listener", "password1", "Xiaomi 15")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(sess.UserID, "u_") || !strings.HasPrefix(sess.DeviceID, "d_") {
		t.Fatalf("ids: %+v", sess)
	}
	if sess.AccessExpiresAt.Sub(time.Date(2026, 10, 22, 8, 0, 0, 0, time.UTC)) != 15*time.Minute {
		t.Fatalf("access exp %v", sess.AccessExpiresAt)
	}
	if sess.RefreshExpiresAt.Sub(time.Date(2026, 10, 22, 8, 0, 0, 0, time.UTC)) != 30*24*time.Hour {
		t.Fatalf("refresh exp %v", sess.RefreshExpiresAt)
	}
	if _, err := s.Register(ctx, "LISTENER", "password1", "x"); !errors.Is(err, ErrUsernameTaken) {
		t.Fatalf("dup: %v", err)
	}
	if _, err := s.Register(ctx, "x", "password1", "x"); !errors.Is(err, ErrBadUsername) {
		t.Fatalf("bad username: %v", err)
	}
	if _, err := s.Register(ctx, "newuser", "short", "x"); !errors.Is(err, ErrWeakPassword) {
		t.Fatalf("weak: %v", err)
	}
	if _, err := s.Register(ctx, "newuser", strings.Repeat("p", 129), "x"); !errors.Is(err, ErrWeakPassword) {
		t.Fatalf("long pw: %v", err)
	}
	for _, dn := range []string{"", strings.Repeat("机", 65), "a\nb"} {
		if _, err := s.Register(ctx, "newuser", "password1", dn); !errors.Is(err, ErrBadDeviceName) {
			t.Fatalf("device name %q: %v", dn, err)
		}
	}
	if _, err := s.Register(ctx, "newuser", "password1", strings.Repeat("机", 64)); err != nil {
		t.Fatalf("64-char device name: %v", err)
	}

	l, err := s.Login(ctx, "listener", "password1", "Pixel")
	if err != nil {
		t.Fatal(err)
	}
	if l.UserID != sess.UserID || l.DeviceID == sess.DeviceID {
		t.Fatal("login should create a new device on the same user")
	}
	for _, c := range [][2]string{{"listener", "wrongpass"}, {"nobody", "password1"}, {"x", "password1"}, {"listener", "short"}} {
		if _, err := s.Login(ctx, c[0], c[1], "Pixel"); !errors.Is(err, ErrInvalidCredentials) {
			t.Fatalf("login %v: %v", c, err)
		}
	}
	acc, err := s.Me(ctx, Identity{UserID: sess.UserID, DeviceID: sess.DeviceID})
	if err != nil || len(acc.Devices) != 2 || acc.Username != "listener" {
		t.Fatalf("me: %+v %v", acc, err)
	}
}

func TestRegistrationClosed(t *testing.T) {
	s, _, _ := newSvc(t, false)
	if _, err := s.Register(context.Background(), "someone", "password1", "x"); !errors.Is(err, ErrRegistrationClosed) {
		t.Fatal(err)
	}
}

func TestAccessTokenVerification(t *testing.T) {
	ctx := context.Background()
	s, clk, _ := newSvc(t, true)
	sess, _ := s.Register(ctx, "alice", "password1", "p")
	id, err := s.Authenticate(ctx, sess.AccessToken)
	if err != nil || id.UserID != sess.UserID || id.DeviceID != sess.DeviceID {
		t.Fatalf("auth: %+v %v", id, err)
	}
	// Claims carry sub/did/iat/exp.
	var c accessClaims
	if _, _, err := jwt.NewParser().ParseUnverified(sess.AccessToken, &c); err != nil || c.Subject != sess.UserID || c.DeviceID != sess.DeviceID || c.IssuedAt == nil || c.ExpiresAt == nil {
		t.Fatalf("claims %+v %v", c, err)
	}
	// Wrong secret.
	forged, _, _ := signAccess([]byte("ffffffffffffffffffffffffffffffff"), sess.UserID, sess.DeviceID, 1, clk.Now())
	if _, err := s.Authenticate(ctx, forged); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("forged token accepted")
	}
	// alg=none.
	none, _ := jwt.NewWithClaims(jwt.SigningMethodNone, accessClaims{DeviceID: sess.DeviceID, RegisteredClaims: jwt.RegisteredClaims{
		Subject: sess.UserID, ExpiresAt: jwt.NewNumericDate(clk.Now().Add(time.Hour))}}).SignedString(jwt.UnsafeAllowNoneSignatureType)
	if _, err := s.Authenticate(ctx, none); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("alg none accepted")
	}
	// Token for a device belonging to someone else.
	other, _ := s.Register(ctx, "bob", "password1", "p")
	mixed, _, _ := signAccess(secret, sess.UserID, other.DeviceID, 1, clk.Now())
	if _, err := s.Authenticate(ctx, mixed); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("cross-user device accepted")
	}
	if _, err := s.Authenticate(ctx, "garbage"); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("garbage accepted")
	}
	// Expiry.
	clk.Add(15*time.Minute + time.Second)
	if _, err := s.Authenticate(ctx, sess.AccessToken); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("expired token accepted")
	}
}

func TestRefreshRotationAndReuseDetection(t *testing.T) {
	ctx := context.Background()
	s, clk, _ := newSvc(t, true)
	s0, _ := s.Register(ctx, "alice", "password1", "phone")
	other, _ := s.Login(ctx, "alice", "password1", "tablet")

	clk.Add(time.Minute)
	s1, err := s.Refresh(ctx, s0.RefreshToken)
	if err != nil {
		t.Fatal(err)
	}
	if s1.DeviceID != s0.DeviceID || s1.UserID != s0.UserID || s1.RefreshToken == s0.RefreshToken {
		t.Fatalf("rotation: %+v", s1)
	}
	if !s1.RefreshExpiresAt.After(s0.RefreshExpiresAt) {
		t.Fatal("refresh expiry should slide forward")
	}
	if _, err := s.Authenticate(ctx, s1.AccessToken); err != nil {
		t.Fatal(err)
	}
	s2, err := s.Refresh(ctx, s1.RefreshToken)
	if err != nil {
		t.Fatal(err)
	}

	// Attacker replays the first (rotated) token → reuse detected.
	if _, err := s.Refresh(ctx, s0.RefreshToken); !errors.Is(err, ErrInvalidRefreshToken) {
		t.Fatalf("reuse: %v", err)
	}
	// Every token of that device is now dead: the latest refresh token...
	if _, err := s.Refresh(ctx, s2.RefreshToken); !errors.Is(err, ErrInvalidRefreshToken) {
		t.Fatalf("latest token survived reuse detection: %v", err)
	}
	// ...and outstanding access tokens.
	if _, err := s.Authenticate(ctx, s2.AccessToken); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("access token survived reuse detection")
	}
	// Other devices of the same user are unaffected.
	if _, err := s.Authenticate(ctx, other.AccessToken); err != nil {
		t.Fatalf("other device affected: %v", err)
	}
	if _, err := s.Refresh(ctx, other.RefreshToken); err != nil {
		t.Fatalf("other device refresh: %v", err)
	}
	// Revoked device is hidden from /me.
	acc, _ := s.Me(ctx, Identity{UserID: other.UserID, DeviceID: other.DeviceID})
	if len(acc.Devices) != 1 || acc.Devices[0].ID != other.DeviceID {
		t.Fatalf("devices: %+v", acc.Devices)
	}

	for _, bad := range []string{"", "nope", strings.Repeat("x", 300)} {
		if _, err := s.Refresh(ctx, bad); !errors.Is(err, ErrInvalidRefreshToken) {
			t.Fatalf("bad token %q: %v", bad, err)
		}
	}
}

func TestRefreshExpiry(t *testing.T) {
	ctx := context.Background()
	s, clk, _ := newSvc(t, true)
	s0, _ := s.Register(ctx, "alice", "password1", "phone")
	clk.Add(30*24*time.Hour + time.Second)
	if _, err := s.Refresh(ctx, s0.RefreshToken); !errors.Is(err, ErrInvalidRefreshToken) {
		t.Fatal(err)
	}
	if n, err := s.PurgeExpired(ctx); err != nil || n != 1 {
		t.Fatalf("purge n=%d err=%v", n, err)
	}
}

func liveTokens(t *testing.T, st *store.Store, clk *fakeClock, deviceID string) int {
	t.Helper()
	n, err := st.Read().LiveRefreshTokens(context.Background(), deviceID, clk.Now().Unix())
	if err != nil {
		t.Fatal(err)
	}
	return n
}

func TestRefreshGraceInsideWindow(t *testing.T) {
	ctx := context.Background()
	s, clk, st := newSvc(t, true)
	s0, _ := s.Register(ctx, "alice", "password1", "phone")
	s1, err := s.Refresh(ctx, s0.RefreshToken) // response "lost"
	if err != nil {
		t.Fatal(err)
	}
	clk.Add(RefreshGrace) // exactly at the edge of the window
	s2, err := s.Refresh(ctx, s0.RefreshToken)
	if err != nil {
		t.Fatalf("retry inside window: %v", err)
	}
	if s2.DeviceID != s0.DeviceID || s2.RefreshToken == s1.RefreshToken {
		t.Fatalf("retry session: %+v", s2)
	}
	// The pair from the lost response is dead, without revoking the device.
	if _, err := s.Authenticate(ctx, s1.AccessToken); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("superseded access token still valid")
	}
	if _, err := s.Authenticate(ctx, s0.AccessToken); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("pre-rotation access token still valid after retry")
	}
	if _, err := s.Refresh(ctx, s1.RefreshToken); !errors.Is(err, ErrInvalidRefreshToken) {
		t.Fatal("superseded refresh token still valid")
	}
	if _, err := s.Authenticate(ctx, s2.AccessToken); err != nil {
		t.Fatalf("device was revoked by superseded-token use: %v", err)
	}
	if n := liveTokens(t, st, clk, s0.DeviceID); n != 1 {
		t.Fatalf("live refresh tokens = %d, want 1", n)
	}
	// The new pair keeps working normally.
	s3, err := s.Refresh(ctx, s2.RefreshToken)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.Authenticate(ctx, s3.AccessToken); err != nil {
		t.Fatal(err)
	}
	// Now s0 is an older ancestor (its successor s2 was rotated) → theft.
	if _, err := s.Refresh(ctx, s0.RefreshToken); !errors.Is(err, ErrInvalidRefreshToken) {
		t.Fatal(err)
	}
	if _, err := s.Authenticate(ctx, s3.AccessToken); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("ancestor replay did not revoke the device")
	}
}

func TestRefreshGraceNotExtendedByRetries(t *testing.T) {
	ctx := context.Background()
	s, clk, _ := newSvc(t, true)
	s0, _ := s.Register(ctx, "alice", "password1", "phone")
	if _, err := s.Refresh(ctx, s0.RefreshToken); err != nil {
		t.Fatal(err)
	}
	clk.Add(40 * time.Second)
	last, err := s.Refresh(ctx, s0.RefreshToken)
	if err != nil {
		t.Fatal(err)
	}
	clk.Add(21 * time.Second) // 61s after the original rotation
	if _, err := s.Refresh(ctx, s0.RefreshToken); !errors.Is(err, ErrInvalidRefreshToken) {
		t.Fatal("window was extended by a retry")
	}
	if _, err := s.Authenticate(ctx, last.AccessToken); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("device not revoked")
	}
}

func TestRefreshGraceJustOutsideWindow(t *testing.T) {
	ctx := context.Background()
	s, clk, st := newSvc(t, true)
	s0, _ := s.Register(ctx, "alice", "password1", "phone")
	s1, err := s.Refresh(ctx, s0.RefreshToken)
	if err != nil {
		t.Fatal(err)
	}
	clk.Add(RefreshGrace + time.Second)
	if _, err := s.Refresh(ctx, s0.RefreshToken); !errors.Is(err, ErrInvalidRefreshToken) {
		t.Fatalf("outside window: %v", err)
	}
	if _, err := s.Authenticate(ctx, s1.AccessToken); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("device not revoked outside the window")
	}
	if _, err := s.Refresh(ctx, s1.RefreshToken); !errors.Is(err, ErrInvalidRefreshToken) {
		t.Fatal("successor survived revocation")
	}
	if n := liveTokens(t, st, clk, s0.DeviceID); n != 0 {
		t.Fatalf("live tokens after revocation = %d", n)
	}
}

func TestRefreshOlderAncestorInsideWindow(t *testing.T) {
	ctx := context.Background()
	s, clk, _ := newSvc(t, true)
	s0, _ := s.Register(ctx, "alice", "password1", "phone")
	s1, _ := s.Refresh(ctx, s0.RefreshToken)
	clk.Add(time.Second)
	s2, err := s.Refresh(ctx, s1.RefreshToken)
	if err != nil {
		t.Fatal(err)
	}
	clk.Add(time.Second)
	// s0 was rotated only 2s ago, but it is not the most recent one.
	if _, err := s.Refresh(ctx, s0.RefreshToken); !errors.Is(err, ErrInvalidRefreshToken) {
		t.Fatal(err)
	}
	if _, err := s.Authenticate(ctx, s2.AccessToken); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("older ancestor did not revoke the device")
	}
	// s1 would have been a valid grace retry, but the device is gone now.
	if _, err := s.Refresh(ctx, s1.RefreshToken); !errors.Is(err, ErrInvalidRefreshToken) {
		t.Fatal("refresh after revocation")
	}
}

func TestConcurrentRefreshRetriesInWindow(t *testing.T) {
	ctx := context.Background()
	s, clk, st := newSvc(t, true)
	s0, _ := s.Register(ctx, "alice", "password1", "phone")
	const n = 8
	var wg sync.WaitGroup
	var mu sync.Mutex
	var got []Session
	for range n {
		wg.Add(1)
		go func() {
			defer wg.Done()
			sess, err := s.Refresh(ctx, s0.RefreshToken)
			if err != nil {
				t.Errorf("concurrent retry failed: %v", err)
				return
			}
			mu.Lock()
			got = append(got, sess)
			mu.Unlock()
		}()
	}
	wg.Wait()
	if len(got) != n {
		t.Fatalf("%d/%d succeeded", len(got), n)
	}
	// Exactly one live successor: one refresh token in the DB, and exactly one
	// of the returned access tokens still authenticates.
	if live := liveTokens(t, st, clk, s0.DeviceID); live != 1 {
		t.Fatalf("live refresh tokens = %d, want 1", live)
	}
	valid := 0
	var winner Session
	for _, g := range got {
		if _, err := s.Authenticate(ctx, g.AccessToken); err == nil {
			valid++
			winner = g
		}
	}
	if valid != 1 {
		t.Fatalf("%d access tokens valid, want 1", valid)
	}
	if _, err := s.Refresh(ctx, winner.RefreshToken); err != nil {
		t.Fatalf("winner's refresh token: %v", err)
	}
}

func TestConcurrentFreshTokenRotations(t *testing.T) {
	// Distinct fresh tokens on different devices rotate independently.
	ctx := context.Background()
	s, _, _ := newSvc(t, true)
	var sessions []Session
	first, _ := s.Register(ctx, "alice", "password1", "d0")
	sessions = append(sessions, first)
	for i := range 5 {
		l, err := s.Login(ctx, "alice", "password1", fmt.Sprint("d", i+1))
		if err != nil {
			t.Fatal(err)
		}
		sessions = append(sessions, l)
	}
	var wg sync.WaitGroup
	for _, ss := range sessions {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := s.Refresh(ctx, ss.RefreshToken); err != nil {
				t.Errorf("refresh: %v", err)
			}
		}()
	}
	wg.Wait()
}

func TestLogoutDeleteDeviceDeleteAccount(t *testing.T) {
	ctx := context.Background()
	s, _, st := newSvc(t, true)
	a, _ := s.Register(ctx, "alice", "password1", "phone")
	b, _ := s.Login(ctx, "alice", "password1", "tablet")
	c, _ := s.Login(ctx, "alice", "password1", "laptop")
	mallory, _ := s.Register(ctx, "mallory", "password1", "x")

	idA := Identity{UserID: a.UserID, DeviceID: a.DeviceID}
	if err := s.Logout(ctx, Identity{UserID: b.UserID, DeviceID: b.DeviceID}); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Authenticate(ctx, b.AccessToken); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("logged-out access token still valid")
	}
	if _, err := s.Refresh(ctx, b.RefreshToken); !errors.Is(err, ErrInvalidRefreshToken) {
		t.Fatal("logged-out refresh token still valid")
	}
	// Cannot delete someone else's device.
	if err := s.DeleteDevice(ctx, idA, mallory.DeviceID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("cross-user delete: %v", err)
	}
	if err := s.DeleteDevice(ctx, idA, "d_doesnotexist"); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	if err := s.DeleteDevice(ctx, idA, c.DeviceID); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Authenticate(ctx, c.AccessToken); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("removed device still valid")
	}
	if err := s.DeleteAccount(ctx, idA, "wrongpass"); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatal(err)
	}
	if err := s.DeleteAccount(ctx, idA, "password1"); err != nil {
		t.Fatal(err)
	}
	if _, err := st.Read().UserByUsername(ctx, "alice"); !errors.Is(err, store.ErrNotFound) {
		t.Fatal("user not deleted")
	}
	if _, err := s.Authenticate(ctx, a.AccessToken); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("deleted account token valid")
	}
	if _, err := s.Login(ctx, "alice", "password1", "p"); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatal("deleted account can log in")
	}
	// Username is free again.
	if _, err := s.Register(ctx, "alice", "password2", "p"); err != nil {
		t.Fatal(err)
	}
}
