# UserPersona ↔ Character 临时规划

> 临时规划文档。用于实现前确认范围；落地并更新正式模块文档后可删除。

## 目标

把 `UserPersona.characterConnections` 从“已可编辑的数据”接成完整运行时链路：用户切换角色时，如果某个真实身份绑定了该角色，Nut Tavern 自动把当前新会话 / 目标会话使用的 persona 切到该身份。

这件事不是 1、2、3、4、5 分开的独立任务，而是一条完整链路：数据、编辑入口、切角色自动套用、优先级、删除清理必须一起闭环。

## 已确认现状

- 数据字段已存在：`UserPersona.characterConnections: List<String>`。
- 编辑入口已存在：`UserPersonaEditScreen` 里“绑定角色”打开 `PersonaCharacterBindSheet`，支持多选角色并写回草稿。
- 删除清理已存在：`CharacterViewModel.delete` 调 `PersonaRepository.clearCharacterConnection(id)`，`DataStorePersonaRepository` 会移除所有 persona 中的悬空角色引用。
- 世界书绑定已接通：`UserPersona.lorebookId` 编辑页可选，删除世界书会清理引用，运行时由 `SessionLorebookResolver` 消费。
- 运行时缺口：`ChatViewModel.selectCharacter(characterId)` 目前只切角色 / 会话，不会根据 `characterConnections` 自动选择 persona。

## 设计原则

1. **会话锁定优先**：已经存在的会话有自己的 `conversation.personaId`，切回这条会话时必须尊重会话当前身份，不用角色绑定覆盖。
2. **新会话占位自动套用**：切到某个角色且没有既有会话时，进入新会话占位态，此时应根据角色绑定选择 persona；找不到绑定时回退默认 persona。
3. **用户手动切身份优先于自动绑定**：用户在当前会话 / 新会话占位态手动选择 persona 后，不应被同一个角色的自动规则反复覆盖。
4. **“无”身份是合法选择**：如果默认身份是 `none`，且没有角色绑定身份，新会话保持无身份；绑定关系只来自真实 persona，`UserPersona.None` 不参与 `characterConnections`。
5. **不引入隐藏副作用**：自动选择只发生在“切角色进入新会话占位 / 创建新会话初始 persona”这类明确路径，不在普通消息发送阶段偷偷改 persona。

## 运行时规则草案

### 切到已有会话

`selectCharacter(characterId)` 找到最近会话时：

1. 调 `selectConversation(target.id)`。
2. `selectConversation` 继续按当前逻辑把 `target.personaId` 同步到 `_currentPersonaId`。
3. 不读取 `characterConnections`，不覆盖该会话身份。

理由：会话身份是历史上下文的一部分，用户可能已在该会话里手动改过身份。

### 切到新会话占位

`selectCharacter(characterId)` 找不到会话时：

1. 写 `_currentCharacterId = characterId`。
2. 进入新会话占位态时统一清空会话 / 消息 / 草稿 / 工具 / 世界书选择。
3. 根据 `personaRepository.personas.first()` 查找第一个 `characterId in persona.characterConnections` 的真实 persona。
4. 命中则写 `_currentPersonaId = matchedPersona.id`。
5. 未命中则写 `_currentPersonaId = personaRepository.defaultPersonaId.first()`；默认值可能是 `UserPersona.NONE_PERSONA_ID`。
6. `startNewConversation()` 当前会异步把 `_currentPersonaId` 重置成默认身份,所以实现时必须把新会话初始化拆成“清空占位态 + 初始化 persona + 初始化 preset”三步,并允许 persona 初始化按角色绑定决定。

### 创建会话落库

`ensureCurrentConversation` 会把 `_currentPersonaId.value ?: resolveInitialPersonaIdForCharacter(characterId)` 作为占位态选择结果,再经 `normalizePersonaIdForConversationStorage` 写入 `ConversationSummary.personaId`。因此占位态选中的真实 persona 会自然落库,显式 `none` 会按会话表契约写成 `null`。

### 用户手动切身份

`selectPersonaForCurrentConversation(personaId)` 保持最高优先级：

- 有会话：直接覆盖 `conversation.personaId`。
- 无会话占位：只写 `_currentPersonaId`，后续 `ensureCurrentConversation` 落库。

这条手动路径不应被自动绑定即时覆盖。

## 待实现点

1. 在 `data/persona` 增加纯函数,按角色 id 从真实 persona 列表中选择第一个绑定身份,显式跳过 `UserPersona.None`。
2. 调整 `ChatViewModel.selectCharacter(characterId)` 的“没有历史会话”分支,让新会话占位态选择绑定 persona。
3. 调整 `ChatViewModel.startNewConversation()` 的内部初始化,避免覆盖刚选出的绑定 persona,并让普通“新会话”也按当前角色优先选择绑定 persona。
4. 对新纯函数补单元测试,覆盖命中、未命中、空角色、跳过 `UserPersona.None`、按列表顺序选择。
5. 更新正式文档：`docs/modules/user-persona.md`、`docs/modules/character.md`、`AGENTS.md` 待办区。

## 前端影响

当前无需新增复杂 UI：

- 绑定角色 Sheet 已存在。
- 绑定数量副标题已存在。
- 如需体验提示，可后续在切角色时显示“已自动切换身份：xxx”的 Snackbar，但不是本次必要条件。

若后续决定加提示，再交给前端优化助手评估文案和触发时机。

## 验收标准

1. 某 persona 绑定角色 A；切到角色 A 且没有历史会话时，新会话占位身份自动变成该 persona。
2. 角色 A 已有历史会话且该会话 persona 是另一个身份；切回角色 A 时保留历史会话 persona，不被绑定规则覆盖。
3. 新会话占位态手动切 persona 后，发送第一条消息落库为手动选择的 persona。
4. 删除角色 A 后，所有 persona 的 `characterConnections` 不再包含 A。
5. 没有绑定 persona 时，行为与现在一致：使用默认 persona / none。

## 执行结果

- 已新增 `findPersonaIdBoundToCharacter` / `selectInitialPersonaIdForCharacter` / `normalizePersonaIdForConversationStorage`:按身份列表顺序选择第一个绑定角色的真实 persona,显式跳过 `UserPersona.None`;找不到绑定时回退默认 persona,默认 `none` 在占位态显式保留,写库时归一成 `null`。
- 已接入 `ChatViewModel`:普通开新会话和切到无历史会话的角色时,新会话占位态会按当前角色绑定选择初始 persona;找不到绑定时回退默认 persona / none,创建会话时再把 `none` 归一成 `null` 写库。
- 已保留会话锁定优先级:切回已有会话、删除/归档后回落已有会话、切 assistant 后回落已有会话时,都尊重会话自己的 `personaId`。
- 已补异步保护:用户手动切 persona / preset 或切回已有会话时取消新会话占位初始化 job,避免后台初始化覆盖用户选择。
- 已更新正式文档和 `AGENTS.md` 待办区:该待办从 AGENTS 低优先级列表移除。
