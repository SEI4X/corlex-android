package ru.corlex.symbols

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import org.gradle.api.Project

/** The part of the plugin that touches the Android Gradle Plugin; loaded only when AGP is applied. */
internal object AndroidWiring {
    fun register(project: Project, extension: CorlexSymbolsExtension) {
        val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        components.onVariants { variant ->
            // Debug builds are not obfuscated, and a build type without R8 has no mapping.
            if (!variant.isMinifyEnabled) return@onVariants
            register(project, extension, variant)
        }
    }

    private fun register(project: Project, extension: CorlexSymbolsExtension, variant: ApplicationVariant) {
        val capitalized = variant.name.replaceFirstChar { it.uppercase() }
        val output = variant.outputs.first()
        val upload = project.tasks.register("uploadCorlexMapping$capitalized", UploadMappingTask::class.java) { task ->
            task.mappingFile.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
            task.versionName.set(output.versionName)
            task.versionCode.set(output.versionCode.map { it.toString() })
            task.projectId.set(extension.projectId)
            task.apiUrl.set(extension.apiUrl)
            task.uploadToken.set(extension.uploadToken)
            task.uploadEnabled.set(extension.enabled)
        }
        // «After R8»: the upload reads R8's output, so Gradle runs the minify task first; finalizedBy
        // makes `assemble<Variant>` and `bundle<Variant>` run the upload too.
        project.tasks.matching { it.name == "assemble$capitalized" || it.name == "bundle$capitalized" }
            .configureEach { it.finalizedBy(upload) }
    }
}
