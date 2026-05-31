package com.nuttavern.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuttavern.data.persona.PersonaRepository
import com.nuttavern.data.persona.UserPersona
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 用户身份 ViewModel(设置页 / 编辑页 / 抽屉 picker 共用)。
 *
 * 这里只关心**身份管理本身**:列表展示、增删、默认设置、排序、单条查询。
 * **不**关心"当前会话用哪个身份" — 那是 [ChatViewModel.currentPersonaId] 的职责,
 * 持久化到 `conversations.personaId` 字段,生命周期与会话绑定。
 *
 * - 列表页订阅 [items],已包含"无"伪卡和默认标志。
 * - 编辑页通过 [findById] 取一次身份快照(普通 Flow,不挂 stateIn,避免 viewModelScope 里累积订阅);
 *   编辑过程的本地草稿由 Screen 自己保留,保存时整体 [upsert] 写回。
 * - 抽屉 picker 切换会话身份调 [ChatViewModel.selectPersonaForCurrentConversation],
 *   写回当前会话的 personaId,本 ViewModel 不参与。
 */
@HiltViewModel
class UserPersonaViewModel @Inject constructor(
    private val repository: PersonaRepository,
) : ViewModel() {

    /**
     * 列表项视图模型。
     *
     * @property persona 身份本体(可能是"无"伪卡)。
     * @property isDefault 是否设置页选定的默认身份。
     */
    data class PersonaListItem(
        val persona: UserPersona,
        val isDefault: Boolean,
    )

    val items: StateFlow<List<PersonaListItem>> = combine(
        repository.personas,
        repository.defaultPersonaId,
    ) { personas, defaultId ->
        personas.map { persona ->
            PersonaListItem(
                persona = persona,
                isDefault = persona.id == defaultId,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = listOf(
            PersonaListItem(
                persona = UserPersona.None,
                isDefault = true,
            ),
        ),
    )

    /**
     * 取一份身份快照流(普通 Flow,**不**挂 stateIn)。
     *
     * 编辑页的草稿用 `rememberSaveable`,初值通过 `Flow.first()` / `collectAsState`
     * 一次性获取即可,**不需要持续订阅**。返回 StateFlow 会让每次调用都在
     * `viewModelScope` 里挂一个新 stateIn job,长期重复编辑会泄漏。
     */
    fun findById(id: String): Flow<UserPersona?> = repository.personas
        .map { list -> list.firstOrNull { it.id == id } }

    fun newPersona(): UserPersona = UserPersona()

    fun upsert(persona: UserPersona) {
        viewModelScope.launch { repository.upsert(persona) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun setDefault(id: String) {
        viewModelScope.launch { repository.setDefault(id) }
    }

    /**
     * 列表页拖动结束后提交真实身份顺序。"无"伪卡始终在头部,不参与排序,
     * 调用方传入的列表里也不应包含 [UserPersona.NONE_PERSONA_ID]。
     */
    fun reorderRealPersonas(orderedIds: List<String>) {
        viewModelScope.launch { repository.reorder(orderedIds) }
    }

    /**
     * 编辑页手动选图:把相册选中的图片字节落盘为该身份头像,回调返回 avatarPath 供草稿更新。
     * 落盘失败回调 null。落盘用草稿 id(新身份 [UserPersona] 已生成 UUID),保存时随草稿一起写库。
     */
    fun persistAvatar(personaId: String, bytes: ByteArray, extension: String, onSaved: (String?) -> Unit) {
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching { repository.saveAvatarBytes(personaId, bytes, extension) }.getOrNull()
            }
            onSaved(path)
        }
    }
}
