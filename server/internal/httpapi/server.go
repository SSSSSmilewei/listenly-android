// Package httpapi exposes the sync protocol over HTTP using the stdlib
// ServeMux with method patterns.
package httpapi

import (
	"context"
	"log/slog"
	"net/http"
	"time"

	"github.com/listenly/listenly-server/internal/auth"
	"github.com/listenly/listenly-server/internal/ratelimit"
	"github.com/listenly/listenly-server/internal/store"
	"github.com/listenly/listenly-server/internal/syncsvc"
)

// Options configure the HTTP API.
type Options struct {
	Store      *store.Store
	Auth       *auth.Service
	Sync       *syncsvc.Service
	Logger     *slog.Logger
	TrustProxy bool
	// AuthLimiter limits register/login/refresh (and DELETE /v1/me) per IP.
	// nil → 10 per minute.
	AuthLimiter *ratelimit.Limiter
	// SyncLimiter limits /v1/sync per user. nil → 60 per minute.
	SyncLimiter *ratelimit.Limiter
}

// Server is the HTTP handler.
type Server struct {
	st          *store.Store
	auth        *auth.Service
	sync        *syncsvc.Service
	log         *slog.Logger
	trustProxy  bool
	authLimiter *ratelimit.Limiter
	syncLimiter *ratelimit.Limiter
	handler     http.Handler
}

// New builds the router.
func New(o Options) *Server {
	s := &Server{
		st: o.Store, auth: o.Auth, sync: o.Sync, log: o.Logger, trustProxy: o.TrustProxy,
		authLimiter: o.AuthLimiter, syncLimiter: o.SyncLimiter,
	}
	if s.log == nil {
		s.log = slog.Default()
	}
	if s.authLimiter == nil {
		s.authLimiter = ratelimit.New(10, time.Minute, nil)
	}
	if s.syncLimiter == nil {
		s.syncLimiter = ratelimit.New(60, time.Minute, nil)
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", s.handleHealth)
	mux.HandleFunc("POST /v1/auth/register", s.limitIP(s.handleRegister))
	mux.HandleFunc("POST /v1/auth/login", s.limitIP(s.handleLogin))
	mux.HandleFunc("POST /v1/auth/refresh", s.limitIP(s.handleRefresh))
	mux.HandleFunc("POST /v1/auth/logout", s.requireAuth(s.handleLogout))
	mux.HandleFunc("GET /v1/me", s.requireAuth(s.handleMe))
	mux.HandleFunc("DELETE /v1/me", s.limitIP(s.requireAuth(s.handleDeleteMe)))
	mux.HandleFunc("DELETE /v1/devices/{id}", s.requireAuth(s.handleDeleteDevice))
	mux.HandleFunc("POST /v1/sync", s.requireAuth(s.handleSync))
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		writeError(w, http.StatusNotFound, "not_found")
	})
	s.handler = s.withCommon(mux)
	return s
}

// ServeHTTP implements http.Handler.
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) { s.handler.ServeHTTP(w, r) }

// RunJanitor periodically drops idle rate-limit buckets and expired
// refresh tokens until ctx is cancelled.
func (s *Server) RunJanitor(ctx context.Context) {
	t := time.NewTicker(time.Minute)
	defer t.Stop()
	purge := func() {
		if n, err := s.auth.PurgeExpired(ctx); err != nil {
			s.log.Warn("purge expired refresh tokens failed", "err", err)
		} else if n > 0 {
			s.log.Info("purged expired refresh tokens", "count", n)
		}
	}
	purge()
	ticks := 0
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			s.authLimiter.Sweep()
			s.syncLimiter.Sweep()
			if ticks++; ticks%60 == 0 {
				purge()
			}
		}
	}
}
