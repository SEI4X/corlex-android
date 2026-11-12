package ru.corlex.symbols

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property

/**
 * ```
 * corlex {
 *     projectId.set("…")      // or -Pcorlex.projectId / CORLEX_PROJECT_ID
 *     apiUrl.set("https://…") // or -Pcorlex.apiUrl / CORLEX_API_URL
 *     // the token comes from -Pcorlex.uploadToken or CORLEX_UPLOAD_TOKEN: do not commit it
 * }
 * ```
 */
public abstract class CorlexSymbolsExtension {
    public abstract val projectId: Property<String>
    public abstract val apiUrl: Property<String>
    public abstract val uploadToken: Property<String>

    /** `false` switches the upload off (for a CI job that builds but does not publish). */
    public abstract val enabled: Property<Boolean>
}

/** Registers `uploadCorlexMapping<Variant>` for every release variant of an Android application. */
public class CorlexSymbolsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("corlex", CorlexSymbolsExtension::class.java)
        extension.enabled.convention(true)
        extension.projectId.convention(setting(project, "corlex.projectId", "CORLEX_PROJECT_ID"))
        extension.apiUrl.convention(setting(project, "corlex.apiUrl", "CORLEX_API_URL"))
        extension.uploadToken.convention(setting(project, "corlex.uploadToken", "CORLEX_UPLOAD_TOKEN"))

        project.pluginManager.withPlugin("com.android.application") {
            AndroidWiring.register(project, extension)
        }
    }

    private fun setting(project: Project, property: String, env: String) =
        project.providers.gradleProperty(property).orElse(project.providers.environmentVariable(env))
}
