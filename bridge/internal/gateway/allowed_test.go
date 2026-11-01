package gateway

import "testing"

func TestWorkspaceRenameRPCIsNarrowlyAllowed(t *testing.T) {
	if !allowedRPC["workspace/rename"] {
		t.Fatal("workspace rename RPC rejected")
	}
	for _, endpoint := range []string{"workspace/renameAnything", "workspace/delete", "workspace/insertBefore", "workspace/pinSession", "workspace/*"} {
		if allowedRPC[endpoint] {
			t.Errorf("unexpected workspace RPC allowed: %s", endpoint)
		}
	}
	frame := `{"type":"open","streamId":"rename","endpoint":"workspace/rename","payload":{"args":{}}}`
	if validFrame([]byte(frame)) {
		t.Fatal("workspace rename unexpectedly allowed as a stream")
	}
}

func TestSessionArchiveRPCsAreNarrowlyAllowed(t *testing.T) {
	for _, endpoint := range []string{"workspace/archiveSession", "workspace/unarchiveSession"} {
		if !allowedRPC[endpoint] {
			t.Errorf("archive RPC endpoint rejected: %s", endpoint)
		}
	}
	for _, endpoint := range []string{
		"workspace/archiveSessionAnything", "workspace/unarchiveSessionAnything",
		"workspace/archive", "workspace/unarchive", "workspace/insertSessionBefore", "workspace/*",
	} {
		if allowedRPC[endpoint] {
			t.Errorf("unexpected workspace RPC endpoint allowed: %s", endpoint)
		}
	}
	for _, endpoint := range []string{
		"workspace/archiveSession", "workspace/unarchiveSession",
		"workspace/archiveSessionAnything", "workspace/unarchiveSessionAnything",
	} {
		frame := `{"type":"open","streamId":"archive","endpoint":"` + endpoint + `","payload":{"args":{}}}`
		if validFrame([]byte(frame)) {
			t.Errorf("archive endpoint unexpectedly allowed as a stream: %s", endpoint)
		}
	}
}
