package gateway

import (
	"app.deepseek.harness/bridge/internal/auth"
	"app.deepseek.harness/bridge/internal/config"
	"app.deepseek.harness/bridge/internal/upstream"
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func fixture(t *testing.T, h http.Handler) (*Gateway, config.Config, *auth.Store, string) {
	t.Helper()
	s := httptest.NewServer(h)
	t.Cleanup(s.Close)
	cfg, e := config.Init(t.TempDir(), config.Config{ProtocolVersion: 1, Listen: "127.0.0.1:3090", Upstream: s.URL, PublicOrigin: "http://127.0.0.1:3090"})
	if e != nil {
		t.Fatal(e)
	}
	store, e := auth.Open(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	code, _ := store.NewPairing(time.Now())
	credential, e := store.Pair(code, "phone", time.Now())
	if e != nil {
		t.Fatal(e)
	}
	up, e := upstream.New(s.URL, "")
	if e != nil {
		t.Fatal(e)
	}
	g := New(cfg, store, up)
	return g, cfg, store, credential.DeviceToken
}
func TestAnonymousNeverReachesUpstream(t *testing.T) {
	var hits atomic.Int32
	g, cfg, _, _ := fixture(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { hits.Add(1) }))
	for _, path := range []string{"/", "/api/session/list", "/v1/rpc", cfg.Routes.RPC, cfg.Routes.Stream, cfg.Routes.Export, "/favicon.ico"} {
		rr := httptest.NewRecorder()
		g.ServeHTTP(rr, httptest.NewRequest("POST", path, strings.NewReader("broken")))
		if rr.Code != 404 || rr.Body.Len() != 0 || rr.Header().Get("Location") != "" {
			t.Fatal("fingerprint", path, rr.Code)
		}
	}
	if hits.Load() != 0 {
		t.Fatal("anonymous upstream request")
	}
}
func TestRawRPCAndUnknownMethod(t *testing.T) {
	var hits atomic.Int32
	payload := `{"type":"client-request","rpcId":"r","method":"session/list","payload":{"args":{"_request":{}}}}`
	g, cfg, _, token := fixture(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		b, _ := io.ReadAll(r.Body)
		if string(b) != payload || r.Header.Get("Authorization") != "" || r.URL.Path != "/api/session/list" {
			t.Error("changed request")
		}
		w.Header().Set("Content-Type", "application/json")
		io.WriteString(w, `{"type":"server-response","rpcId":"r","result":{"ok":true,"value":[1,null]}}`)
	}))
	r := httptest.NewRequest("POST", cfg.Routes.RPC, strings.NewReader(payload))
	r.Header.Set("Authorization", "Bearer "+token)
	rr := httptest.NewRecorder()
	g.ServeHTTP(rr, r)
	if rr.Code != 200 || !bytes.Contains(rr.Body.Bytes(), []byte(`[1,null]`)) {
		t.Fatal("lost RPC result", rr.Code)
	}
	r = httptest.NewRequest("POST", cfg.Routes.RPC, strings.NewReader(strings.Replace(payload, "session/list", "../../", 1)))
	r.Header.Set("Authorization", "Bearer "+token)
	rr = httptest.NewRecorder()
	g.ServeHTTP(rr, r)
	if rr.Code != 400 || hits.Load() != 1 {
		t.Fatal("allowed traversal", rr.Code)
	}
}

