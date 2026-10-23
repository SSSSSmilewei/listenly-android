// Command listenly-server is the optional sync server for 拾听 Listenly.
//
// Usage:
//
//	listenly-server              run the server (configured via LISTENLY_* env vars)
//	listenly-server healthcheck  probe /healthz on LISTENLY_ADDR (for container health checks)
//	listenly-server backup FILE  write a consistent snapshot of the database to FILE (safe while running)
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/listenly/listenly-server/internal/auth"
	"github.com/listenly/listenly-server/internal/config"
	"github.com/listenly/listenly-server/internal/httpapi"
	"github.com/listenly/listenly-server/internal/store"
	"github.com/listenly/listenly-server/internal/syncsvc"
)

var version = "dev"

func main() {
	if len(os.Args) > 1 {
		switch os.Args[1] {
		case "healthcheck":
			os.Exit(healthcheck())
		case "backup":
			if len(os.Args) != 3 {
				fmt.Fprintln(os.Stderr, "usage: listenly-server backup /path/to/backup.db")
				os.Exit(2)
			}
			if err := backup(os.Args[2]); err != nil {
				fmt.Fprintln(os.Stderr, "backup failed:", err)
				os.Exit(1)
			}
			fmt.Println("backup written to", os.Args[2])
			return
		case "version", "--version":
			fmt.Println(version)
			return
		default:
			fmt.Fprintf(os.Stderr, "unknown command %q (expected: healthcheck, backup, version)\n", os.Args[1])
			os.Exit(2)
		}
	}
	if err := run(); err != nil {
		slog.Error("fatal", "err", err)
		os.Exit(1)
	}
}

func healthcheck() int {
	addr := os.Getenv("LISTENLY_ADDR")
	if addr == "" {
		addr = ":8080"
	}
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	if host == "" || host == "0.0.0.0" || host == "::" {
		host = "127.0.0.1"
	}
	c := http.Client{Timeout: 3 * time.Second}
	resp, err := c.Get("http://" + net.JoinHostPort(host, port) + "/healthz")
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		fmt.Fprintln(os.Stderr, "status", resp.StatusCode)
		return 1
	}
	return 0
}

func backup(dest string) error {
	cfg, err := config.Load(os.LookupEnv)
	if err != nil {
		return err
	}
	ctx := context.Background()
	st, err := store.Open(ctx, cfg.DBPath)
	if err != nil {
		return err
	}
	defer st.Close()
	return st.BackupTo(ctx, dest)
}

func run() error {
	cfg, err := config.Load(os.LookupEnv)
	if err != nil {
		return err
	}
	log := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: cfg.LogLevel}))
	slog.SetDefault(log)
	if cfg.SecretGenerated {
		log.Info("generated new JWT secret file next to the database")
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	st, err := store.Open(ctx, cfg.DBPath)
	if err != nil {
		return err
	}
	defer st.Close()
	sv, _ := st.SchemaVersion(ctx)

	authSvc, err := auth.New(st, auth.Options{Secret: cfg.JWTSecret, AllowRegistration: cfg.AllowRegistration, Logger: log})
	if err != nil {
		return err
	}
	api := httpapi.New(httpapi.Options{
		Store: st, Auth: authSvc, Sync: syncsvc.New(st, nil), Logger: log, TrustProxy: cfg.TrustProxy,
	})

	srv := &http.Server{
		Addr:              cfg.Addr,
		Handler:           api,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    64 << 10,
		ErrorLog:          slog.NewLogLogger(log.Handler(), slog.LevelWarn),
	}
	go api.RunJanitor(ctx)

	errCh := make(chan error, 1)
	go func() {
		log.Info("listening", "addr", cfg.Addr, "db", cfg.DBPath, "schema", sv, "version", version,
			"registration", cfg.AllowRegistration, "trust_proxy", cfg.TrustProxy)
		errCh <- srv.ListenAndServe()
	}()

	select {
	case err := <-errCh:
		if !errors.Is(err, http.ErrServerClosed) {
			return err
		}
	case <-ctx.Done():
		log.Info("shutting down")
		sctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		if err := srv.Shutdown(sctx); err != nil {
			log.Warn("graceful shutdown incomplete", "err", err)
		}
	}
	log.Info("stopped")
	return nil
}
