package app.radiusrf.features.sessions

import app.radiusrf.core.debug.DemoDataBus
import app.radiusrf.core.prefs.AppSettings
import app.radiusrf.core.sessions.SessionExportService
import app.radiusrf.core.sessions.SessionRepository
import app.radiusrf.core.testing.FakeSharedPreferences
import app.radiusrf.core.testing.MainDispatcherRule
import app.radiusrf.features.sessions.viewmodel.SessionsViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SessionsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repo = mockk<SessionRepository>(relaxed = true) {
        every { sessions } returns flowOf(emptyList())
    }

    private fun vm() = SessionsViewModel(repo, mockk<SessionExportService>(relaxed = true), AppSettings(FakeSharedPreferences()), DemoDataBus())

    @Test
    fun `selection toggles and keeps at most two, dropping the oldest pick`() {
        val viewModel = vm()
        viewModel.toggleSelection("a")
        viewModel.toggleSelection("b")
        viewModel.toggleSelection("c")
        assertEquals(listOf("b", "c"), viewModel.selected.value)

        viewModel.toggleSelection("b")
        assertEquals(listOf("c"), viewModel.selected.value)

        viewModel.clearSelection()
        assertEquals(emptyList<String>(), viewModel.selected.value)
    }
}