func TestRPCBodyLimits(t *testing.T) {
	// Exercise exact limits and the first byte over, including reordered and duplicate method fields.
	for _, tc := range []struct {
		name   string
		fields string
		suffix string
		size   int
		status int
	}{
		{"default_at_limit", `"method":"session/prompt",`, "", 8 << 20, http.StatusOK},
		{"default_over_limit", `"method":"session/prompt",`, "", (8 << 20) + 1, http.StatusRequestEntityTooLarge},
		{"attachment_over_limit", `"method":"session/attachment",`, "", (8 << 20) + 1, http.StatusRequestEntityTooLarge},
		{"upload_between_limits", `"method":"fileUploads/upload",`, "", 9 << 20, http.StatusOK},
		{"upload_at_limit", `"method":"fileUploads/upload",`, "", 32 << 20, http.StatusOK},
		{"upload_over_limit", `"method":"fileUploads/upload",`, "", (32 << 20) + 1, http.StatusRequestEntityTooLarge},
		{"unknown_at_limit", `"method":"unknown/method",`, "", 8 << 20, http.StatusBadRequest},
		{"unknown_over_limit", `"method":"unknown/method",`, "", (8 << 20) + 1, http.StatusRequestEntityTooLarge},
		{"upload_method_last", "", `,"method":"fileUploads/upload"`, 9 << 20, http.StatusOK},
		{"nested_upload_method", `"method":"session/prompt","extra":{"method":"fileUploads/upload"},`, "", (8 << 20) + 1, http.StatusRequestEntityTooLarge},
		{"overwritten_upload_method", `"method":"fileUploads/upload",`, `,"method":"session/prompt"`, 9 << 20, http.StatusRequestEntityTooLarge},
		{"escaped_upload_method", `"m\u0065thod":"fileUploads\/upload",`, "", 9 << 20, http.StatusOK},
	} {
		t.Run(tc.name, func(t *testing.T) {
			prefix := `{"type":"client-request","rpcId":"r",` + tc.fields + `"payload":{"args":{"content":"`
			suffix := `"}}` + tc.suffix + `}`
			body := prefix + strings.Repeat("A", tc.size-len(prefix)-len(suffix)) + suffix
			var hits atomic.Int32
			g, cfg, _, token := fixture(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				hits.Add(1)
				forwarded, err := io.ReadAll(r.Body)
				if err != nil || !bytes.Equal(forwarded, []byte(body)) {
					t.Error("RPC body changed during method detection", err)
				}
				w.Header().Set("Content-Type", "application/json")
				io.WriteString(w, `{"accepted":true}`)
			}))
			r := httptest.NewRequest("POST", cfg.Routes.RPC, strings.NewReader(body))
			r.ContentLength = -1 // Limits must also apply when the body length is unknown.
			r.Header.Set("Authorization", "Bearer "+token)
			rr := httptest.NewRecorder()
			g.ServeHTTP(rr, r)
			if rr.Code != tc.status {
				t.Fatalf("status = %d, want %d; response: %s", rr.Code, tc.status, rr.Body.String())
			}
			if tc.status == http.StatusOK {
				if hits.Load() != 1 || rr.Body.String() != `{"accepted":true}` {
					t.Fatalf("upload not forwarded: hits=%d, response=%s", hits.Load(), rr.Body.String())
				}
			} else if hits.Load() != 0 {
				t.Fatal("rejected RPC reached upstream")
			}
			if tc.status == http.StatusRequestEntityTooLarge && rr.Body.String() != "{\"error\":{\"code\":\"request-too-large\"}}\n" {
				t.Fatalf("changed size error: %s", rr.Body.String())
			}
			if len(g.requests) != 0 {
				t.Fatal("request counter was not released")
			}
		})
	}
}

func TestRPCBodyReadBounded(t *testing.T) {
	padding := strings.Repeat("A", 34<<20)
	for _, tc := range []struct {
		name    string
		prefix  string
		maxRead int64
	}{
		{"default_method", `{"method":"session/prompt","payload":"`, (8 << 20) + 1},
		{"unknown_method", `{"method":"unknown/method","payload":"`, (8 << 20) + 1},
		{"upload_method", `{"method":"fileUploads/upload","payload":"`, (32 << 20) + 1},
		{"missing_method", `{"payload":"`, 32 << 20},
		{"malformed_json", `!`, (8 << 20) + 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			const available = 40 << 20
			body := &io.LimitedReader{R: io.MultiReader(strings.NewReader(tc.prefix), strings.NewReader(padding)), N: available}
			g, cfg, _, token := fixture(t, http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
				t.Error("oversized request reached upstream")
			}))
			r := httptest.NewRequest("POST", cfg.Routes.RPC, body)
			r.Header.Set("Authorization", "Bearer "+token)
			rr := httptest.NewRecorder()
			g.ServeHTTP(rr, r)
			if rr.Code != http.StatusRequestEntityTooLarge || rr.Body.String() != "{\"error\":{\"code\":\"request-too-large\"}}\n" {
				t.Fatalf("status=%d, response=%s", rr.Code, rr.Body.String())
			}
			if consumed := available - body.N; consumed > tc.maxRead {
				t.Fatalf("read %d bytes, limit %d", consumed, tc.maxRead)
			}
		})
	}
}

