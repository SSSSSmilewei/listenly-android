package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/listenly/listenly-server/internal/auth"
	"github.com/listenly/listenly-server/internal/ratelimit"
	"github.com/listenly/listenly-server/internal/store"
	"github.com/listenly/listenly-server/internal/syncsvc"
)

type env struct {
	t   *testing.T
	srv *httptest.Server
	log *bytes.Buffer
}

type opts struct {
	allowReg    bool
	trustProxy  bool
	authLimiter *ratelimit.Limiter
	syncLimiter *ratelimit.Limiter
}

func newEnv(t *testing.T, o opts) *env {
	t.Helper()
	ctx := context.Background()
	st, err := store.Open(ctx, filepath.Join(t.TempDir(), "e2e.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	logBuf := &bytes.Buffer{}
	log := slog.New(slog.NewJSONHandler(logBuf, &slog.HandlerOptions{Level: slog.LevelDebug}))
	a, err := auth.New(st, auth.Options{
		Secret: []byte("0123456789abcdef0123456789abcdef"), AllowRegistration: o.allowReg,
		Params: auth.Params{MemoryKiB: 64, Time: 1, Threads: 1, SaltLen: 16, KeyLen: 32}, Logger: log,
	})
	if err != nil {
		t.Fatal(err)
	}
	if o.authLimiter == nil {
		o.authLimiter = ratelimit.New(1000, time.Minute, nil)
	}
	if o.syncLimiter == nil {
		o.syncLimiter = ratelimit.New(1000, time.Minute, nil)
	}
	api := New(Options{Store: st, Auth: a, Sync: syncsvc.New(st, nil), Logger: log, TrustProxy: o.trustProxy,
		AuthLimiter: o.authLimiter, SyncLimiter: o.syncLimiter})
	srv := httptest.NewServer(api)
	t.Cleanup(srv.Close)
	return &env{t: t, srv: srv, log: logBuf}
}

type resp struct {
	status int
	header http.Header
	body   []byte
}

func (r resp) json(t *testing.T, v any) {
	t.Helper()
	if err := json.Unmarshal(r.body, v); err != nil {
		t.Fatalf("decode %s: %v", r.body, err)
	}
}

func (r resp) code(t *testing.T) string {
	t.Helper()
	var e struct {
		Error struct{ Code, Message string } `json:"error"`
	}
	r.json(t, &e)
	if e.Error.Message == "" {
		t.Fatalf("error without message: %s", r.body)
	}
	return e.Error.Code
}

func (e *env) do(method, path, token string, body any, hdr ...string) resp {
	e.t.Helper()
	var rd io.Reader
	switch b := body.(type) {
	case nil:
	case string:
		rd = strings.NewReader(b)
	case []byte:
		rd = bytes.NewReader(b)
	default:
		j, _ := json.Marshal(b)
		rd = bytes.NewReader(j)
	}
	req, _ := http.NewRequest(method, e.srv.URL+path, rd)
	req.Header.Set("Content-Type", "application/json; charset=utf-8")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	for i := 0; i+1 < len(hdr); i += 2 {
		req.Header.Set(hdr[i], hdr[i+1])
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		e.t.Fatal(err)
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(res.Body)
	return resp{res.StatusCode, res.Header, b}
}

func (e *env) expect(r resp, status int, code string) {
	e.t.Helper()
	if r.status != status {
		e.t.Fatalf("status %d want %d: %s", r.status, status, r.body)
	}
	if code != "" {
		if got := r.code(e.t); got != code {
			e.t.Fatalf("code %q want %q", got, code)
		}
	}
}

type session struct {
	UserID, DeviceID, AccessToken, AccessExpiresAt, RefreshToken, RefreshExpiresAt string
}

type syncRec struct {
	Collection string          `json:"collection"`
	Key        string          `json:"key"`
	Value      json.RawMessage `json:"value"`
	Deleted    bool            `json:"deleted"`
	Clock      int64           `json:"clock"`
	Device     string          `json:"device"`
	Seq        int64           `json:"seq"`
}

type syncResp struct {
	Accepted int                 `json:"accepted"`
	Ignored  int                 `json:"ignored"`
	Rejected []syncsvc.Rejection `json:"rejected"`
	Changes  []syncRec           `json:"changes"`
	Cursor   int64               `json:"cursor"`
	HasMore  bool                `json:"hasMore"`
}

func (e *env) sync(tok string, cursor int64, changes ...map[string]any) syncResp {
	e.t.Helper()
	if changes == nil {
		changes = []map[string]any{}
	}
	r := e.do("POST", "/v1/sync", tok, map[string]any{"cursor": cursor, "changes": changes})
	e.expect(r, 200, "")
	var s syncResp
	r.json(e.t, &s)
	// rejected and changes must always be arrays, never null
	if !bytes.Contains(r.body, []byte(`"rejected":[`)) || !bytes.Contains(r.body, []byte(`"changes":[`)) {
		e.t.Fatalf("arrays must not be null: %s", r.body)
	}
	return s
}

func ch(coll, key string, value any, deleted bool, clock int64) map[string]any {
	return map[string]any{"collection": coll, "key": key, "value": value, "deleted": deleted, "clock": clock, "device": "d_forged"}
}

func TestEndToEnd(t *testing.T) {
	e := newEnv(t, opts{allowReg: true})
	const password = "s3cret-pass"
	// Hybrid logical clock base: (unixMillis << 16). Clocks must not be more
	// than 24h ahead of server time.
	hlc0 := time.Now().UnixMilli() << 16

	// Health.
	r := e.do("GET", "/healthz", "", nil)
	e.expect(r, 200, "")
	if strings.TrimSpace(string(r.body)) != `{"status":"ok"}` || r.header.Get("Content-Type") != "application/json; charset=utf-8" {
		t.Fatalf("healthz: %s %v", r.body, r.header)
	}
	if r.header.Get("X-Request-ID") == "" {
		t.Fatal("missing request id")
	}

	// Register (device A).
	r = e.do("POST", "/v1/auth/register", "", map[string]string{"username": "Listener", "password": password, "deviceName": "Xiaomi 15"})
	e.expect(r, 201, "")
	var a session
	r.json(t, &a)
	if !strings.HasPrefix(a.UserID, "u_") || !strings.HasPrefix(a.DeviceID, "d_") || a.AccessToken == "" || a.RefreshToken == "" {
		t.Fatalf("register: %+v", a)
	}
	for _, ts := range []string{a.AccessExpiresAt, a.RefreshExpiresAt} {
		p, err := time.Parse(time.RFC3339, ts)
		if err != nil || !strings.HasSuffix(ts, "Z") || p.Location() != time.UTC {
			t.Fatalf("timestamp %q: %v", ts, err)
		}
	}
	e.expect(e.do("POST", "/v1/auth/register", "", map[string]string{"username": "LISTENER", "password": password, "deviceName": "x"}), 409, "username_taken")
	e.expect(e.do("POST", "/v1/auth/register", "", map[string]string{"username": "a b", "password": password, "deviceName": "x"}), 400, "bad_username")
	e.expect(e.do("POST", "/v1/auth/register", "", map[string]string{"username": "other", "password": "1234567", "deviceName": "x"}), 400, "weak_password")
	e.expect(e.do("POST", "/v1/auth/register", "", map[string]string{"username": "other", "password": password, "deviceName": ""}), 400, "bad_request")
	e.expect(e.do("POST", "/v1/auth/register", "", `{"username":`), 400, "bad_request")
	e.expect(e.do("POST", "/v1/auth/register", "", `{"username":"x"} {"again":1}`), 400, "bad_request")
	e.expect(e.do("POST", "/v1/auth/register", "", `{"username":5}`), 400, "bad_request")

	// Device A pushes.
	s := e.sync(a.AccessToken, 0,
		ch("answer", "cet6-example/section-a-1/q1", map[string]any{"choice": "B", "submitted": true}, false, hlc0+1),
		ch("position", "cet6-example", map[string]any{"seconds": 18.4, "articleId": "morning"}, false, hlc0+2),
		ch("bookmark", "cet6-example/morning", map[string]any{}, false, hlc0+3),
		ch("activity", "2026-10-22/"+a.DeviceID, map[string]any{"listeningSeconds": 720}, false, hlc0+4),
		ch("audio", "nope", map[string]any{}, false, 1),
	)
	if s.Accepted != 4 || s.Ignored != 0 || len(s.Rejected) != 1 || s.Rejected[0] != (syncsvc.Rejection{Index: 4, Reason: "bad_collection"}) {
		t.Fatalf("push A: %+v", s)
	}
	if len(s.Changes) != 4 || s.Cursor != 4 || s.HasMore {
		t.Fatalf("push A echo: %+v", s)
	}
	for _, c := range s.Changes {
		if c.Device != a.DeviceID {
			t.Fatalf("device not overridden: %q", c.Device)
		}
	}
	cursorA := s.Cursor

	// Login as device B and pull everything.
	r = e.do("POST", "/v1/auth/login", "", map[string]string{"username": "listener", "password": password, "deviceName": "Pixel 9"})
	e.expect(r, 200, "")
	var b session
	r.json(t, &b)
	if b.UserID != a.UserID || b.DeviceID == a.DeviceID {
		t.Fatalf("login: %+v", b)
	}
	e.expect(e.do("POST", "/v1/auth/login", "", map[string]string{"username": "listener", "password": "wrong-pass", "deviceName": "x"}), 401, "invalid_credentials")
	e.expect(e.do("POST", "/v1/auth/login", "", map[string]string{"username": "ghost", "password": password, "deviceName": "x"}), 401, "invalid_credentials")

	s = e.sync(b.AccessToken, 0)
	if len(s.Changes) != 4 || s.Cursor != 4 || s.HasMore {
		t.Fatalf("pull B: %+v", s)
	}
	if string(s.Changes[0].Value) != `{"choice":"B","submitted":true}` || s.Changes[0].Clock != hlc0+1 {
		t.Fatalf("value round-trip: %s", s.Changes[0].Value)
	}
	cursorB := s.Cursor

	// Conflicting edits on the same answer: B writes later clock, A writes an older one.
	key := "cet6-example/section-a-1/q1"
	s = e.sync(b.AccessToken, cursorB, ch("answer", key, map[string]any{"choice": "C"}, false, hlc0+100))
	if s.Accepted != 1 || len(s.Changes) != 1 || s.Changes[0].Seq != 5 {
		t.Fatalf("B edit: %+v", s)
	}
	cursorB = s.Cursor
	s = e.sync(a.AccessToken, cursorA, ch("answer", key, map[string]any{"choice": "A"}, false, hlc0+50))
	if s.Accepted != 0 || s.Ignored != 1 {
		t.Fatalf("A stale edit: %+v", s)
	}
	if len(s.Changes) != 1 || string(s.Changes[0].Value) != `{"choice":"C"}` || s.Changes[0].Device != b.DeviceID {
		t.Fatalf("A should receive B's winning value: %+v", s.Changes)
	}
	cursorA = s.Cursor
	// Same clock from both devices: the lexicographically larger device id wins.
	tie := int64(hlc0 + 200)
	sa := e.sync(a.AccessToken, cursorA, ch("setting", "accent", map[string]any{"v": "amber"}, false, tie))
	sb := e.sync(b.AccessToken, cursorB, ch("setting", "accent", map[string]any{"v": "teal"}, false, tie))
	winner, wantVal := a.DeviceID, `{"v":"amber"}`
	if b.DeviceID > a.DeviceID {
		winner, wantVal = b.DeviceID, `{"v":"teal"}`
		if sb.Accepted != 1 {
			t.Fatalf("B should win tie: %+v", sb)
		}
	} else if sb.Ignored != 1 {
		t.Fatalf("B should lose tie: %+v", sb)
	}
	_ = sa
	s = e.sync(a.AccessToken, 0)
	var accent *syncRec
	for i := range s.Changes {
		if s.Changes[i].Key == "accent" {
			accent = &s.Changes[i]
		}
	}
	if accent == nil || accent.Device != winner || string(accent.Value) != wantVal {
		t.Fatalf("tie result: %+v", accent)
	}
	// B un-bookmarks (tombstone); A pulls it.
	s = e.sync(b.AccessToken, 0, ch("bookmark", "cet6-example/morning", nil, true, hlc0+300))
	if s.Accepted != 1 {
		t.Fatalf("tombstone: %+v", s)
	}
	s = e.sync(a.AccessToken, s.Cursor-1)
	if len(s.Changes) != 1 || !s.Changes[0].Deleted || string(s.Changes[0].Value) != "null" {
		t.Fatalf("tombstone pull: %+v", s.Changes)
	}

	// Sync validation at request level.
	e.expect(e.do("POST", "/v1/sync", a.AccessToken, `{"cursor":-1,"changes":[]}`), 400, "bad_request")
	e.expect(e.do("POST", "/v1/sync", a.AccessToken, `{"cursor":"x","changes":[]}`), 400, "bad_request")
	e.expect(e.do("POST", "/v1/sync", a.AccessToken, `{"cursor":0,"changes":{}}`), 400, "bad_request")
	many := make([]map[string]any, 501)
	for i := range many {
		many[i] = ch("answer", fmt.Sprint(i), map[string]any{}, false, 1)
	}
	e.expect(e.do("POST", "/v1/sync", a.AccessToken, map[string]any{"cursor": 0, "changes": many}), 400, "too_many_changes")
	// Missing cursor/changes default to 0/[].
	r = e.do("POST", "/v1/sync", a.AccessToken, `{}`)
	e.expect(r, 200, "")

	// /v1/me.
	r = e.do("GET", "/v1/me", a.AccessToken, nil)
	e.expect(r, 200, "")
	var me struct {
		UserID   string `json:"userId"`
		Username string `json:"username"`
		Devices  []struct {
			ID, Name, CreatedAt, LastSeenAt string
			Current                         bool
		} `json:"devices"`
	}
	r.json(t, &me)
	if me.UserID != a.UserID || me.Username != "listener" || len(me.Devices) != 2 {
		t.Fatalf("me: %+v", me)
	}
	for _, d := range me.Devices {
		if d.Current != (d.ID == a.DeviceID) {
			t.Fatalf("current flag wrong: %+v", d)
		}
		if _, err := time.Parse(time.RFC3339, d.LastSeenAt); err != nil {
			t.Fatal(err)
		}
	}

	// Refresh on B, then logout B.
	r = e.do("POST", "/v1/auth/refresh", "", map[string]string{"refreshToken": b.RefreshToken})
	e.expect(r, 200, "")
	var b2 session
	r.json(t, &b2)
	if b2.DeviceID != b.DeviceID || b2.RefreshToken == b.RefreshToken {
		t.Fatalf("refresh: %+v", b2)
	}
	e.expect(e.do("POST", "/v1/auth/logout", b2.AccessToken, nil), 204, "")
	e.expect(e.do("POST", "/v1/sync", b2.AccessToken, `{}`), 401, "unauthorized")
	e.expect(e.do("POST", "/v1/auth/refresh", "", map[string]string{"refreshToken": b2.RefreshToken}), 401, "invalid_refresh_token")

	// Device management: C logs in, A removes C.
	r = e.do("POST", "/v1/auth/login", "", map[string]string{"username": "listener", "password": password, "deviceName": "iPad"})
	var c session
	r.json(t, &c)
	e.expect(e.do("DELETE", "/v1/devices/d_nonexistent", a.AccessToken, nil), 404, "not_found")
	e.expect(e.do("DELETE", "/v1/devices/"+c.DeviceID, a.AccessToken, nil), 204, "")
	e.expect(e.do("GET", "/v1/me", c.AccessToken, nil), 401, "unauthorized")

	// Account deletion.
	e.expect(e.do("DELETE", "/v1/me", a.AccessToken, map[string]string{"password": "nope-nope"}), 401, "invalid_credentials")
	e.expect(e.do("DELETE", "/v1/me", a.AccessToken, map[string]string{"password": password}), 204, "")
	e.expect(e.do("GET", "/v1/me", a.AccessToken, nil), 401, "unauthorized")
	e.expect(e.do("POST", "/v1/auth/login", "", map[string]string{"username": "listener", "password": password, "deviceName": "x"}), 401, "invalid_credentials")
	// Records are gone: a re-registered account starts empty.
	r = e.do("POST", "/v1/auth/register", "", map[string]string{"username": "listener", "password": password, "deviceName": "x"})
	e.expect(r, 201, "")
	var n session
	r.json(t, &n)
	if s := e.sync(n.AccessToken, 0); len(s.Changes) != 0 || s.Cursor != 0 {
		t.Fatalf("records survived deletion: %+v", s)
	}

	// Secrets never reach the log.
	logs := e.log.String()
	for _, secret := range []string{password, a.AccessToken, a.RefreshToken, b.RefreshToken, b2.AccessToken, `"choice"`, "amber"} {
		if strings.Contains(logs, secret) {
			t.Fatalf("log leaked %q", secret)
		}
	}
	if !strings.Contains(logs, `"path":"/v1/sync"`) {
		t.Fatal("access log missing")
	}
}

func TestAuthErrors(t *testing.T) {
	e := newEnv(t, opts{allowReg: false})
	e.expect(e.do("POST", "/v1/auth/register", "", map[string]string{"username": "someone", "password": "password1", "deviceName": "x"}), 403, "registration_closed")
	e.expect(e.do("GET", "/v1/me", "", nil), 401, "unauthorized")
	e.expect(e.do("GET", "/v1/me", "", nil, "Authorization", "Basic abc"), 401, "unauthorized")
	e.expect(e.do("GET", "/v1/me", "not.a.jwt", nil), 401, "unauthorized")
	e.expect(e.do("POST", "/v1/auth/refresh", "", map[string]string{"refreshToken": "bogus"}), 401, "invalid_refresh_token")
	e.expect(e.do("GET", "/nope", "", nil), 404, "not_found")
}

func TestRefreshGraceAndReuseOverHTTP(t *testing.T) {
	e := newEnv(t, opts{allowReg: true})
	refresh := func(tok string) resp {
		return e.do("POST", "/v1/auth/refresh", "", map[string]string{"refreshToken": tok})
	}
	r := e.do("POST", "/v1/auth/register", "", map[string]string{"username": "alice", "password": "password1", "deviceName": "p"})
	var s0 session
	r.json(t, &s0)
	r = refresh(s0.RefreshToken)
	e.expect(r, 200, "")
	var s1 session
	r.json(t, &s1) // pretend this response was lost

	// Immediate retry with the just-rotated token: grace window → new pair, s1 superseded.
	r = refresh(s0.RefreshToken)
	e.expect(r, 200, "")
	var s2 session
	r.json(t, &s2)
	if s2.DeviceID != s0.DeviceID || s2.RefreshToken == s1.RefreshToken {
		t.Fatalf("grace retry: %+v", s2)
	}
	e.expect(e.do("GET", "/v1/me", s1.AccessToken, nil), 401, "unauthorized")
	e.expect(refresh(s1.RefreshToken), 401, "invalid_refresh_token")
	e.expect(e.do("GET", "/v1/me", s2.AccessToken, nil), 200, "")

	// Rotate s2 → s3; s0 is now an older ancestor → theft → device revoked.
	r = refresh(s2.RefreshToken)
	e.expect(r, 200, "")
	var s3 session
	r.json(t, &s3)
	e.expect(refresh(s0.RefreshToken), 401, "invalid_refresh_token")
	e.expect(refresh(s3.RefreshToken), 401, "invalid_refresh_token")
	e.expect(e.do("GET", "/v1/me", s3.AccessToken, nil), 401, "unauthorized")
}

func TestBodyLimit(t *testing.T) {
	e := newEnv(t, opts{allowReg: true})
	big := `{"username":"` + strings.Repeat("a", MaxBodyBytes) + `"}`
	e.expect(e.do("POST", "/v1/auth/register", "", big), 413, "body_too_large")
	// Chunked body without Content-Length is also capped.
	req, _ := http.NewRequest("POST", e.srv.URL+"/v1/auth/register", io.MultiReader(strings.NewReader(big)))
	req.ContentLength = -1
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 413 {
		t.Fatalf("chunked: %d", res.StatusCode)
	}
}

func TestAuthRateLimit(t *testing.T) {
	e := newEnv(t, opts{allowReg: true, authLimiter: ratelimit.New(10, time.Minute, nil)})
	for i := range 10 {
		r := e.do("POST", "/v1/auth/login", "", map[string]string{"username": "x", "password": "y", "deviceName": "z"})
		if r.status == 429 {
			t.Fatalf("limited at request %d", i)
		}
	}
	r := e.do("POST", "/v1/auth/refresh", "", map[string]string{"refreshToken": "x"})
	e.expect(r, 429, "rate_limited")
	ra, err := strconv.Atoi(r.header.Get("Retry-After"))
	if err != nil || ra < 1 || ra > 6 {
		t.Fatalf("Retry-After = %q", r.header.Get("Retry-After"))
	}
	// Authenticated non-auth endpoints are not IP-limited.
	e.expect(e.do("GET", "/healthz", "", nil), 200, "")
}

func TestTrustProxyRateLimitKey(t *testing.T) {
	e := newEnv(t, opts{allowReg: true, trustProxy: true, authLimiter: ratelimit.New(1, time.Minute, nil)})
	body := map[string]string{"refreshToken": "x"}
	e.expect(e.do("POST", "/v1/auth/refresh", "", body, "X-Forwarded-For", "203.0.113.9, 198.51.100.1"), 401, "")
	// Same rightmost (proxy-observed) address → limited even though the spoofable left part changed.
	e.expect(e.do("POST", "/v1/auth/refresh", "", body, "X-Forwarded-For", "1.1.1.1, 198.51.100.1"), 429, "rate_limited")
	// A different client address gets its own bucket.
	e.expect(e.do("POST", "/v1/auth/refresh", "", body, "X-Forwarded-For", "198.51.100.2"), 401, "")

	// Without TrustProxy the header is ignored.
	e2 := newEnv(t, opts{allowReg: true, authLimiter: ratelimit.New(1, time.Minute, nil)})
	e2.expect(e2.do("POST", "/v1/auth/refresh", "", body, "X-Forwarded-For", "198.51.100.3"), 401, "")
	e2.expect(e2.do("POST", "/v1/auth/refresh", "", body, "X-Forwarded-For", "198.51.100.4"), 429, "rate_limited")
}

func TestSyncRateLimitPerUser(t *testing.T) {
	e := newEnv(t, opts{allowReg: true, syncLimiter: ratelimit.New(60, time.Minute, nil)})
	reg := func(u string) session {
		r := e.do("POST", "/v1/auth/register", "", map[string]string{"username": u, "password": "password1", "deviceName": "p"})
		var s session
		r.json(t, &s)
		return s
	}
	a, b := reg("alice"), reg("bob")
	r := e.do("POST", "/v1/auth/login", "", map[string]string{"username": "alice", "password": "password1", "deviceName": "p2"})
	var a2 session
	r.json(t, &a2)
	for i := range 60 {
		tok := a.AccessToken
		if i%2 == 1 {
			tok = a2.AccessToken // the limit is per user, across devices
		}
		if r := e.do("POST", "/v1/sync", tok, `{}`); r.status != 200 {
			t.Fatalf("request %d: %d", i, r.status)
		}
	}
	r = e.do("POST", "/v1/sync", a2.AccessToken, `{}`)
	e.expect(r, 429, "rate_limited")
	if r.header.Get("Retry-After") == "" {
		t.Fatal("missing Retry-After")
	}
	e.expect(e.do("POST", "/v1/sync", b.AccessToken, `{}`), 200, "")
}

func TestPanicRecovery(t *testing.T) {
	s := &Server{log: slog.New(slog.NewTextHandler(io.Discard, nil))}
	h := s.withCommon(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { panic("boom") }))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest("GET", "/x", nil))
	if rec.Code != 500 || !strings.Contains(rec.Body.String(), `"internal"`) {
		t.Fatalf("panic: %d %s", rec.Code, rec.Body)
	}
}
