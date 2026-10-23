package config

import (
	"log/slog"
	"os"
	"path/filepath"
	"testing"
)

func env(m map[string]string) func(string) (string, bool) {
	return func(k string) (string, bool) { v, ok := m[k]; return v, ok }
}

func TestDefaultsAndGeneratedSecret(t *testing.T) {
	dir := t.TempDir()
	db := filepath.Join(dir, "sub", "listenly.db")
	c, err := Load(env(map[string]string{"LISTENLY_DB": db}))
	if err != nil {
		t.Fatal(err)
	}
	if c.Addr != ":8080" || !c.AllowRegistration || c.TrustProxy || c.LogLevel != slog.LevelInfo {
		t.Fatalf("bad defaults: %+v", c)
	}
	if !c.SecretGenerated || len(c.JWTSecret) < MinSecretLen {
		t.Fatalf("secret not generated")
	}
	st, err := os.Stat(filepath.Join(dir, "sub", "jwt.secret"))
	if err != nil {
		t.Fatal(err)
	}
	if st.Mode().Perm() != 0o600 {
		t.Fatalf("perm = %v", st.Mode().Perm())
	}
	c2, err := Load(env(map[string]string{"LISTENLY_DB": db}))
	if err != nil {
		t.Fatal(err)
	}
	if c2.SecretGenerated || string(c2.JWTSecret) != string(c.JWTSecret) {
		t.Fatal("secret not reused")
	}
}

func TestEnvSecretAndValidation(t *testing.T) {
	db := filepath.Join(t.TempDir(), "x.db")
	if _, err := Load(env(map[string]string{"LISTENLY_DB": db, "LISTENLY_JWT_SECRET": "short"})); err == nil {
		t.Fatal("short secret accepted")
	}
	c, err := Load(env(map[string]string{
		"LISTENLY_DB": db, "LISTENLY_JWT_SECRET": "0123456789abcdef0123456789abcdef",
		"LISTENLY_ALLOW_REGISTRATION": "false", "LISTENLY_TRUST_PROXY": "true", "LISTENLY_LOG_LEVEL": "debug",
		"LISTENLY_ADDR": "127.0.0.1:9000",
	}))
	if err != nil {
		t.Fatal(err)
	}
	if c.AllowRegistration || !c.TrustProxy || c.LogLevel != slog.LevelDebug || c.SecretFromFile || c.Addr != "127.0.0.1:9000" {
		t.Fatalf("bad: %+v", c)
	}
	if _, err := Load(env(map[string]string{"LISTENLY_DB": db, "LISTENLY_TRUST_PROXY": "maybe"})); err == nil {
		t.Fatal("bad bool accepted")
	}
	if _, err := Load(env(map[string]string{"LISTENLY_DB": db, "LISTENLY_LOG_LEVEL": "loud"})); err == nil {
		t.Fatal("bad level accepted")
	}
}
