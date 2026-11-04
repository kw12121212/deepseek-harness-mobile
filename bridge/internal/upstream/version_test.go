package upstream

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// realBootPage is the observed shape of a dsh landing page (0.2.1-rc.2 host,
// trimmed to the boot script): globalThis["__DSH_BOOT__"] carries
// release.version. Recorded from a live host during the dsh-version-lock work.
const realBootPage = `<!doctype html><html><head><script>globalThis["__DSH_BOOT__"] = {"rev":"7cd59d6e8775","release":{"version":"0.2.1-rc.2","commit":"332895e"},"entries":[{"id":"x"}]};</script><script src="./assets/index.js"></script></head><body></body></html>`

func bootPageWith(version string) string {
	return strings.Replace(realBootPage, `"0.2.1-rc.2"`, `"`+version+`"`, 1)
}

func TestParseBootVersionExtractsReleaseVersion(t *testing.T) {
	v, e := ParseBootVersion([]byte(realBootPage))
	if e != nil || v != "0.2.1-rc.2" {
		t.Fatalf("expected 0.2.1-rc.2, got %q err %v", v, e)
	}
}

func TestParseBootVersionRealShapes(t *testing.T) {
	cases := []struct {
		name, page, want string
		wantErr          bool
	}{
		{"pinned", bootPageWith("0.2.0-rc.2"), "0.2.0-rc.2", false},
		{"no-boot-object", `<html><body>login</body></html>`, "", true},
		{"boot-without-release", `globalThis["__DSH_BOOT__"] = {"rev":"abc"};`, "", true},
		{"boot-empty-version", `globalThis["__DSH_BOOT__"] = {"release":{"version":""}};`, "", true},
		{"braces-inside-strings", `x["__DSH_BOOT__"] = {"release":{"version":"0.2.0-rc.2"},"note":"a{b}c\"q"};`, "0.2.0-rc.2", false},
		{"unclosed-object", `globalThis["__DSH_BOOT__"] = {"release":{"version":"0.2.0-rc.2"}`, "", true},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			v, e := ParseBootVersion([]byte(c.page))
			if c.wantErr {
				if e == nil {
					t.Fatalf("expected error, got %q", v)
				}
				return
			}
			if e != nil || v != c.want {
				t.Fatalf("expected %q, got %q err %v", c.want, v, e)
			}
		})
	}
}

func TestParseBootVersionHandlesLargePadding(t *testing.T) {
	page := `globalThis["__DSH_BOOT__"] = {"release":{"version":"0.2.0-rc.2"},"pad":"` + strings.Repeat("x", 2<<20) + `"};`
	if v, e := ParseBootVersion([]byte(page)); e != nil || v != "0.2.0-rc.2" {
		t.Fatalf("expected version despite large padding, got %q err %v", v, e)
	}
}

func TestCompatibleDSHVersionCanary(t *testing.T) {
	// 字面量金丝雀：升级常量时这里必须一起改，与 app 侧 canary 呼应。
	if CompatibleDSHVersion != "0.2.0-rc.2" {
		t.Fatalf("pinned version drifted: %q", CompatibleDSHVersion)
	}
}

func newVersionUpstream(t *testing.T, handler http.HandlerFunc) *Client {
	t.Helper()
	srv := httptest.NewServer(handler)
	t.Cleanup(srv.Close)
	c, e := New(srv.URL, "")
	if e != nil {
		t.Fatalf("upstream client: %v", e)
	}
	return c
}

func TestServerVersionAuthenticatedFetch(t *testing.T) {
	var gotAuth string
	var srv *httptest.Server
	srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if token := r.URL.Query().Get("token"); token != "" {
			http.SetCookie(w, &http.Cookie{Name: authCookieName(srv.URL), Value: "session"})
			w.Header().Set("Location", "/")
			w.WriteHeader(303)
			return
		}
		gotAuth = r.Header.Get("Cookie")
		w.Header().Set("Content-Type", "text/html")
		_, _ = w.Write([]byte(bootPageWith("0.2.0-rc.2")))
	}))
	defer srv.Close()
	c, e := New(srv.URL, "launch-token")
	if e != nil {
		t.Fatalf("upstream client: %v", e)
	}
	v, e := c.ServerVersion(context.Background())
	if e != nil || v != CompatibleDSHVersion {
		t.Fatalf("expected %q, got %q err %v", CompatibleDSHVersion, v, e)
	}
	if gotAuth == "" {
		t.Fatal("probe must send authenticated headers")
	}
}

func TestServerVersionFailClosed(t *testing.T) {
	cases := map[string]http.HandlerFunc{
		"unauthorized": func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(401) },
		"plain-login-page": func(w http.ResponseWriter, r *http.Request) {
			_, _ = w.Write([]byte(`<html><body>sign in</body></html>`))
		},
		"server-error": func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(500) },
	}
	for name, h := range cases {
		t.Run(name, func(t *testing.T) {
			c := newVersionUpstream(t, h)
			if v, e := c.ServerVersion(context.Background()); e == nil {
				t.Fatalf("expected fail-closed error, got version %q", v)
			}
		})
	}
}

func TestServerVersionRejectsOversizedLandingPage(t *testing.T) {
	c := newVersionUpstream(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html")
		_, _ = w.Write([]byte(strings.Repeat("x", landingPageLimit+1)))
	})
	if v, e := c.ServerVersion(context.Background()); e == nil {
		t.Fatalf("expected over-limit rejection, got version %q", v)
	}
}

func TestServerVersionConnectionRefused(t *testing.T) {
	c, e := New("http://127.0.0.1:1", "")
	if e != nil {
		t.Fatalf("client: %v", e)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, e := c.ServerVersion(ctx); e == nil {
		t.Fatal("expected upstream unavailable error")
	}
}
