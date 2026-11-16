package tachiyomi.domain.release.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService

@Inject
class GetApplicationRelease(
    private val service: ReleaseService,
) {
    suspend fun await(arguments: Arguments): Result {
        val release = service.latest(arguments) ?: return Result.NoNewUpdate

        // Check if latest version is different from current version
        val isNewVersion = isNewVersion(
            arguments.isNightly,
            arguments.commitCount,
            arguments.versionName,
            release.version,
        )
        return when {
            isNewVersion -> Result.NewUpdate(release)
            else -> Result.NoNewUpdate
        }
    }

    private fun isNewVersion(
        isNightly: Boolean,
        commitCount: Int,
        versionName: String,
        versionTag: String,
    ): Boolean {
        if (isNightly) {
            return (versionTag.removePrefix("r").toIntOrNull() ?: return false) > commitCount
        }
        val latest = parseVersion(versionTag) ?: return false
        val current = parseVersion(versionName) ?: return false
        for ((newPart, oldPart) in latest.zip(current)) {
            if (newPart != oldPart) return newPart > oldPart
        }
        return false
    }

    private fun parseVersion(version: String): List<Int>? {
        val parts = version.removePrefix("v").split('.')
        if (parts.size != 3) return null
        return parts.map { it.toIntOrNull()?.takeIf { part -> part >= 0 } ?: return null }
    }

    data class Arguments(
        val isFoss: Boolean,
        val isNightly: Boolean,
        val commitCount: Int,
        val versionName: String,
        val repository: String,
        val forceCheck: Boolean = false,
    )

    sealed interface Result {
        data class NewUpdate(val release: Release) : Result
        data object NoNewUpdate : Result
        data object OsTooOld : Result
    }
}
