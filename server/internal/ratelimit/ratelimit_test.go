package ratelimit

import (
	"sync"
	"testing"
	"time"
)

type clock struct {
	mu sync.Mutex
	t  time.Time
}

func (c *clock) now() time.Time      { c.mu.Lock(); defer c.mu.Unlock(); return c.t }
func (c *clock) add(d time.Duration) { c.mu.Lock(); c.t = c.t.Add(d); c.mu.Unlock() }

func TestBurstThenRefill(t *testing.T) {
	c := &clock{t: time.Unix(1_700_000_000, 0)}
	l := New(10, time.Minute, c.now)
	for i := range 10 {
		if ok, _ := l.Allow("1.2.3.4"); !ok {
			t.Fatalf("request %d denied", i)
		}
	}
	ok, wait := l.Allow("1.2.3.4")
	if ok {
		t.Fatal("11th request allowed")
	}
	if wait <= 0 || wait > 6*time.Second {
		t.Fatalf("wait = %v, want ~6s", wait)
	}
	if got := RetryAfterSeconds(wait); got != 6 {
		t.Fatalf("Retry-After = %d", got)
	}
	// other keys are independent
	if ok, _ := l.Allow("5.6.7.8"); !ok {
		t.Fatal("independent key denied")
	}
	c.add(6 * time.Second)
	if ok, _ := l.Allow("1.2.3.4"); !ok {
		t.Fatal("not refilled after 6s")
	}
	if ok, _ := l.Allow("1.2.3.4"); ok {
		t.Fatal("only one token should have refilled")
	}
	c.add(time.Hour)
	for i := range 10 {
		if ok, _ := l.Allow("1.2.3.4"); !ok {
			t.Fatalf("after long idle request %d denied (burst should cap at 10)", i)
		}
	}
	if ok, _ := l.Allow("1.2.3.4"); ok {
		t.Fatal("burst exceeded cap")
	}
}

func TestSweep(t *testing.T) {
	c := &clock{t: time.Unix(0, 0)}
	l := New(60, time.Minute, c.now)
	l.Allow("a")
	l.Allow("b")
	if l.Len() != 2 {
		t.Fatal("len")
	}
	c.add(500 * time.Millisecond)
	l.Sweep()
	if l.Len() != 2 {
		t.Fatal("swept non-full buckets")
	}
	c.add(2 * time.Second)
	l.Sweep()
	if l.Len() != 0 {
		t.Fatalf("len = %d after sweep", l.Len())
	}
}

func TestRetryAfterMinimum(t *testing.T) {
	if RetryAfterSeconds(10*time.Millisecond) != 1 || RetryAfterSeconds(0) != 1 {
		t.Fatal("minimum 1")
	}
}

func TestConcurrent(t *testing.T) {
	l := New(100, time.Hour, nil)
	var wg sync.WaitGroup
	var mu sync.Mutex
	allowed := 0
	for range 20 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for range 10 {
				if ok, _ := l.Allow("k"); ok {
					mu.Lock()
					allowed++
					mu.Unlock()
				}
			}
		}()
	}
	wg.Wait()
	if allowed != 100 {
		t.Fatalf("allowed = %d, want 100", allowed)
	}
}
