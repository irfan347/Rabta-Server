package com.irfan.rabta

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Collections

/** Device identity from the real Android Keystore and the real profile editors against a localhost server. */
@RunWith(AndroidJUnit4::class)
class AccountSecurityTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val server = MockWebServer()
    private val posted = Collections.synchronizedList(mutableListOf<Pair<String, JSONObject>>())
    private lateinit var old: List<String>
    private var screen: android.app.Activity? = null
    private fun ui(block: () -> Unit) = inst.runOnMainSync(block)
    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun windows() = WindowInspector.getGlobalWindowViews().flatMap { all(it) }
    private fun until(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10000
        do { inst.waitForIdleSync(); var ok = false; ui { ok = check() }; if (ok) return; Thread.sleep(80) } while (System.currentTimeMillis() < end)
        fail(what)
    }
    private fun text(t: String) = windows().filterIsInstance<TextView>().any { it.isShown && it.text.toString() == t }
    private fun button(label: String) = windows().filterIsInstance<Button>().first { it.isShown && it.text.toString().equals(label, true) }

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                if (request.method == "POST") posted += path to JSONObject(request.body.readUtf8().ifBlank { "{}" })
                return when {
                    path == "/api/me/account" -> MockResponse().setBody("""{"nextUsernameChangeAt":0}""")
                    path == "/api/username/available?u=sara" -> MockResponse().setBody("""{"available":false,"error":"Username already used"}""")
                    path.startsWith("/api/username/available?u=") -> MockResponse().setBody("""{"available":true}""")
                    path == "/api/me/username" -> MockResponse().setBody("""{"username":"ali_new","nextUsernameChangeAt":${System.currentTimeMillis() + 30L * 86400000}}""")
                    path == "/api/me/name" -> MockResponse().setBody("""{"name":"Ali Raza"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val url = server.url("/").toString().trimEnd('/') // resolves the host: never on the main thread
        Session.init(ctx)
        old = listOf(Session.id, Session.name, Session.username, Session.token, Session.serverUrl)
        ctx.getSharedPreferences("appearance", 0).edit().putString("language", "en").putFloat("scale", 1f).commit()
        ui { MessageService.stop(ctx); WsClient.disconnect(); Session.id = "AC0000000001"; Session.name = "Ali"; Session.username = "ali"; Session.token = "local-account-fixture"; Session.serverUrl = url }
    }
    @After fun tearDown() {
        ui { screen?.finish() }; server.shutdown()
        ui { Session.id = old[0]; Session.name = old[1]; Session.username = old[2]; Session.token = old[3]; Session.serverUrl = old[4] }
    }
    private fun openProfile() = (inst.startActivitySync(Intent(ctx, ProfileActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as android.app.Activity).also { screen = it }
    private fun tapRow(label: String) = ui { (windows().filterIsInstance<TextView>().first { it.text == label }.parent.parent as View).performClick() }

    @Test fun keystoreProofIsStableAndVerifiesLikeTheServerChecksIt() {
        val first = DeviceIdentity.proof("login:ali"); val second = DeviceIdentity.proof("login:ali")
        assertEquals("Same phone, same identity", first.getString("id"), second.getString("id"))
        val der = Base64.getDecoder().decode(first.getString("key"))
        assertEquals(MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }.take(32), first.getString("id"))
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        val data = "rabta-device\nlogin:ali\n${first.getString("id")}\n${first.getLong("time")}".toByteArray()
        assertTrue(Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(data); verify(Base64.getDecoder().decode(first.getString("sig"))) })
        assertFalse("A proof is bound to its purpose", Signature.getInstance("SHA256withECDSA").run { initVerify(key); update("rabta-device\nlogin:sara\n${first.getString("id")}\n${first.getLong("time")}".toByteArray()); verify(Base64.getDecoder().decode(first.getString("sig"))) })
        assertNotEquals("Signatures are never reused", first.getString("sig"), second.getString("sig"))
    }

    @Test fun usernameEditorChecksLiveAndSavesOnlyAnAvailableName() {
        openProfile()
        until("Username row") { text("@ali") }
        tapRow("Username")
        until("Editor open") { windows().any { it is EditText && it.isShown } }
        val field = windows().filterIsInstance<EditText>().first { it.isShown }
        ui { field.setText("sara") }
        until("Taken name is reported") { text("Username already used") && !button("Save").isEnabled }
        ui { field.setText("ali_new") }
        until("Free name is offered") { text("✓ @ali_new is available") && button("Save").isEnabled }
        ui { button("Save").performClick() }
        until("Confirmation explains the 30-day rule") { windows().filterIsInstance<TextView>().any { it.isShown && it.text.contains("30 days") } }
        ui { button("Change").performClick() }
        until("Saved") { Session.username == "ali_new" }
        assertEquals("ali_new", posted.last { it.first == "/api/me/username" }.second.getString("username"))
    }

    @Test fun displayNameEditorEnforces30CharactersAndSaves() {
        openProfile()
        until("Display name row") { text("Display name") }
        tapRow("Display name")
        until("Editor open") { windows().any { it is EditText && it.isShown } }
        val field = windows().filterIsInstance<EditText>().first { it.isShown }
        ui { field.setText("x".repeat(31)); button("Save").performClick() }
        until("Too long is refused locally") { text("Display name must be 1–30 characters") }
        assertTrue(posted.none { it.first == "/api/me/name" })
        ui { field.setText("  Ali   Raza "); button("Save").performClick() }
        until("Saved") { Session.name == "Ali Raza" }
        assertEquals("Ali Raza", posted.last { it.first == "/api/me/name" }.second.getString("name"))
    }
}
