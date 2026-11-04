package main

import (
	"app.deepseek.harness/bridge/internal/config"
	"app.deepseek.harness/bridge/internal/upstream"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestRefusesPlainPublicListener(t *testing.T) {
	c := config.Config{Listen: "0.0.0.0:443", PublicOrigin: "https://example.com"}
	if validateServe(c, "", "", false) == nil {
		t.Fatal("accepted public plaintext")
	}
	if validateServe(c, "", "", true) == nil {
		t.Fatal("proxy mode reachable publicly")
	}
	c.Listen = "127.0.0.1:3090"
	if e := validateServe(c, "", "", true); e != nil {
		t.Fatal(e)
	}
	if validateServe(c, "cert", "", false) == nil {
		t.Fatal("partial TLS accepted")
	}
}

func TestRequireCompatibleUpstreamRefusesWrongVersion(t *testing.T) {
	page := []byte(`globalThis["__DSH_BOOT__"] = {"rev":"r","release":{"version":"0.2.1-rc.2","commit":"c"}};`)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html")
		_, _ = w.Write(page)
	}))
	defer srv.Close()
	up, e := upstream.New(srv.URL, "")
	if e != nil {
		t.Fatal(e)
	}
	err := requireCompatibleUpstream(up)
	if err == nil {
		t.Fatal("accepted mismatched upstream version")
	}
	msg := err.Error()
	for _, want := range []string{"0.2.1-rc.2", upstream.CompatibleDSHVersion, "refusing to start"} {
		if !strings.Contains(msg, want) {
			t.Fatalf("error %q missing %q", msg, want)
		}
	}
}

func TestRequireCompatibleUpstreamAcceptsPinnedVersion(t *testing.T) {
	page := []byte(`globalThis["__DSH_BOOT__"] = {"rev":"r","release":{"version":"` + upstream.CompatibleDSHVersion + `","commit":"c"}};`)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write(page)
	}))
	defer srv.Close()
	up, e := upstream.New(srv.URL, "")
	if e != nil {
		t.Fatal(e)
	}
	if err := requireCompatibleUpstream(up); err != nil {
		t.Fatalf("pinned version refused: %v", err)
	}
}

func TestRequireCompatibleUpstreamFailsClosedOnUnknown(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`<html><body>sign in</body></html>`))
	}))
	defer srv.Close()
	up, e := upstream.New(srv.URL, "")
	if e != nil {
		t.Fatal(e)
	}
	err := requireCompatibleUpstream(up)
	if err == nil {
		t.Fatal("accepted undetectable upstream version")
	}
	msg := err.Error()
	for _, want := range []string{"unable to determine upstream dsh version", upstream.CompatibleDSHVersion, "refusing to start"} {
		if !strings.Contains(msg, want) {
			t.Fatalf("error %q missing %q", msg, want)
		}
	}
}
