package upstream

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
)

// CompatibleDSHVersion is the single pinned upstream dsh version this bridge
// is validated against. Any other version — or an undetectable one — is
// rejected at serve startup. Moving to a new version means changing this
// constant through an explicit change with re-validation, the same policy as
// the RPC allowlist.
const CompatibleDSHVersion = "0.2.0-rc.2"

// landingPageLimit bounds the version probe: the __DSH_BOOT__ boot object sits
// in the inline script near the top of the landing page, well under 1 MiB.
const landingPageLimit = 1 << 20

type bootObject struct {
	Release struct {
		Version string `json:"version"`
	} `json:"release"`
}

// ServerVersion fetches the upstream landing page with the bridge's
// authenticated headers and extracts release.version from the embedded
// __DSH_BOOT__ object. This is the version surface the native Web UI boots
// from; no RPC allowlist entry is involved.
func (c *Client) ServerVersion(ctx context.Context) (string, error) {
	h, e := c.Headers()
	if e != nil {
		return "", e
	}
	r, e := http.NewRequestWithContext(ctx, "GET", c.origin+"/", nil)
	if e != nil {
		return "", errors.New("upstream unavailable")
	}
	r.Header = h
	resp, e := c.http.Do(r)
	if e != nil {
		return "", errors.New("upstream unavailable")
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return "", errors.New("upstream landing page unavailable")
	}
	b, e := io.ReadAll(io.LimitReader(resp.Body, landingPageLimit+1))
	if e != nil || len(b) > landingPageLimit {
		return "", errors.New("upstream landing page unreadable")
	}
	return ParseBootVersion(b)
}

// ParseBootVersion extracts release.version from a dsh landing page body.
// It returns an error when the boot object or the version is absent, so
// callers fail closed on unknown upstreams.
func ParseBootVersion(body []byte) (string, error) {
	obj, ok := extractBootObject(body)
	if !ok {
		return "", errors.New("upstream landing page has no __DSH_BOOT__ object")
	}
	var boot bootObject
	if json.Unmarshal(obj, &boot) != nil || boot.Release.Version == "" {
		return "", errors.New("upstream __DSH_BOOT__ object has no release.version")
	}
	return boot.Release.Version, nil
}

// extractBootObject locates the JSON object assigned to globalThis["__DSH_BOOT__"]
// and slices it out with a string-aware brace scan.
func extractBootObject(body []byte) ([]byte, bool) {
	marker := []byte(`__DSH_BOOT__"]`)
	i := bytes.Index(body, marker)
	if i < 0 {
		return nil, false
	}
	rel := bytes.IndexByte(body[i:], '{')
	if rel < 0 {
		return nil, false
	}
	start := i + rel
	depth := 0
	inString := false
	escaped := false
	for k := start; k < len(body); k++ {
		ch := body[k]
		if inString {
			switch {
			case escaped:
				escaped = false
			case ch == '\\':
				escaped = true
			case ch == '"':
				inString = false
			}
			continue
		}
		switch ch {
		case '"':
			inString = true
		case '{':
			depth++
		case '}':
			depth--
			if depth == 0 {
				return body[start : k+1], true
			}
		}
	}
	return nil, false
}
