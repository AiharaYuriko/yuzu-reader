package eu.kanade.tachiyomi.data.updater

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService

class AppUpdateVersionTest {
    @Test
    fun `only newer stable versions are offered`() = runBlocking<Unit> {
        var release: Release? = null
        val checker = GetApplicationRelease(
            object : ReleaseService {
                override suspend fun latest(arguments: GetApplicationRelease.Arguments) = release
            },
        )
        val arguments = GetApplicationRelease.Arguments(
            isFoss = false,
            isNightly = false,
            commitCount = 0,
            versionName = "0.21.4",
            repository = GITHUB_REPO,
        )

        checker.await(arguments) shouldBe GetApplicationRelease.Result.NoNewUpdate
        for (tag in listOf("v0.21.3", "v0.21.4", "v0.21", "v0.21.5-rc1")) {
            release = Release(tag, "", "", "")
            checker.await(arguments) shouldBe GetApplicationRelease.Result.NoNewUpdate
        }
        val next = Release("v0.21.5", "", "", "")
        release = next
        checker.await(arguments) shouldBe GetApplicationRelease.Result.NewUpdate(next)
    }
}
