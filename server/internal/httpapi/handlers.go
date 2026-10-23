package httpapi

import (
	"bytes"
	"encoding/json"
	"net/http"
	"strconv"
	"time"

	"github.com/listenly/listenly-server/internal/auth"
)

func ts(unix int64) string { return time.Unix(unix, 0).UTC().Format(time.RFC3339) }

type credentialsRequest struct {
	Username   string `json:"username"`
	Password   string `json:"password"`
	DeviceName string `json:"deviceName"`
}

type sessionResponse struct {
	UserID           string `json:"userId"`
	DeviceID         string `json:"deviceId"`
	AccessToken      string `json:"accessToken"`
	AccessExpiresAt  string `json:"accessExpiresAt"`
	RefreshToken     string `json:"refreshToken"`
	RefreshExpiresAt string `json:"refreshExpiresAt"`
}

func toSession(s auth.Session) sessionResponse {
	return sessionResponse{
		UserID: s.UserID, DeviceID: s.DeviceID,
		AccessToken: s.AccessToken, AccessExpiresAt: s.AccessExpiresAt.UTC().Format(time.RFC3339),
		RefreshToken: s.RefreshToken, RefreshExpiresAt: s.RefreshExpiresAt.UTC().Format(time.RFC3339),
	}
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	if err := s.st.Ping(r.Context()); err != nil {
		s.writeServiceError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (s *Server) handleRegister(w http.ResponseWriter, r *http.Request) {
	var req credentialsRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	sess, err := s.auth.Register(r.Context(), req.Username, req.Password, req.DeviceName)
	if err != nil {
		s.writeServiceError(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, toSession(sess))
}

func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	var req credentialsRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	sess, err := s.auth.Login(r.Context(), req.Username, req.Password, req.DeviceName)
	if err != nil {
		s.writeServiceError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, toSession(sess))
}

func (s *Server) handleRefresh(w http.ResponseWriter, r *http.Request) {
	var req struct {
		RefreshToken string `json:"refreshToken"`
	}
	if !decodeJSON(w, r, &req) {
		return
	}
	sess, err := s.auth.Refresh(r.Context(), req.RefreshToken)
	if err != nil {
		s.writeServiceError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, toSession(sess))
}

func (s *Server) handleLogout(w http.ResponseWriter, r *http.Request) {
	if err := s.auth.Logout(r.Context(), identity(r.Context())); err != nil {
		s.writeServiceError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

type deviceView struct {
	ID         string `json:"id"`
	Name       string `json:"name"`
	CreatedAt  string `json:"createdAt"`
	LastSeenAt string `json:"lastSeenAt"`
	Current    bool   `json:"current"`
}

func (s *Server) handleMe(w http.ResponseWriter, r *http.Request) {
	id := identity(r.Context())
	acc, err := s.auth.Me(r.Context(), id)
	if err != nil {
		s.writeServiceError(w, r, err)
		return
	}
	devs := make([]deviceView, 0, len(acc.Devices))
	for _, d := range acc.Devices {
		devs = append(devs, deviceView{ID: d.ID, Name: d.Name, CreatedAt: ts(d.CreatedAt), LastSeenAt: ts(d.LastSeenAt), Current: d.ID == id.DeviceID})
	}
	writeJSON(w, http.StatusOK, struct {
		UserID   string       `json:"userId"`
		Username string       `json:"username"`
		Devices  []deviceView `json:"devices"`
	}{acc.UserID, acc.Username, devs})
}

func (s *Server) handleDeleteDevice(w http.ResponseWriter, r *http.Request) {
	if err := s.auth.DeleteDevice(r.Context(), identity(r.Context()), r.PathValue("id")); err != nil {
		s.writeServiceError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleDeleteMe(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Password string `json:"password"`
	}
	if !decodeJSON(w, r, &req) {
		return
	}
	if err := s.auth.DeleteAccount(r.Context(), identity(r.Context()), req.Password); err != nil {
		s.writeServiceError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

type recordView struct {
	Collection string          `json:"collection"`
	Key        string          `json:"key"`
	Value      json.RawMessage `json:"value"`
	Deleted    bool            `json:"deleted"`
	Clock      int64           `json:"clock"`
	Device     string          `json:"device"`
	Seq        int64           `json:"seq"`
}

type syncResponse struct {
	Accepted int          `json:"accepted"`
	Ignored  int          `json:"ignored"`
	Rejected any          `json:"rejected"`
	Changes  []recordView `json:"changes"`
	Cursor   int64        `json:"cursor"`
	HasMore  bool         `json:"hasMore"`
}

func (s *Server) handleSync(w http.ResponseWriter, r *http.Request) {
	id := identity(r.Context())
	if rateLimited(w, s.syncLimiter, id.UserID) {
		return
	}
	var req struct {
		Cursor  json.RawMessage   `json:"cursor"`
		Changes []json.RawMessage `json:"changes"`
	}
	if !decodeJSON(w, r, &req) {
		return
	}
	var cursor int64
	if c := bytes.TrimSpace(req.Cursor); len(c) > 0 && string(c) != "null" {
		n, err := strconv.ParseInt(string(c), 10, 64)
		if err != nil || n < 0 {
			writeError(w, http.StatusBadRequest, "bad_request")
			return
		}
		cursor = n
	}
	res, err := s.sync.Sync(r.Context(), id.UserID, id.DeviceID, cursor, req.Changes)
	if err != nil {
		s.writeServiceError(w, r, err)
		return
	}
	out := syncResponse{
		Accepted: res.Accepted, Ignored: res.Ignored, Rejected: res.Rejected,
		Changes: make([]recordView, 0, len(res.Changes)), Cursor: res.Cursor, HasMore: res.HasMore,
	}
	for _, c := range res.Changes {
		out.Changes = append(out.Changes, recordView{
			Collection: c.Collection, Key: c.Key, Value: json.RawMessage(c.Value),
			Deleted: c.Deleted, Clock: c.Clock, Device: c.Device, Seq: c.Seq,
		})
	}
	writeJSON(w, http.StatusOK, out)
}