type rpcReadErrorBody struct {
	body string
}

func (r *rpcReadErrorBody) Read(p []byte) (int, error) {
	if r.body == "" {
		return 0, io.EOF
	}
	n := copy(p, r.body)
	r.body = r.body[n:]
	return n, io.ErrUnexpectedEOF
}

func TestRPCBodyDiscoveryPreservesReadError(t *testing.T) {
	g, cfg, _, token := fixture(t, http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		t.Error("request with a body read error reached upstream")
	}))
	body := &rpcReadErrorBody{body: `{"type":"client-request","rpcId":"r","method":"session/list","payload":{"args":{}}}`}
	r := httptest.NewRequest("POST", cfg.Routes.RPC, body)
	r.Header.Set("Authorization", "Bearer "+token)
	rr := httptest.NewRecorder()
	g.ServeHTTP(rr, r)
	if rr.Code != http.StatusRequestEntityTooLarge || rr.Body.String() != "{\"error\":{\"code\":\"request-too-large\"}}\n" {
		t.Fatalf("status=%d, response=%s", rr.Code, rr.Body.String())
	}
}
func TestPairAndUnsafePaths(t *testing.T) {
	g, cfg, store, _ := fixture(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { t.Error("unexpected upstream") }))
	code, _ := store.NewPairing(time.Now())
	b, _ := json.Marshal(map[string]string{"deviceName": "Pixel 10", "pairingCode": code})
	r := httptest.NewRequest("POST", cfg.Routes.Pair, bytes.NewReader(b))
	rr := httptest.NewRecorder()
	g.ServeHTTP(rr, r)
	var result struct {
		DeviceToken string            `json:"deviceToken"`
		Routes      map[string]string `json:"routes"`
	}
	json.Unmarshal(rr.Body.Bytes(), &result)
	if rr.Code != 200 || result.DeviceToken == "" || result.Routes["rpc"] != cfg.Routes.RPC {
		t.Fatal("pair result")
	}
	rr = httptest.NewRecorder()
	g.ServeHTTP(rr, httptest.NewRequest("POST", cfg.Routes.Pair, bytes.NewReader(b)))
	if rr.Code != 404 {
		t.Fatal("reused pair")
	}
	for _, p := range []string{cfg.Routes.RPC + "?x=1", "/api/../" + strings.TrimPrefix(cfg.Routes.RPC, "/"), "/%61/b"} {
		rr = httptest.NewRecorder()
		g.ServeHTTP(rr, httptest.NewRequest("GET", p, nil))
		if rr.Code != 404 || rr.Header().Get("Location") != "" {
			t.Fatal("path exposure")
		}
	}
}
func TestExportDropsInternalHeaders(t *testing.T) {
	g, cfg, _, token := fixture(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/session.export" || r.URL.Query().Get("sessionId") != "s" {
			t.Error("bad export query")
		}
		w.Header().Set("Set-Cookie", "dsh-secret=value")
		w.Header().Set("Server", "dsh")
		w.Header().Set("Content-Type", "application/zip")
		io.WriteString(w, "ZIP")
	}))
	r := httptest.NewRequest("POST", cfg.Routes.Export, strings.NewReader(`{"sessionId":"s","includeDescendants":true}`))
	r.Header.Set("Authorization", "Bearer "+token)
	rr := httptest.NewRecorder()
	g.ServeHTTP(rr, r)
	if rr.Code != 200 || rr.Body.String() != "ZIP" || rr.Header().Get("Set-Cookie") != "" || rr.Header().Get("Server") != "" {
		t.Fatal("export leak")
	}
}
