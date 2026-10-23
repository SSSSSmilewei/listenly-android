// Package ratelimit provides a keyed token-bucket limiter.
package ratelimit

import (
	"math"
	"sync"
	"time"
)

type bucket struct {
	tokens float64
	last   time.Time
}

// Limiter is a set of token buckets keyed by string (IP, user id, ...).
// Each bucket holds up to Burst tokens and refills at Burst per Period.
type Limiter struct {
	mu      sync.Mutex
	buckets map[string]*bucket
	burst   float64
	rate    float64 // tokens per second
	now     func() time.Time
}

// New returns a limiter allowing burst requests per period per key.
// now may be nil (time.Now).
func New(burst int, period time.Duration, now func() time.Time) *Limiter {
	if now == nil {
		now = time.Now
	}
	return &Limiter{
		buckets: make(map[string]*bucket),
		burst:   float64(burst),
		rate:    float64(burst) / period.Seconds(),
		now:     now,
	}
}

// Allow consumes one token for key. When no token is available it returns
// false and how long until one will be.
func (l *Limiter) Allow(key string) (bool, time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	b, ok := l.buckets[key]
	if !ok {
		b = &bucket{tokens: l.burst, last: now}
		l.buckets[key] = b
	} else if el := now.Sub(b.last).Seconds(); el > 0 {
		b.tokens = math.Min(l.burst, b.tokens+el*l.rate)
		b.last = now
	}
	if b.tokens >= 1 {
		b.tokens--
		return true, 0
	}
	wait := time.Duration((1 - b.tokens) / l.rate * float64(time.Second))
	return false, wait
}

// RetryAfterSeconds converts a wait into a Retry-After header value (≥1).
func RetryAfterSeconds(d time.Duration) int {
	return max(1, int(math.Ceil(d.Seconds())))
}

// Sweep drops buckets that would be full by now, bounding memory use.
func (l *Limiter) Sweep() {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	for k, b := range l.buckets {
		if b.tokens+now.Sub(b.last).Seconds()*l.rate >= l.burst {
			delete(l.buckets, k)
		}
	}
}

// Len returns the number of tracked keys.
func (l *Limiter) Len() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.buckets)
}
