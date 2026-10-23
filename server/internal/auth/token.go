package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// Token lifetimes from the protocol.
const (
	AccessTTL  = 15 * time.Minute
	RefreshTTL = 30 * 24 * time.Hour
	// RefreshGrace is how long after rotation the just-replaced refresh token
	// may be presented again as a lost-response retry.
	RefreshGrace = 60 * time.Second
)

type accessClaims struct {
	DeviceID string `json:"did"`
	// Gen is the device token generation at issuance; tokens below the
	// device's min_access_gen are rejected (used to kill a superseded pair).
	Gen int64 `json:"gen,omitempty"`
	jwt.RegisteredClaims
}

// signAccess issues an HS256 JWT with sub, did, iat, exp and gen.
func signAccess(secret []byte, userID, deviceID string, gen int64, now time.Time) (string, time.Time, error) {
	iat := now.Truncate(time.Second)
	exp := iat.Add(AccessTTL)
	c := accessClaims{
		DeviceID: deviceID,
		Gen:      gen,
		RegisteredClaims: jwt.RegisteredClaims{
			Subject:   userID,
			IssuedAt:  jwt.NewNumericDate(iat),
			ExpiresAt: jwt.NewNumericDate(exp),
		},
	}
	s, err := jwt.NewWithClaims(jwt.SigningMethodHS256, c).SignedString(secret)
	return s, exp, err
}

var errBadAccess = errors.New("auth: invalid access token")

// parseAccess verifies signature (HS256 only), expiry and required claims.
func parseAccess(secret []byte, token string, now func() time.Time) (userID, deviceID string, gen int64, err error) {
	var c accessClaims
	_, err = jwt.ParseWithClaims(token, &c, func(*jwt.Token) (any, error) { return secret, nil },
		jwt.WithValidMethods([]string{jwt.SigningMethodHS256.Alg()}),
		jwt.WithExpirationRequired(),
		jwt.WithIssuedAt(),
		jwt.WithTimeFunc(now),
	)
	if err != nil || c.Subject == "" || c.DeviceID == "" || c.Gen < 0 {
		return "", "", 0, errBadAccess
	}
	return c.Subject, c.DeviceID, c.Gen, nil
}

// newRefreshToken returns a 256-bit random token (base64url) and its
// SHA-256 hex digest, which is all the server stores.
func newRefreshToken() (token, hash string, err error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", "", err
	}
	token = base64.RawURLEncoding.EncodeToString(b)
	return token, hashRefresh(token), nil
}

func hashRefresh(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

// NewID returns a prefixed random identifier such as "u_3f…" or "d_9a…".
func NewID(prefix string) string {
	b := make([]byte, 12)
	if _, err := rand.Read(b); err != nil {
		panic(err) // crypto/rand never fails on supported platforms
	}
	return prefix + hex.EncodeToString(b)
}
