package gateway

var allowedRPC = map[string]bool{
	"goal/get":                      true,
	"goals/get":                     true,
	"goals/clear":                   true,
	"goals/edit":                    true,
	"goals/pause":                   true,
	"goals/resume":                  true,
	"commands/list":                 true,
	"session/attachment":            true,
	"fileUploads/upload":            true,
	"session/updateQueue":           true,
	"workspace/delete":              true,
	"workspace/rename":              true,
	"workspace/insertBefore":        true,
	"workspace/insertSessionBefore": true,
	"workspace/archiveSession":      true, "workspace/unarchiveSession": true,
	"session/list": true, "session/create": true, "session/page": true, "session/modelCatalog": true, "session/projections": true, "session/selectModel": true, "session/rename": true, "session/fork": true, "session/prompt": true, "session/cancel": true,
	"workspace/create": true, "directoryPicker/list": true, "directoryPicker/createDirectory": true, "agentPresets/list": true, "agentPresets/select": true, "llm/listProviders": true, "llm/listConfigurableProviders": true, "settings/describe": true, "credentials/describe": true, "credentials/set": true, "commands/execute": true, "messageFeedback/list": true, "messageFeedback/put": true, "messageFeedback/delete": true, "userQuestions/answer": true, "$events/result": true,
}
var allowedStream = map[string]bool{"session/follow": true, "session/control": true, "workspace/follow": true, "userQuestions/attachWait": true, "$events": true}
