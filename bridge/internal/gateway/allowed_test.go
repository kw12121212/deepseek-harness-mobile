package gateway

import "testing"

func TestAttachmentRPCsAreNarrowlyAllowed(t *testing.T) {
	for _, test := range []struct {
		name     string
		endpoint string
		allowed  bool
	}{
		{"read", "session/attachment", true},
		{"upload", "fileUploads/upload", true},
		{"readSuffix", "session/attachmentAnything", false},
		{"readSlash", "session/attachment/", false},
		{"uploadSuffix", "fileUploads/uploadAnything", false},
		{"uploadSlash", "fileUploads/upload/", false},
		{"inventedUpload", "session/upload", false},
		{"wildcard", "fileUploads/*", false},
	} {
		t.Run(test.name, func(t *testing.T) {
			if allowedRPC[test.endpoint] != test.allowed {
				t.Errorf("RPC allowance for %s = %t, want %t", test.endpoint, allowedRPC[test.endpoint], test.allowed)
			}
			frame := `{"type":"open","streamId":"attachment","endpoint":"` + test.endpoint + `","payload":{"args":{}}}`
			if validFrame([]byte(frame)) {
				t.Errorf("attachment endpoint unexpectedly allowed as a stream: %s", test.endpoint)
			}
		})
	}
}

func TestSessionUpdateQueueRPCIsNarrowlyAllowed(t *testing.T) {
	for _, test := range []struct {
		endpoint string
		allowed  bool
	}{
		{"session/updateQueue", true},
		{"session/updateQueueAnything", false},
		{"session/updateQueue/", false},
		{"session/update", false},
		{"session/*", false},
	} {
		t.Run(test.endpoint, func(t *testing.T) {
			if allowedRPC[test.endpoint] != test.allowed {
				t.Errorf("RPC allowance for %s = %t, want %t", test.endpoint, allowedRPC[test.endpoint], test.allowed)
			}
			frame := `{"type":"open","streamId":"queue","endpoint":"` + test.endpoint + `","payload":{"args":{}}}`
			if validFrame([]byte(frame)) {
				t.Errorf("queue endpoint unexpectedly allowed as a stream: %s", test.endpoint)
			}
		})
	}
}

func TestWorkspaceDeleteRPCIsNarrowlyAllowed(t *testing.T) {
	for _, endpoint := range []string{"workspace/delete", "workspace/insertBefore"} {
		if !allowedRPC[endpoint] {
			t.Errorf("workspace RPC endpoint rejected: %s", endpoint)
		}
	}
	for _, endpoint := range []string{"workspace/deleteAnything", "workspace/delete/", "workspace/pinSession", "session/delete", "workspace/*"} {
		if allowedRPC[endpoint] {
			t.Errorf("unexpected RPC allowed: %s", endpoint)
		}
	}
	frame := `{"type":"open","streamId":"delete","endpoint":"workspace/delete","payload":{"args":{}}}`
	if validFrame([]byte(frame)) {
		t.Fatal("workspace delete unexpectedly allowed as a stream")
	}
}

func TestWorkspaceRenameRPCIsNarrowlyAllowed(t *testing.T) {
	for _, endpoint := range []string{"workspace/rename", "workspace/insertBefore"} {
		if !allowedRPC[endpoint] {
			t.Errorf("workspace RPC endpoint rejected: %s", endpoint)
		}
	}
	for _, endpoint := range []string{"workspace/renameAnything", "workspace/pinSession", "workspace/*"} {
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
	for _, endpoint := range []string{"workspace/archiveSession", "workspace/unarchiveSession", "workspace/insertSessionBefore"} {
		if !allowedRPC[endpoint] {
			t.Errorf("archive RPC endpoint rejected: %s", endpoint)
		}
	}
	for _, endpoint := range []string{
		"workspace/archiveSessionAnything", "workspace/unarchiveSessionAnything",
		"workspace/archive", "workspace/unarchive", "workspace/*",
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

func TestWorkspaceOrderingRPCsAreNarrowlyAllowed(t *testing.T) {
	for _, test := range []struct {
		endpoint string
		allowed  bool
	}{
		{"workspace/insertBefore", true},
		{"workspace/insertSessionBefore", true},
		{"workspace/insert", false},
		{"workspace/insertBeforeAnything", false},
		{"workspace/*", false},
	} {
		t.Run(test.endpoint, func(t *testing.T) {
			if allowedRPC[test.endpoint] != test.allowed {
				t.Errorf("RPC allowance for %s = %t, want %t", test.endpoint, allowedRPC[test.endpoint], test.allowed)
			}
			frame := `{"type":"open","streamId":"order","endpoint":"` + test.endpoint + `","payload":{"args":{}}}`
			if validFrame([]byte(frame)) {
				t.Errorf("ordering endpoint unexpectedly allowed as a stream: %s", test.endpoint)
			}
		})
	}
}
