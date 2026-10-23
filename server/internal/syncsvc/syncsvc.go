// Package syncsvc implements POST /v1/sync: per-record validation,
// last-writer-wins application and cursor-based pulling.
package syncsvc

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"strconv"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/listenly/listenly-server/internal/store"
)

// Protocol limits.
const (
	MaxChanges    = 500
	MaxPull       = 1000
	MaxKeyBytes   = 256
	MaxValueBytes = 16 * 1024
	// MaxClockSkew is how far the millisecond part of a record's clock may
	// be ahead of server time before the record is rejected (bad_clock).
	MaxClockSkew = 24 * time.Hour
)

// Rejection reasons.
const (
	ReasonBadCollection = "bad_collection"
	ReasonBadKey        = "bad_key"
	ReasonBadClock      = "bad_clock"
	ReasonValueTooLarge = "value_too_large"
	ReasonBadValue      = "bad_value"
)

// ErrTooManyChanges is returned when a request carries more than MaxChanges records.
var ErrTooManyChanges = errors.New("too_many_changes")

var collections = map[string]bool{
	"answer": true, "position": true, "bookmark": true,
	"completion": true, "activity": true, "setting": true,
}

// Change is a validated incoming record.
type Change struct {
	Collection string
	Key        string
	Value      []byte // compact JSON object, or nil for null
	Deleted    bool
	Clock      int64
}

// Rejection is an entry of the response's "rejected" array.
type Rejection struct {
	Index  int    `json:"index"`
	Reason string `json:"reason"`
}

// Result is the outcome of a sync round-trip.
type Result struct {
	Accepted int
	Ignored  int
	Rejected []Rejection
	Changes  []store.Record
	Cursor   int64
	HasMore  bool
}

// Wins reports whether version (c1, d1) is strictly greater than (c2, d2)
// under lexicographic (clock, device) ordering. Devices compare bytewise.
func Wins(c1 int64, d1 string, c2 int64, d2 string) bool {
	if c1 != c2 {
		return c1 > c2
	}
	return d1 > d2
}

// ParseChange validates one raw record against server time nowMs (unix
// milliseconds). It returns a rejection reason on failure. Checks run in the order collection, key, clock, value; the first
// failing check determines the reason. The client-supplied "device" and
// "seq" fields are ignored.
func ParseChange(raw json.RawMessage, nowMs int64) (Change, string) {
	var f map[string]json.RawMessage
	if err := json.Unmarshal(raw, &f); err != nil || f == nil {
		return Change{}, ReasonBadValue // not a JSON object at all
	}
	var c Change
	if err := json.Unmarshal(f["collection"], &c.Collection); err != nil || !collections[c.Collection] {
		return Change{}, ReasonBadCollection
	}
	if err := json.Unmarshal(f["key"], &c.Key); err != nil || !validKey(c.Key) {
		return Change{}, ReasonBadKey
	}
	clk, ok := parseClock(f["clock"])
	// clock = (unixMillis << 16) | counter; a far-future clock would win
	// every later write and lock the record forever.
	if !ok || clk>>16 > nowMs+MaxClockSkew.Milliseconds() {
		return Change{}, ReasonBadClock
	}
	c.Clock = clk
	if d, ok := f["deleted"]; ok && !isNull(d) {
		if err := json.Unmarshal(d, &c.Deleted); err != nil {
			return Change{}, ReasonBadValue
		}
	}
	if c.Deleted {
		return c, "" // tombstones always store null, whatever was sent
	}
	v, ok := f["value"]
	if !ok || isNull(v) {
		return c, ""
	}
	v = bytes.TrimSpace(v)
	if len(v) == 0 || v[0] != '{' {
		return Change{}, ReasonBadValue
	}
	var buf bytes.Buffer
	if err := json.Compact(&buf, v); err != nil {
		return Change{}, ReasonBadValue
	}
	if buf.Len() > MaxValueBytes {
		return Change{}, ReasonValueTooLarge
	}
	c.Value = buf.Bytes()
	return c, ""
}

func isNull(b json.RawMessage) bool { return string(bytes.TrimSpace(b)) == "null" }

func validKey(k string) bool {
	if len(k) == 0 || len(k) > MaxKeyBytes || !utf8.ValidString(k) {
		return false
	}
	for _, r := range k {
		if unicode.IsControl(r) || r == utf8.RuneError {
			return false
		}
	}
	return true
}

// parseClock accepts only a JSON integer literal in (0, MaxInt64].
func parseClock(b json.RawMessage) (int64, bool) {
	s := string(bytes.TrimSpace(b))
	if s == "" || s[0] == '"' {
		return 0, false
	}
	n, err := strconv.ParseInt(s, 10, 64)
	if err != nil || n <= 0 {
		return 0, false
	}
	return n, true
}

// Service applies sync requests.
type Service struct {
	st  *store.Store
	now func() time.Time
}

// New creates a Service. now may be nil.
func New(st *store.Store, now func() time.Time) *Service {
	if now == nil {
		now = time.Now
	}
	return &Service{st: st, now: now}
}

// Sync validates and applies changes written by deviceID (which overrides
// any client-supplied device), then returns records with seq > cursor.
// All writes and the pull happen in one transaction.
func (s *Service) Sync(ctx context.Context, userID, deviceID string, cursor int64, raw []json.RawMessage) (Result, error) {
	if len(raw) > MaxChanges {
		return Result{}, ErrTooManyChanges
	}
	res := Result{Rejected: []Rejection{}}
	valid := make([]Change, 0, len(raw))
	nowMs := s.now().UnixMilli()
	for i, r := range raw {
		c, reason := ParseChange(r, nowMs)
		if reason != "" {
			res.Rejected = append(res.Rejected, Rejection{Index: i, Reason: reason})
			continue
		}
		valid = append(valid, c)
	}

	run := func(r store.Repo) error {
		u, err := r.UserByID(ctx, userID)
		if err != nil {
			return err
		}
		seq := u.Seq
		now := s.now().Unix()
		for _, c := range valid {
			oc, od, err := r.RecordVersion(ctx, userID, c.Collection, c.Key)
			switch {
			case errors.Is(err, store.ErrNotFound):
			case err != nil:
				return err
			case !Wins(c.Clock, deviceID, oc, od):
				res.Ignored++
				continue
			}
			seq++
			rec := store.Record{Collection: c.Collection, Key: c.Key, Value: c.Value, Deleted: c.Deleted,
				Clock: c.Clock, Device: deviceID, Seq: seq}
			if err := r.UpsertRecord(ctx, userID, rec, now); err != nil {
				return err
			}
			res.Accepted++
		}
		if seq != u.Seq {
			if err := r.SetUserSeq(ctx, userID, seq); err != nil {
				return err
			}
		}
		recs, err := r.RecordsAfter(ctx, userID, cursor, MaxPull+1)
		if err != nil {
			return err
		}
		if len(recs) > MaxPull {
			recs, res.HasMore = recs[:MaxPull], true
		}
		res.Changes = recs
		if len(recs) > 0 {
			res.Cursor = recs[len(recs)-1].Seq
		} else {
			res.Cursor = min(cursor, seq)
		}
		return nil
	}

	var err error
	if len(valid) == 0 {
		err = run(s.st.Read()) // pull-only: no write lock needed
	} else {
		err = s.st.Tx(ctx, run)
	}
	if err != nil {
		return Result{}, err
	}
	return res, nil
}
