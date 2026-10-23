package httpapi

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"log/slog"
	"net"
	"net/http"
	"runtime/debug"
	"strconv"
	"strings"
	"time"

	"github.com/listenly/listenly-server/internal/auth"
	"github.com/listenly/listenly-server/internal/ratelimit"
)

type ctxKey int

const (
	ctxRequestID ctxKey = iota
	ctxIdentity
)

func requestID(ctx context.Context) string {
	s, _ := ctx.Value(ctxRequestID).(string)
	return s
}

func identity(ctx context.Context) auth.Identity {
	id, _ := ctx.Value(ctxIdentity).(auth.Identity)
	return id
}

func newRequestID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

type statusWriter struct {
	http.ResponseWriter
	status int
	bytes  int
}

func (w *statusWriter) WriteHeader(code int) {
	if w.status == 0 {
		w.status = code
	}
	w.ResponseWriter.WriteHeader(code)
}

func (w *statusWriter) Write(b []byte) (int, error) {
	if w.status == 0 {
		w.status = http.StatusOK
	}
	n, err := w.ResponseWriter.Write(b)
	w.bytes += n
	return n, err
}

func (w *statusWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

// withCommon wraps the router with request ID, panic recovery, access log
// and security headers. The access log records only method, path, status,
// size, duration and client IP — never headers, query strings or bodies.
func (s *Server) withCommon(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rid := newRequestID()
		ctx := context.WithValue(r.Context(), ctxRequestID, rid)
		r = r.WithContext(ctx)
		sw := &statusWriter{ResponseWriter: w}
		h := sw.Header()
		h.Set("X-Request-ID", rid)
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("Cache-Control", "no-store")

		defer func() {
			if p := recover(); p != nil {
				if p == http.ErrAbortHandler {
					panic(p)
				}
				s.log.ErrorContext(ctx, "panic", slog.String("request_id", rid), slog.Any("panic", p),
					slog.String("stack", string(debug.Stack())))
				if sw.status == 0 {
					writeError(sw, http.StatusInternalServerError, "internal")
				}
			}
			status := sw.status
			if status == 0 {
				status = http.StatusOK
			}
			s.log.LogAttrs(ctx, slog.LevelInfo, "http",
				slog.String("request_id", rid),
				slog.String("method", r.Method),
				slog.String("path", r.URL.Path),
				slog.Int("status", status),
				slog.Int("bytes", sw.bytes),
				slog.Float64("ms", float64(time.Since(start).Microseconds())/1000),
				slog.String("ip", s.clientIP(r)),
			)
		}()
		next.ServeHTTP(sw, r)
	})
}

// clientIP returns the address used for rate limiting. With TrustProxy the
// right-most X-Forwarded-For entry is used: that is the address the
// (single, trusted) reverse proxy itself observed; entries to its left are
// client-controlled and spoofable.
func (s *Server) clientIP(r *http.Request) string {
	if s.trustProxy {
		if xff := r.Header.Values("X-Forwarded-For"); len(xff) > 0 {
			parts := strings.Split(xff[len(xff)-1], ",")
			for i := len(parts) - 1; i >= 0; i-- {
				if ip := net.ParseIP(strings.TrimSpace(parts[i])); ip != nil {
					return ip.String()
				}
			}
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func rateLimited(w http.ResponseWriter, l *ratelimit.Limiter, key string) bool {
	ok, wait := l.Allow(key)
	if ok {
		return false
	}
	w.Header().Set("Retry-After", strconv.Itoa(ratelimit.RetryAfterSeconds(wait)))
	writeError(w, http.StatusTooManyRequests, "rate_limited")
	return true
}

// limitIP applies the per-IP auth limiter.
func (s *Server) limitIP(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if rateLimited(w, s.authLimiter, s.clientIP(r)) {
			return
		}
		next(w, r)
	}
}

// requireAuth validates the bearer access token.
func (s *Server) requireAuth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		h := r.Header.Get("Authorization")
		scheme, tok, ok := strings.Cut(h, " ")
		if !ok || !strings.EqualFold(scheme, "Bearer") || strings.TrimSpace(tok) == "" {
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		id, err := s.auth.Authenticate(r.Context(), strings.TrimSpace(tok))
		if err != nil {
			s.writeServiceError(w, r, err)
			return
		}
		next(w, r.WithContext(context.WithValue(r.Context(), ctxIdentity, id)))
	}
}
