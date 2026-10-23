package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"

	"github.com/listenly/listenly-server/internal/auth"
	"github.com/listenly/listenly-server/internal/store"
	"github.com/listenly/listenly-server/internal/syncsvc"
)

// MaxBodyBytes is the protocol request body limit (1 MiB).
const MaxBodyBytes = 1 << 20

var messages = map[string]string{
	"bad_request":           "请求格式错误",
	"too_many_changes":      "单次最多提交 500 条变更",
	"weak_password":         "密码长度需为 8–128 字节",
	"bad_username":          "用户名需为 3–32 位小写字母、数字或 _ . -",
	"unauthorized":          "未登录或登录已过期",
	"invalid_credentials":   "用户名或密码错误",
	"invalid_refresh_token": "登录凭证无效或已过期，请重新登录",
	"registration_closed":   "此服务器已关闭注册",
	"not_found":             "资源不存在",
	"username_taken":        "用户名已被占用",
	"body_too_large":        "请求体超过 1 MiB",
	"rate_limited":          "请求过于频繁，请稍后再试",
	"internal":              "服务器内部错误",
}

type errorBody struct {
	Error struct {
		Code    string `json:"code"`
		Message string `json:"message"`
	} `json:"error"`
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	if v != nil {
		_ = json.NewEncoder(w).Encode(v)
	}
}

func writeError(w http.ResponseWriter, status int, code string) {
	var b errorBody
	b.Error.Code = code
	b.Error.Message = messages[code]
	writeJSON(w, status, b)
}

// writeServiceError maps service-layer errors to protocol errors; anything
// unknown is logged and reported as 500 internal.
func (s *Server) writeServiceError(w http.ResponseWriter, r *http.Request, err error) {
	switch {
	case errors.Is(err, auth.ErrBadUsername):
		writeError(w, http.StatusBadRequest, "bad_username")
	case errors.Is(err, auth.ErrWeakPassword):
		writeError(w, http.StatusBadRequest, "weak_password")
	case errors.Is(err, auth.ErrBadDeviceName):
		writeError(w, http.StatusBadRequest, "bad_request")
	case errors.Is(err, syncsvc.ErrTooManyChanges):
		writeError(w, http.StatusBadRequest, "too_many_changes")
	case errors.Is(err, auth.ErrUnauthorized), errors.Is(err, store.ErrNotFound):
		// store.ErrNotFound here means the account vanished mid-request.
		writeError(w, http.StatusUnauthorized, "unauthorized")
	case errors.Is(err, auth.ErrInvalidCredentials):
		writeError(w, http.StatusUnauthorized, "invalid_credentials")
	case errors.Is(err, auth.ErrInvalidRefreshToken):
		writeError(w, http.StatusUnauthorized, "invalid_refresh_token")
	case errors.Is(err, auth.ErrRegistrationClosed):
		writeError(w, http.StatusForbidden, "registration_closed")
	case errors.Is(err, auth.ErrNotFound):
		writeError(w, http.StatusNotFound, "not_found")
	case errors.Is(err, auth.ErrUsernameTaken):
		writeError(w, http.StatusConflict, "username_taken")
	default:
		s.log.ErrorContext(r.Context(), "request failed", slog.String("request_id", requestID(r.Context())), slog.Any("err", err))
		writeError(w, http.StatusInternalServerError, "internal")
	}
}

// decodeJSON reads exactly one JSON value (≤ 1 MiB) into v. It writes the
// error response itself and returns false on failure.
func decodeJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	if r.ContentLength > MaxBodyBytes {
		writeError(w, http.StatusRequestEntityTooLarge, "body_too_large")
		return false
	}
	body := http.MaxBytesReader(w, r.Body, MaxBodyBytes)
	dec := json.NewDecoder(body)
	err := dec.Decode(v)
	if err == nil {
		// Reject trailing garbage after the JSON value.
		if err2 := dec.Decode(&struct{}{}); !errors.Is(err2, io.EOF) {
			err = err2
			if err == nil {
				err = errors.New("trailing data")
			}
		}
	}
	if err != nil {
		var mbe *http.MaxBytesError
		if errors.As(err, &mbe) {
			writeError(w, http.StatusRequestEntityTooLarge, "body_too_large")
		} else {
			writeError(w, http.StatusBadRequest, "bad_request")
		}
		return false
	}
	return true
}
