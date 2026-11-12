package ru.corlex.internal

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The vectors shared with the TypeScript implementation: the same person must land in the same place. */
private val vectors: JSONObject =
    JSONObject(File("src/test/resources/remote-config.json").readText(Charsets.UTF_8))

class VectorsTest {
    @Test fun `bucketing matches the shared vectors`() {
        val buckets = vectors.getJSONArray("buckets").objects()
        assertTrue(buckets.size >= 50)
        for (v in buckets) {
            assertEquals(v.toString(), v.getInt("bucket"), Bucketing.bucketOf(v.getString("key"), v.getString("userId")))
        }
    }

    @Test fun `a series of 1000 people lands in the same buckets`() {
        val series = vectors.getJSONObject("series")
        val expected = series.getJSONArray("buckets")
        for (i in 0 until series.getInt("count")) {
            assertEquals("person $i", expected.getInt(i), Bucketing.bucketOf(series.getString("key"), series.getString("prefix") + i))
        }
    }

    @Test fun `rollouts and platform overrides match the shared vectors`() {
        for (v in vectors.getJSONArray("resolve").objects()) {
            val param = v.getJSONObject("param")
            val doc = ConfigDoc.parse(
                JSONObject().put("version", 1).put("etag", "\"e\"").put("params", JSONArray().put(param)),
            )!!
            val context = v.getJSONObject("context")
            val resolved = ConfigEval.resolve(doc.params[0], context.getString("userId"), context.optString("platform", "web-none"))
            val expected = v.opt("expected")
            val name = v.getString("name")
            if (expected == null || expected == JSONObject.NULL) {
                assertNull(name, resolved)
            } else {
                assertNotNull(name, resolved)
                assertEquals(name, (expected as JSONObject).get("value").toString(), resolved!!.value.toString())
            }
        }
    }

    @Test fun `experiment assignment matches the shared vectors`() {
        for (v in vectors.getJSONArray("experiments").objects()) {
            val experiment = experiment(v.getJSONObject("experiment"))
            val assigned = ConfigEval.assign(experiment, v.getString("userId"))
            val expected = v.getJSONObject("expected")
            assertEquals(v.getString("name"), expected.getString("variant"), assigned!!.variant)
            assertEquals(v.getString("name"), expected.get("value").toString(), assigned.value.toString())
        }
    }

    @Test fun `a 1000-person experiment series matches`() {
        val series = vectors.getJSONObject("experimentSeries")
        val experiment = experiment(series.getJSONObject("experiment"))
        val expected = series.getJSONArray("variants")
        for (i in 0 until series.getInt("count")) {
            val got = ConfigEval.assign(experiment, series.getString("prefix") + i)!!
            assertEquals("person $i", expected.getInt(i), (got.value as Number).toInt())
        }
    }

