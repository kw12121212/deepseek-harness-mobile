package app.deepseek.harness.mobile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshVersionContractTest {
    // 真机 dsh 0.2.1-rc.2 落地页 boot 脚本的实际形状（截取自 docs/qa 验证记录）。
    private val realBootPage = """<!doctype html><html><head><script>globalThis["__DSH_BOOT__"] = {"rev":"7cd59d6e8775","release":{"version":"0.2.1-rc.2","commit":"332895e"},"entries":[{"id":"x"}]};</script><script src="./assets/index.js"></script></head><body></body></html>"""

    @Test fun constantPinsValidatedVersion() {
        assertEquals("0.2.0-rc.2", COMPATIBLE_DSH_VERSION)
    }

    @Test fun parsesReleaseVersionFromObservedLandingPage() {
        assertEquals("0.2.1-rc.2", parseDshBootVersion(realBootPage))
    }

    @Test fun parsesPinnedVersion() {
        assertEquals("0.2.0-rc.2", parseDshBootVersion(realBootPage.replace("0.2.1-rc.2", "0.2.0-rc.2")))
    }

    @Test fun failsClosedWhenBootObjectMissing() {
        assertNull(parseDshBootVersion("<html><body>sign in</body></html>"))
    }

    @Test fun failsClosedWhenReleaseVersionMissingOrEmpty() {
        assertNull(parseDshBootVersion("""globalThis["__DSH_BOOT__"] = {"rev":"abc"};"""))
        assertNull(parseDshBootVersion("""globalThis["__DSH_BOOT__"] = {"release":{"version":""}};"""))
        assertNull(parseDshBootVersion("""globalThis["__DSH_BOOT__"] = {"release":{}};"""))
    }

    @Test fun survivesBracesInsideStringsAndEscapes() {
        assertEquals("0.2.0-rc.2", parseDshBootVersion("""x["__DSH_BOOT__"] = {"release":{"version":"0.2.0-rc.2"},"note":"a{b}c\"q"};"""))
    }

    @Test fun failsClosedOnUnclosedObject() {
        assertNull(parseDshBootVersion("""globalThis["__DSH_BOOT__"] = {"release":{"version":"0.2.0-rc.2"}"""))
    }

    @Test fun treatsOtherVersionsAsMismatch() {
        val version = parseDshBootVersion(realBootPage)
        assertEquals(false, version == COMPATIBLE_DSH_VERSION)
    }

    @Test fun gateProceedsOnPinnedVersion() {
        assertEquals(DshVersionGate.Proceed, dshVersionGateDecision(DshVersionProbe.Version(COMPATIBLE_DSH_VERSION)))
    }

    @Test fun gateRejectsMismatchWithUpstreamVersion() {
        val gate = dshVersionGateDecision(DshVersionProbe.Version("0.2.1-rc.2"))
        assertTrue(gate is DshVersionGate.Reject && gate.upstreamVersion == "0.2.1-rc.2" && gate.messageKey == "mismatch")
    }

    @Test fun gateRejectsUndetectableWithoutVersion() {
        val gate = dshVersionGateDecision(DshVersionProbe.Undetectable)
        assertTrue(gate is DshVersionGate.Reject && gate.upstreamVersion == null && gate.messageKey == "undetectable")
    }

    @Test fun gateSkipsToAuthenticationWhenUnauthenticated() {
        // 裸地址首连（无 token/cookie）探测得认证挑战时不得判不兼容：
        // 放行到既有认证流程，unchanged_behavior 保持。
        assertEquals(DshVersionGate.ProceedToAuthentication, dshVersionGateDecision(DshVersionProbe.Unauthenticated))
    }
}
