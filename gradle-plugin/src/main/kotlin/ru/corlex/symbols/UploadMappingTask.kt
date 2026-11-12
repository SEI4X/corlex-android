package ru.corlex.symbols

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction

/**
 * Uploads one `mapping.txt`. It never fails the build: a missing setting, a missing file, a server
 * that says no or one that cannot be reached each end in a warning, because a crash that stays
 * obfuscated is better than a release that cannot be built.
 */
public abstract class UploadMappingTask : DefaultTask() {
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val mappingFile: RegularFileProperty

    /** `versionName` of the variant: half of the release key. */
    @get:Input
    @get:Optional
    public abstract val versionName: Property<String>

    /** `versionCode` of the variant, the other half. */
    @get:Input
    @get:Optional
    public abstract val versionCode: Property<String>

    @get:Input
    @get:Optional
    public abstract val projectId: Property<String>

    @get:Input
    @get:Optional
    public abstract val apiUrl: Property<String>

    /** The upload token: a secret, so it is not an input (inputs are shown in build scans and cache keys). */
    @get:Internal
    public abstract val uploadToken: Property<String>

    /** `corlex { enabled.set(false) }` turns the upload off. */
    @get:Input
    public abstract val uploadEnabled: Property<Boolean>

    init {
        uploadEnabled.convention(true)
        onlyIf { uploadEnabled.get() }
        description = "Uploads the R8 mapping.txt to Corlex."
        group = "corlex"
        // The upload is a side effect on a server: it runs whenever it is asked for.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    public fun upload() {
        val problem = problem()
        if (problem != null) {
            logger.warn("[corlex] mapping.txt was not uploaded: $problem")
            return
        }
        val request = MappingUpload(
            apiUrl = apiUrl.get(),
            projectId = projectId.get(),
            token = uploadToken.get(),
            version = versionName.get(),
            build = versionCode.get(),
            file = mappingFile.get().asFile,
        )
        report(request, MappingUploader.upload(request))
    }

    /** Why there is nothing to upload, or null. */
    private fun problem(): String? = when {
        !uploadToken.isPresent || uploadToken.get().isBlank() ->
            "no upload token (set CORLEX_UPLOAD_TOKEN or the corlex.uploadToken property)"
        !projectId.isPresent || projectId.get().isBlank() ->
            "no project id (set CORLEX_PROJECT_ID or the corlex.projectId property)"
        !apiUrl.isPresent || apiUrl.get().isBlank() ->
            "no API URL (set CORLEX_API_URL or the corlex.apiUrl property)"
        !versionName.isPresent || !versionCode.isPresent -> "the variant has no versionName / versionCode"
        !mappingFile.isPresent || !mappingFile.get().asFile.isFile ->
            "there is no mapping file (is minifyEnabled on for this build type?)"
        else -> null
    }

    private fun report(request: MappingUpload, result: UploadResult) {
        val release = "${request.version} (${request.build})"
        when (result) {
            is UploadResult.Uploaded -> logger.lifecycle("[corlex] uploaded mapping.txt for $release")
            is UploadResult.Rejected ->
                logger.warn("[corlex] mapping.txt for $release was not uploaded: the server answered ${result.status}")
            is UploadResult.Failed ->
                logger.warn("[corlex] mapping.txt for $release was not uploaded: ${result.reason}")
        }
    }
}