    private fun experiment(json: JSONObject): Experiment =
        ConfigDoc.parse(
            JSONObject().put("version", 1).put("etag", "\"e\"").put("params", JSONArray())
                .put("experiments", JSONArray().put(json)),
        )!!.experiments[0]
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteConfigTest {
    private val transport = FakeTransport()
    private val store = MemoryStore()
    private val exposures = ArrayList<Pair<String, String>>()
    private var userId: String? = "user-1"

    private fun doc(version: Int, params: String, experiments: String = "[]") =
        """{"version":$version,"etag":"\"v$version\"","params":$params,"experiments":$experiments}"""

    private fun respond(body: String) {
        transport.configResponse = HttpResult(200, null, body.toByteArray(), null)
    }

    private fun config(live: Boolean = false) = RemoteConfig(
        RemoteConfigOptions(store, transport, "https://config.example", "ck_test_key", "android", live, { userId }, { e, v -> exposures.add(e to v) }, { }),
        directExecutor,
    )

    private val param = """[{"k":"paywall","t":"bool","v":true,"r":10000},{"k":"days","t":"number","v":7,"r":10000},{"k":"title","t":"string","v":"Hi","r":10000},{"k":"android_only","t":"bool","v":false,"r":0,"o":{"android":true}}]"""

    @Test fun `values come from the downloaded document, and only with the same type as the fallback`() {
        respond(doc(1, param))
        val c = config().also { it.start() }
        assertEquals(true, c.get("paywall", false))
        assertEquals(7, (c.get("days", 0) as Number).toInt())
        assertEquals("Hi", c.get("title", "x"))
        assertEquals("a string is not a bool", "fallback", c.get("paywall", "fallback"))
        assertEquals(false, c.get("missing", false))
        assertEquals("the android override wins over the 0 % rollout", true, c.get("android_only", false))
    }

    @Test fun `before the first download the fallback is returned`() {
        val c = config().also { it.start() } // 404
        assertEquals(false, c.get("paywall", false))
        assertTrue(c.awaitReady(10))
    }

    @Test fun `the saved document is used at once next launch and the request sends its ETag`() {
        respond(doc(1, param))
        config().start()
        transport.configResponse = HttpResult(304, null, ByteArray(0))
        val second = config().also { it.start() }
        assertEquals(true, second.get("paywall", false))
        assertEquals("\"v1\"", transport.configRequests.last())
    }

    @Test fun `a newer document waits for the next launch once a value was read`() {
        respond(doc(1, """[{"k":"paywall","t":"bool","v":true,"r":10000}]"""))
        val c = config().also { it.start() }
        assertEquals(true, c.get("paywall", false))
        val updates = ArrayList<Pair<Int, Boolean>>()
        c.addListener { v, applied -> updates.add(v to applied) }
        respond(doc(2, """[{"k":"paywall","t":"bool","v":false,"r":10000}]"""))
        c.refresh()
        assertEquals("the running app does not see a value flip", true, c.get("paywall", false))
        assertEquals(listOf(2 to false), updates)
        assertEquals(false, config().also { it.start() }.get("paywall", true))
    }

    @Test fun `live applies at once`() {
        respond(doc(1, """[{"k":"paywall","t":"bool","v":true,"r":10000}]"""))
        val c = config(live = true).also { it.start() }
        assertEquals(true, c.get("paywall", false))
        respond(doc(2, """[{"k":"paywall","t":"bool","v":false,"r":10000}]"""))
        c.refresh()
        assertEquals(false, c.get("paywall", true))
    }

    @Test fun `an invalid document and a server error change nothing`() {
        respond("""{"version":"x"}""")
        val c = config().also { it.start() }
        assertEquals(false, c.get("paywall", false))
        transport.configResponse = HttpResult(500, null, ByteArray(0))
        c.refresh()
        assertTrue(c.getAll().isEmpty())
    }

    @Test fun `no person id, no value`() {
        respond(doc(1, param))
        userId = null
        assertEquals(false, config().also { it.start() }.get("paywall", false))
    }

    @Test fun `a linked experiment gives the variant's value and exposes once per read`() {
        val experiments = """[{"k":"paywall_test","s":"a1b2c3d4","p":"paywall","v":[{"n":"control","b":5000,"v":false},{"n":"variant_1","b":10000,"v":true}]}]"""
        respond(doc(1, param, experiments))
        val c = config().also { it.start() }
        val assigned = c.experiment("paywall_test")!!
        assertEquals("variant_1", assigned.variant) // user-1 in the shared vectors
        assertEquals(true, c.get("paywall", false))
        assertEquals("paywall_test" to "variant_1", exposures.first())
        assertNull(c.experiment("not_running"))
    }

    @Test fun `stop with wipe forgets the saved document`() {
        respond(doc(1, param))
        val c = config().also { it.start() }
        c.stop(wipe = true)
        assertNull(store.get(RemoteConfig.CONFIG_KEY))
        assertFalse(c.getAll().isNotEmpty())
        assertEquals(false, c.get("paywall", false))
    }
}
