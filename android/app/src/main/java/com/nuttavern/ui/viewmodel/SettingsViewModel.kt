package com.nuttavern.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuttavern.ui.theme.ThemeMode
import com.nuttavern.ui.theme.ThemePresets
import com.nuttavern.ui.theme.ThemeRepository
import com.nuttavern.ui.theme.ThemeSpec
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 设置页 ViewModel。当前阶段只承载主题相关偏好(明暗模式、主题预设)。
 *
 * 后续如果接入提供商配置、应用锁等更重的状态,优先评估是否拆成独立 ViewModel,
 * 而不是把所有设置塞进这一个文件。这里只放"全局开关"型的轻状态。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val themeRepository: ThemeRepository,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = themeRepository.themeMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ThemeMode.System,
    )

    val currentTheme: StateFlow<ThemeSpec> = themeRepository.themeId
        .map { id -> ThemePresets.findById(id) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ThemePresets.Default,
        )

    val availableThemes: List<ThemeSpec> = ThemePresets.all

    fun selectThemeMode(mode: ThemeMode) {
        viewModelScope.launch { themeRepository.setThemeMode(mode) }
    }

    fun selectTheme(themeId: String) {
        viewModelScope.launch { themeRepository.setThemeId(themeId) }
    }
}
