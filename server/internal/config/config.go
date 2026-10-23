// Package config loads server configuration from environment variables
// (see docs/v3/sync-protocol.md, "服务器配置").
package config

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// MinSecretLen is the minimum JWT secret length in bytes.
const MinSecretLen = 32

// Config is the fully resolved server configuration.
type Config struct {
	Addr              string
	DBPath            string
	JWTSecret         []byte
	AllowRegistration bool
	TrustProxy        bool
	LogLevel          slog.Level
	// SecretFromFile is true when the secret was loaded or generated from
	// <db dir>/jwt.secret rather than the environment.
	SecretFromFile bool
	// SecretGenerated is true when a fresh secret file was written.
	SecretGenerated bool
}

// Load reads configuration from the given lookup function (normally
// os.LookupEnv). It creates the database directory and, when needed, the
// jwt.secret file.
func Load(lookup func(string) (string, bool)) (*Config, error) {
	get := func(k, def string) string {
		if v, ok := lookup(k); ok && strings.TrimSpace(v) != "" {
			return strings.TrimSpace(v)
		}
		return def
	}
	c := &Config{
		Addr:   get("LISTENLY_ADDR", ":8080"),
		DBPath: get("LISTENLY_DB", "./data/listenly.db"),
	}
	var err error
	if c.AllowRegistration, err = parseBool("LISTENLY_ALLOW_REGISTRATION", get("LISTENLY_ALLOW_REGISTRATION", "true")); err != nil {
		return nil, err
	}
	if c.TrustProxy, err = parseBool("LISTENLY_TRUST_PROXY", get("LISTENLY_TRUST_PROXY", "false")); err != nil {
		return nil, err
	}
	switch strings.ToLower(get("LISTENLY_LOG_LEVEL", "info")) {
	case "debug":
		c.LogLevel = slog.LevelDebug
	case "info":
		c.LogLevel = slog.LevelInfo
	case "warn", "warning":
		c.LogLevel = slog.LevelWarn
	case "error":
		c.LogLevel = slog.LevelError
	default:
		return nil, fmt.Errorf("LISTENLY_LOG_LEVEL: must be one of debug, info, warn, error")
	}

	dir := filepath.Dir(c.DBPath)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, fmt.Errorf("create database directory: %w", err)
	}

	if v, ok := lookup("LISTENLY_JWT_SECRET"); ok && v != "" {
		if len(v) < MinSecretLen {
			return nil, fmt.Errorf("LISTENLY_JWT_SECRET must be at least %d bytes", MinSecretLen)
		}
		c.JWTSecret = []byte(v)
		return c, nil
	}
	c.SecretFromFile = true
	c.JWTSecret, c.SecretGenerated, err = loadOrCreateSecret(filepath.Join(dir, "jwt.secret"))
	if err != nil {
		return nil, err
	}
	return c, nil
}

func parseBool(name, v string) (bool, error) {
	b, err := strconv.ParseBool(v)
	if err != nil {
		return false, fmt.Errorf("%s: invalid boolean %q", name, v)
	}
	return b, nil
}

func loadOrCreateSecret(path string) ([]byte, bool, error) {
	data, err := os.ReadFile(path)
	if err == nil {
		s := strings.TrimSpace(string(data))
		if len(s) < MinSecretLen {
			return nil, false, fmt.Errorf("%s: secret must be at least %d bytes", path, MinSecretLen)
		}
		return []byte(s), false, nil
	}
	if !errors.Is(err, os.ErrNotExist) {
		return nil, false, fmt.Errorf("read %s: %w", path, err)
	}
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return nil, false, err
	}
	s := hex.EncodeToString(raw)
	// O_EXCL: never clobber a secret written concurrently by another process.
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return nil, false, fmt.Errorf("create %s: %w", path, err)
	}
	if _, err := f.WriteString(s + "\n"); err != nil {
		f.Close()
		return nil, false, err
	}
	if err := f.Close(); err != nil {
		return nil, false, err
	}
	return []byte(s), true, nil
}
