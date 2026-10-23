package syncsvc

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/listenly/listenly-server/internal/store"
)

func setup(t *testing.T) (*Service, *store.Store) {
	t.Helper()
	st := setupStore(t)
	return New(st, nil), st
}

func setupStore(t *testing.T) *store.Store {
	t.Helper()
	ctx := context.Background()
	st, err := store.Open(ctx, filepath.Join(t.TempDir(), "s.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	err = st.Tx(ctx, func(r store.Repo) error {
		for _, u := range []string{"u_1", "u_2"} {
			if err := r.CreateUser(ctx, store.User{ID: u, Username: u, PasswordHash: "x"}); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	return st
}

func rec(coll, key, value string, deleted bool, clock int64) json.RawMessage {
	m := map[string]any{"collection": coll, "key": key, "deleted": deleted, "clock": clock, "device": "d_spoofed"}
	if value != "" {
		m["value"] = json.RawMessage(value)
	} else {
		m["value"] = nil
	}
	b, _ := json.Marshal(m)
	return b
}

func TestWins(t *testing.T) {
	cases := []struct {
		c1   int64
		d1   string
		c2   int64
		d2   string
		want bool
	}{
		{2, "d_a", 1, "d_z", true},  // higher clock wins regardless of device
		{1, "d_z", 2, "d_a", false}, // lower clock loses regardless of device
		{5, "d_b", 5, "d_a", true},  // equal clock: larger device wins
		{5, "d_a", 5, "d_b", false},
		{5, "d_a", 5, "d_a", false}, // identical version is not strictly greater
		{5, "d_B", 5, "d_a", false}, // bytewise: 'B' (0x42) < 'a' (0x61)
	}
	for _, c := range cases {
		if got := Wins(c.c1, c.d1, c.c2, c.d2); got != c.want {
			t.Errorf("Wins(%d,%q,%d,%q)=%v", c.c1, c.d1, c.c2, c.d2, got)
		}
	}
}

func TestParseChangeReasons(t *testing.T) {
	big := `{"s":"` + strings.Repeat("x", MaxValueBytes) + `"}`
	exact := `{"s":"` + strings.Repeat("x", MaxValueBytes-8) + `"}` // exactly 16384 bytes
	if len(exact) != MaxValueBytes {
		t.Fatalf("exact len %d", len(exact))
	}
	cases := []struct {
		name, raw, reason string
	}{
		{"ok", `{"collection":"answer","key":"p/a/q1","value":{"choice":"B"},"deleted":false,"clock":1}`, ""},
		{"ok tombstone value ignored", `{"collection":"bookmark","key":"p/a","value":{"x":1},"deleted":true,"clock":7}`, ""},
		{"ok null value", `{"collection":"bookmark","key":"p/a","value":null,"clock":7}`, ""},
		{"ok exact 16KiB", `{"collection":"setting","key":"k","value":` + exact + `,"clock":7}`, ""},
		{"ok key 256 bytes", `{"collection":"setting","key":"` + strings.Repeat("k", 256) + `","value":{},"clock":7}`, ""},
		{"ok unicode key", `{"collection":"setting","key":"口音/美式","value":{},"clock":7}`, ""},
		{"not object", `[1,2]`, ReasonBadValue},
		{"null record", `null`, ReasonBadValue},
		{"unknown collection", `{"collection":"audio","key":"k","value":{},"clock":1}`, ReasonBadCollection},
		{"missing collection", `{"key":"k","value":{},"clock":1}`, ReasonBadCollection},
		{"collection wrong type", `{"collection":1,"key":"k","value":{},"clock":1}`, ReasonBadCollection},
		{"collection case", `{"collection":"Answer","key":"k","value":{},"clock":1}`, ReasonBadCollection},
		{"empty key", `{"collection":"answer","key":"","value":{},"clock":1}`, ReasonBadKey},
		{"missing key", `{"collection":"answer","value":{},"clock":1}`, ReasonBadKey},
		{"long key", `{"collection":"answer","key":"` + strings.Repeat("k", 257) + `","value":{},"clock":1}`, ReasonBadKey},
		{"long multibyte key", `{"collection":"answer","key":"` + strings.Repeat("机", 86) + `","value":{},"clock":1}`, ReasonBadKey},
		{"control char key", `{"collection":"answer","key":"a\u0000b","value":{},"clock":1}`, ReasonBadKey},
		{"newline key", `{"collection":"answer","key":"a\nb","value":{},"clock":1}`, ReasonBadKey},
		{"DEL key", `{"collection":"answer","key":"a\u007fb","value":{},"clock":1}`, ReasonBadKey},
		{"lone surrogate key", `{"collection":"answer","key":"a\ud800b","value":{},"clock":1}`, ReasonBadKey},
		{"key wrong type", `{"collection":"answer","key":5,"value":{},"clock":1}`, ReasonBadKey},
		{"zero clock", `{"collection":"answer","key":"k","value":{},"clock":0}`, ReasonBadClock},
		{"negative clock", `{"collection":"answer","key":"k","value":{},"clock":-5}`, ReasonBadClock},
		{"missing clock", `{"collection":"answer","key":"k","value":{}}`, ReasonBadClock},
		{"float clock", `{"collection":"answer","key":"k","value":{},"clock":1.5}`, ReasonBadClock},
		{"exp clock", `{"collection":"answer","key":"k","value":{},"clock":1e5}`, ReasonBadClock},
		{"string clock", `{"collection":"answer","key":"k","value":{},"clock":"5"}`, ReasonBadClock},
		{"overflow clock", `{"collection":"answer","key":"k","value":{},"clock":9223372036854775808}`, ReasonBadClock},
		{"array value", `{"collection":"answer","key":"k","value":[1],"clock":1}`, ReasonBadValue},
		{"string value", `{"collection":"answer","key":"k","value":"B","clock":1}`, ReasonBadValue},
		{"number value", `{"collection":"answer","key":"k","value":3,"clock":1}`, ReasonBadValue},
		{"deleted wrong type", `{"collection":"answer","key":"k","value":{},"deleted":"yes","clock":1}`, ReasonBadValue},
		{"too large", `{"collection":"answer","key":"k","value":` + big + `,"clock":1}`, ReasonValueTooLarge},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			_, reason := ParseChange(json.RawMessage(c.raw), testNowMs)
			if reason != c.reason {
				t.Fatalf("reason=%q want %q", reason, c.reason)
			}
		})
	}
	// max int64 clock is far in the future → rejected by the skew bound
	if _, r := ParseChange(json.RawMessage(`{"collection":"answer","key":"k","value":{},"clock":9223372036854775807}`), testNowMs); r != ReasonBadClock {
		t.Fatalf("max clock: %q", r)
	}
	// value is compacted and tombstones store null
	ch, _ := ParseChange(json.RawMessage(`{"collection":"answer","key":"k","value": { "a" : 1 },"clock":1}`), testNowMs)
	if string(ch.Value) != `{"a":1}` {
		t.Fatalf("not compacted: %s", ch.Value)
	}
	ch, _ = ParseChange(json.RawMessage(`{"collection":"answer","key":"k","value":{"a":1},"deleted":true,"clock":1}`), testNowMs)
	if ch.Value != nil || !ch.Deleted {
		t.Fatal("tombstone should store null")
	}
}

// testNowMs is 2026-10-22T08:00:00Z.
var testNowMs = time.Date(2026, 10, 22, 8, 0, 0, 0, time.UTC).UnixMilli()

func TestClockSkewBound(t *testing.T) {
	bound := testNowMs + MaxClockSkew.Milliseconds()
	mk := func(clock int64) json.RawMessage {
		return json.RawMessage(fmt.Sprintf(`{"collection":"answer","key":"k","value":{},"clock":%d}`, clock))
	}
	cases := []struct {
		name   string
		clock  int64
		reason string
	}{
		{"now", testNowMs << 16, ""},
		{"exactly at bound, counter 0", bound << 16, ""},
		{"exactly at bound, max counter", bound<<16 | 0xFFFF, ""},
		{"1ms over bound", (bound + 1) << 16, ReasonBadClock},
		{"far past is fine", 1, ""},
	}
	for _, c := range cases {
		if _, r := ParseChange(mk(c.clock), testNowMs); r != c.reason {
			t.Errorf("%s: reason=%q want %q", c.name, r, c.reason)
		}
	}
	// Through the service with an injected clock.
	ctx := context.Background()
	st := setupStore(t)
	svc := New(st, func() time.Time { return time.UnixMilli(testNowMs) })
	res, err := svc.Sync(ctx, "u_1", "d_a", 0, []json.RawMessage{mk(bound << 16), mk((bound + 1) << 16)})
	if err != nil {
		t.Fatal(err)
	}
	if res.Accepted != 1 || len(res.Rejected) != 1 || res.Rejected[0] != (Rejection{1, ReasonBadClock}) {
		t.Fatalf("service: %+v", res)
	}
}

func TestRejectedDoNotBlockOthers(t *testing.T) {
	s, _ := setup(t)
	ctx := context.Background()
	res, err := s.Sync(ctx, "u_1", "d_a", 0, []json.RawMessage{
		rec("answer", "k1", `{"choice":"A"}`, false, 10),
		rec("nope", "k2", `{}`, false, 10),
		rec("answer", "k3", `{}`, false, 0),
		rec("answer", "k4", `[]`, false, 10),
		rec("answer", "", `{}`, false, 10),
		rec("answer", "k6", `{}`, false, 10),
	})
	if err != nil {
		t.Fatal(err)
	}
	if res.Accepted != 2 || res.Ignored != 0 {
		t.Fatalf("accepted=%d ignored=%d", res.Accepted, res.Ignored)
	}
	want := []Rejection{{1, ReasonBadCollection}, {2, ReasonBadClock}, {3, ReasonBadValue}, {4, ReasonBadKey}}
	if fmt.Sprint(res.Rejected) != fmt.Sprint(want) {
		t.Fatalf("rejected=%v", res.Rejected)
	}
}

func TestLWW(t *testing.T) {
	s, _ := setup(t)
	ctx := context.Background()
	push := func(dev string, cursor int64, recs ...json.RawMessage) Result {
		t.Helper()
		r, err := s.Sync(ctx, "u_1", dev, cursor, recs)
		if err != nil {
			t.Fatal(err)
		}
		return r
	}
	r := push("d_b", 0, rec("answer", "q1", `{"choice":"A"}`, false, 100))
	if r.Accepted != 1 || len(r.Changes) != 1 || r.Changes[0].Seq != 1 || r.Cursor != 1 {
		t.Fatalf("first: %+v", r)
	}
	if r.Changes[0].Device != "d_b" {
		t.Fatalf("server must override device, got %q", r.Changes[0].Device)
	}

	// Older clock is ignored, no seq consumed.
	r = push("d_z", 1, rec("answer", "q1", `{"choice":"OLD"}`, false, 99))
	if r.Accepted != 0 || r.Ignored != 1 || len(r.Changes) != 0 || r.Cursor != 1 {
		t.Fatalf("older: %+v", r)
	}
	// Equal clock, smaller device → ignored.
	r = push("d_a", 1, rec("answer", "q1", `{"choice":"TIE-LOSE"}`, false, 100))
	if r.Ignored != 1 {
		t.Fatalf("tie lose: %+v", r)
	}
	// Exact replay by the same device → ignored (idempotent).
	r = push("d_b", 1, rec("answer", "q1", `{"choice":"A"}`, false, 100))
	if r.Ignored != 1 {
		t.Fatalf("replay: %+v", r)
	}
	// Equal clock, larger device → wins.
	r = push("d_c", 1, rec("answer", "q1", `{"choice":"TIE-WIN"}`, false, 100))
	if r.Accepted != 1 || len(r.Changes) != 1 || r.Changes[0].Seq != 2 || string(r.Changes[0].Value) != `{"choice":"TIE-WIN"}` {
		t.Fatalf("tie win: %+v", r)
	}
	// Tombstone with a higher clock deletes, value stored as null.
	r = push("d_a", 2, rec("answer", "q1", `{"ignored":true}`, true, 200))
	if r.Accepted != 1 || !r.Changes[0].Deleted || r.Changes[0].Value != nil || r.Changes[0].Seq != 3 {
		t.Fatalf("tombstone: %+v", r.Changes)
	}
	// A stale write cannot resurrect a deleted record.
	r = push("d_z", 3, rec("answer", "q1", `{"choice":"ZOMBIE"}`, false, 150))
	if r.Ignored != 1 {
		t.Fatalf("resurrect: %+v", r)
	}
	// A newer write after the tombstone revives it.
	r = push("d_a", 3, rec("answer", "q1", `{"choice":"C"}`, false, 300))
	if r.Accepted != 1 || r.Changes[0].Deleted || r.Changes[0].Seq != 4 {
		t.Fatalf("revive: %+v", r)
	}
	// Duplicate keys within one batch are applied in order.
	r = push("d_a", 4,
		rec("bookmark", "p/a", `{}`, false, 500),
		rec("bookmark", "p/a", `{}`, false, 400), // older than the previous entry
		rec("bookmark", "p/a", `{}`, true, 600))
	if r.Accepted != 2 || r.Ignored != 1 || len(r.Changes) != 1 || !r.Changes[0].Deleted || r.Changes[0].Seq != 6 {
		t.Fatalf("batch: %+v", r)
	}
	// Full pull shows only the latest state of each key, seq ascending.
	r = push("d_a", 0)
	if len(r.Changes) != 2 || r.Changes[0].Key != "q1" || r.Changes[1].Key != "p/a" || r.Cursor != 6 {
		t.Fatalf("pull: %+v", r.Changes)
	}
	// Users are isolated.
	r2, _ := s.Sync(ctx, "u_2", "d_x", 0, nil)
	if len(r2.Changes) != 0 || r2.Cursor != 0 {
		t.Fatalf("isolation: %+v", r2)
	}
}

func TestPagination(t *testing.T) {
	s, _ := setup(t)
	ctx := context.Background()
	total := 1234
	for start := 0; start < total; start += MaxChanges {
		var batch []json.RawMessage
		for i := start; i < min(start+MaxChanges, total); i++ {
			batch = append(batch, rec("answer", fmt.Sprintf("p/a/q%04d", i), `{"choice":"A"}`, false, int64(i+1)))
		}
		r, err := s.Sync(ctx, "u_1", "d_a", 1<<40, batch) // huge cursor: nothing to pull
		if err != nil {
			t.Fatal(err)
		}
		if r.Accepted != len(batch) || len(r.Changes) != 0 {
			t.Fatalf("push: %+v", r)
		}
		// Request cursor beyond max seq → server max seq.
		if r.Cursor != int64(start+len(batch)) {
			t.Fatalf("cursor clamp = %d", r.Cursor)
		}
	}
	var cursor int64
	var seen []int64
	pages := 0
	for {
		r, err := s.Sync(ctx, "u_1", "d_b", cursor, nil)
		if err != nil {
			t.Fatal(err)
		}
		pages++
		if len(r.Changes) > MaxPull {
			t.Fatalf("page too big: %d", len(r.Changes))
		}
		for _, c := range r.Changes {
			seen = append(seen, c.Seq)
		}
		if len(r.Changes) > 0 && r.Cursor != r.Changes[len(r.Changes)-1].Seq {
			t.Fatal("cursor must equal last seq")
		}
		cursor = r.Cursor
		if !r.HasMore {
			break
		}
	}
	if pages != 2 || len(seen) != total {
		t.Fatalf("pages=%d seen=%d", pages, len(seen))
	}
	for i := 1; i < len(seen); i++ {
		if seen[i] <= seen[i-1] {
			t.Fatal("not ascending")
		}
	}
	// Exactly-at-end: no records, hasMore false, cursor unchanged.
	r, _ := s.Sync(ctx, "u_1", "d_b", cursor, nil)
	if len(r.Changes) != 0 || r.HasMore || r.Cursor != cursor {
		t.Fatalf("end: %+v", r)
	}
	// Exactly 1000 remaining → hasMore must be false.
	r, _ = s.Sync(ctx, "u_1", "d_b", int64(total-MaxPull), nil)
	if len(r.Changes) != MaxPull || r.HasMore {
		t.Fatalf("exact page: n=%d hasMore=%v", len(r.Changes), r.HasMore)
	}
}

func TestTooManyChanges(t *testing.T) {
	s, _ := setup(t)
	raw := make([]json.RawMessage, MaxChanges+1)
	for i := range raw {
		raw[i] = rec("answer", fmt.Sprint(i), `{}`, false, 1)
	}
	if _, err := s.Sync(context.Background(), "u_1", "d_a", 0, raw); !errors.Is(err, ErrTooManyChanges) {
		t.Fatal(err)
	}
	if _, err := s.Sync(context.Background(), "u_1", "d_a", 0, raw[:MaxChanges]); err != nil {
		t.Fatal(err)
	}
}

func TestMissingUser(t *testing.T) {
	s, _ := setup(t)
	_, err := s.Sync(context.Background(), "u_gone", "d_a", 0, []json.RawMessage{rec("answer", "k", `{}`, false, 1)})
	if !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("want ErrNotFound, got %v", err)
	}
}
