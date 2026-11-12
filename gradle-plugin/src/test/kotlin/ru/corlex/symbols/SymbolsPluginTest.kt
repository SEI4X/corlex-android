package ru.corlex.symbols

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private const val MAPPING = "com.example.Secret -> a.a:\n    void reveal() -> a\n"

// A made-up token, not a credential of any server.
private val FAKE_TOKEN = "corlex_up_" + "a".repeat(32)

/** The plugin's tasks against a stub server, through a real Gradle build (TestKit). */
class SymbolsPluginTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = StubServer()

    @Before fun setUp() {
        folder.root.resolve("mapping.txt").writeText(MAPPING)
        folder.root.resolve("settings.gradle.kts").writeText("rootProject.name = \"sample\"\n")
    }

    @After fun tearDown() = server.stop()

    private fun buildFile(extra: String = "", withApiUrl: Boolean = true) {
        val api = if (withApiUrl) "apiUrl.set(\"${server.url}\")" else ""
        folder.root.resolve("build.gradle.kts").writeText(
            """
            plugins { id("ru.corlex.symbols") }
            corlex { projectId.set("p_1"); $api }
            tasks.register<ru.corlex.symbols.UploadMappingTask>("uploadMapping") {
                mappingFile.set(file("mapping.txt"))
                versionName.set("1.2.3")
                versionCode.set("42")
                projectId.set(corlex.projectId)
                apiUrl.set(corlex.apiUrl)
                uploadToken.set(corlex.uploadToken)
                uploadEnabled.set(corlex.enabled)
            }
            $extra
            """.trimIndent(),
        )
    }

    private fun run(vararg args: String, env: Map<String, String> = emptyMap()): BuildResult =
        GradleRunner.create()
            .withProjectDir(folder.root)
            .withPluginClasspath()
            .withEnvironment(System.getenv() + env)
            .withArguments("uploadMapping", "--stacktrace", "--console=plain", *args)
            .build() // a failing build throws: every case here must succeed

    @Test fun `uploads the gzipped mapping with the release key, the token and no secret in the log`() {
        server.respond(201, """{"stored":true,"key":"symbols/p_1/android/1.2.3-42.txt"}""")
        buildFile()
        val result = run("-Pcorlex.uploadToken=$FAKE_TOKEN")

        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/api/projects/p_1/symbols", request.path)
        assertEquals(
            mapOf("platform" to "android", "kind" to "r8_mapping", "version" to "1.2.3", "build" to "42"),
            request.query,
        )
        assertEquals("Bearer $FAKE_TOKEN", request.headers["authorization"])
        assertEquals("gzip", request.headers["content-encoding"])
        assertEquals(MAPPING, request.body)
        assertTrue(result.output, result.output.contains("uploaded mapping.txt for 1.2.3 (42)"))
        assertFalse("the token is not logged", result.output.contains(FAKE_TOKEN))
    }

    @Test fun `settings can come from the environment`() {
        server.respond(201, """{"stored":true}""")
        folder.root.resolve("build.gradle.kts").writeText(
            """
            plugins { id("ru.corlex.symbols") }
            tasks.register<ru.corlex.symbols.UploadMappingTask>("uploadMapping") {
                mappingFile.set(file("mapping.txt")); versionName.set("1.0"); versionCode.set("1")
                projectId.set(corlex.projectId); apiUrl.set(corlex.apiUrl); uploadToken.set(corlex.uploadToken)
            }
            """.trimIndent(),
        )
        run(env = mapOf("CORLEX_PROJECT_ID" to "p_env", "CORLEX_API_URL" to server.url, "CORLEX_UPLOAD_TOKEN" to FAKE_TOKEN))
        assertEquals("/api/projects/p_env/symbols", server.requests.single().path)
    }

    @Test fun `a missing token only warns`() {
        buildFile()
        val result = run(env = mapOf("CORLEX_UPLOAD_TOKEN" to ""))
        assertTrue(result.output, result.output.contains("no upload token"))
        assertTrue(server.requests.isEmpty())
    }

    @Test fun `a missing API URL only warns`() {
        buildFile(withApiUrl = false)
        val result = run("-Pcorlex.uploadToken=$FAKE_TOKEN", env = mapOf("CORLEX_API_URL" to ""))
        assertTrue(result.output, result.output.contains("no API URL"))
    }

    @Test fun `a rejected token (401) only warns and is not retried`() {
        server.respond(401, """{"error":"unauthorized"}""")
        buildFile()
        val result = run("-Pcorlex.uploadToken=$FAKE_TOKEN")
        assertTrue(result.output, result.output.contains("the server answered 401"))
        assertEquals(1, server.requests.size)
    }

    @Test fun `a server error is tried once more, then only warns`() {
        server.respond(503, "{}")
        buildFile()
        val result = run("-Pcorlex.uploadToken=$FAKE_TOKEN")
        assertEquals(2, server.requests.size)
        assertTrue(result.output, result.output.contains("the server answered 503"))
    }

    @Test fun `a server that cannot be reached only warns`() {
        val dead = server.url
        server.stop()
        folder.root.resolve("build.gradle.kts").writeText(
            """
            plugins { id("ru.corlex.symbols") }
            tasks.register<ru.corlex.symbols.UploadMappingTask>("uploadMapping") {
                mappingFile.set(file("mapping.txt")); versionName.set("1.0"); versionCode.set("1")
                projectId.set("p_1"); apiUrl.set("$dead"); uploadToken.set(corlex.uploadToken)
            }
            """.trimIndent(),
        )
        val result = run("-Pcorlex.uploadToken=$FAKE_TOKEN")
        assertTrue(result.output, result.output.contains("was not uploaded"))
    }

    @Test fun `a missing mapping file only warns`() {
        server.respond(201, "{}")
        buildFile()
        File(folder.root, "mapping.txt").delete()
        val result = run("-Pcorlex.uploadToken=$FAKE_TOKEN")
        assertTrue(result.output, result.output.contains("there is no mapping file"))
        assertTrue(server.requests.isEmpty())
    }

    @Test fun `enabled false skips the upload`() {
        server.respond(201, "{}")
        buildFile("corlex.enabled.set(false)")
        val result = run("-Pcorlex.uploadToken=$FAKE_TOKEN")
        assertTrue(server.requests.isEmpty())
        assertFalse(result.output, result.output.contains("uploaded mapping.txt"))
    }
}

class EndpointTest {
    @Test fun `the version and build are URL-encoded`() {
        val request = MappingUpload("https://api.example/", "p 1", "t", "1.0 beta+1", "7", File("x"))
        assertEquals(
            "https://api.example/api/projects/p+1/symbols?platform=android&kind=r8_mapping&version=1.0+beta%2B1&build=7",
            MappingUploader.endpoint(request),
        )
    }
}
