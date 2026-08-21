import { useEffect, useMemo, useState } from 'react'
import {
  createRestriction,
  createTemplate,
  createTrigger,
  deleteRestriction,
  deleteTemplate,
  deleteTrigger,
  fetchMessageCount,
  fetchChannelOverview,
  fetchChannels,
  fetchEnhancedConfig,
  fetchSearchConfig,
  purgeMessages,
  resetRateLimits,
  saveSearchConfig,
  setDefaultTemplate,
  toggleRestriction,
  toggleTrigger,
  updateBasicConfig,
  updatePendingResponseConfig,
  updateContextSettings,
  updateLlmParameters,
  updateRateLimits,
  updateTemplate,
  fetchPersona,
  fetchPersonaBundles,
  fetchPersonasForBot,
  savePersona,
} from './api/client'
import { DatabaseDictionary } from './components/DatabaseDictionary'
import { DbExplorer } from './components/DbExplorer'
import { Section } from './components/Section'
import { DigestPanel } from './pages/digest/DigestPanel'
import { ReactionPanel } from './pages/reaction/ReactionPanel'
import { ConstructorPage } from './pages/constructor/ConstructorPage'
import { MonitoringPanel } from './pages/monitoring/MonitoringPanel'
import { ScanPanel } from './pages/scan/ScanPanel'
import type {
  BasicConfigUpdate,
  ChannelView,
  ChannelOverview,
  ContextSettings,
  EnhancedChatConfig,
  LlmParameters,
  MessageCountResponse,
  MessagePurgeResult,
  RateLimits,
  ResponseTemplate,
  SearchConfig,
  TopicRestriction,
  TriggerCondition,
  Persona,
  PersonaBundle,
  PendingResponseConfigUpdate,
} from './types/api'
import './App.css'

const responseTones = [
  { value: 'NEUTRAL', label: 'Нейтральный' },
  { value: 'FRIENDLY', label: 'Дружелюбный' },
  { value: 'FORMAL', label: 'Формальный' },
  { value: 'CASUAL', label: 'Неформальный' },
  { value: 'ENTHUSIASTIC', label: 'Энергичный' },
  { value: 'CALM', label: 'Спокойный' },
  { value: 'CONFIDENT', label: 'Уверенный' },
  { value: 'SUPPORTIVE', label: 'Поддерживающий' },
  { value: 'DIPLOMATIC', label: 'Дипломатичный' },
]

const responseStyles = [
  { value: 'ADAPTIVE', label: 'Адаптивный' },
  { value: 'INFORMATIVE', label: 'Информативный' },
  { value: 'CONVERSATIONAL', label: 'Разговорный' },
  { value: 'CONCISE', label: 'Коротко' },
  { value: 'DETAILED', label: 'Развернуто' },
  { value: 'ANALYTICAL', label: 'Аналитика' },
  { value: 'EMPATHETIC', label: 'Эмпатичный' },
  { value: 'INSTRUCTIONAL', label: 'Инструкции' },
  { value: 'STORYTELLING', label: 'Рассказы' },
]

const restrictionTypes = [
  { value: 'FORBIDDEN', label: 'Запрещено' },
  { value: 'ALLOWED_ONLY', label: 'Только эти темы' },
  { value: 'MODERATED', label: 'Модерируемые' },
  { value: 'TIME_RESTRICTED', label: 'По времени' },
  { value: 'USER_RESTRICTED', label: 'По пользователям' },
  { value: 'CONTEXT_DEPENDENT', label: 'Контекстно' },
]

const actionTypes = [
  { value: 'IGNORE', label: 'Игнорировать' },
  { value: 'CUSTOM_RESPONSE', label: 'Своя реплика' },
  { value: 'REDIRECT', label: 'Перенаправить' },
  { value: 'MODERATE', label: 'Модерировать' },
  { value: 'LOG_ONLY', label: 'Только лог' },
  { value: 'ESCALATE', label: 'Эскалация' },
]

const triggerTypes = [
  { value: 'KEYWORD_MATCH', label: 'Ключевые слова' },
  { value: 'MENTION_ONLY', label: 'При упоминании' },
  { value: 'TIME_BASED', label: 'Задержка/таймер' },
  { value: 'RANDOM', label: 'Случайно' },
  { value: 'QUESTION_DETECTED', label: 'Вопрос обнаружен' },
  { value: 'SENTIMENT_BASED', label: 'По настроению' },
  { value: 'CONTINUOUS', label: 'Каждое сообщение' },
  { value: 'SCHEDULED', label: 'По расписанию' },
  { value: 'NEGATIVE_REACTION', label: 'Негатив в чате' },
]

const responseFormats = [
  { value: 'TEXT', label: 'Текст' },
  { value: 'MARKDOWN', label: 'Markdown' },
  { value: 'HTML', label: 'HTML' },
  { value: 'JSON', label: 'JSON' },
  { value: 'CODE', label: 'Код' },
]

const searchProviders = [
  { value: 'GOOGLE', label: 'Google' },
  { value: 'BING', label: 'Bing' },
  { value: 'DUCKDUCKGO', label: 'DuckDuckGo' },
]

const defaultContextSettings = (chatConfigId: number | null): ContextSettings => ({
  id: null,
  chat_config_id: chatConfigId,
  history_message_count: 10,
  history_time_window_hours: 24,
  include_user_context: true,
  include_media_descriptions: true,
  context_compression_enabled: false,
  max_context_tokens: 2000,
  preserve_important_messages: true,
})

const defaultLlmParameters = (
  chatConfigId: number | null,
  fallback?: EnhancedChatConfig,
): LlmParameters => ({
  id: null,
  chat_config_id: chatConfigId,
  model_name: 'deepseek-chat',
  temperature: fallback?.temperature ?? 0.7,
  max_tokens: fallback?.max_tokens ?? 1000,
  top_p: 0.9,
  frequency_penalty: 0,
  presence_penalty: 0,
  system_prompt: fallback?.prompt_template ?? null,
  custom_instructions: null,
  response_format: 'TEXT',
})

const defaultRateLimits = (chatConfigId: number | null): RateLimits => ({
  id: null,
  chat_config_id: chatConfigId,
  max_messages_per_minute: null,
  max_messages_per_hour: 20,
  max_messages_per_day: 100,
  current_daily_messages: 0,
  max_tokens_per_day: 50000,
  pending_response_delay_seconds: 0,
  cooldown_after_limit_minutes: 60,
  burst_limit: 3,
  burst_window_seconds: 60,
  user_specific_limits: false,
})

const defaultSearch = (chatId: number): SearchConfig => ({
  chat_id: chatId,
  search_enabled: false,
  auto_search_enabled: false,
  search_provider: 'GOOGLE',
  max_results: 5,
  cache_duration_minutes: 60,
  rate_limit_per_hour: 30,
  include_attribution: true,
  relevance_threshold: 0.6,
  search_triggers: [],
})

const defaultPendingResponseConfig = (
  fallback?: EnhancedChatConfig | null,
): PendingResponseConfigUpdate => ({
  wait_for_human_replies_count: fallback?.wait_for_human_replies_count ?? -1,
  pending_response_delay_seconds: fallback?.rate_limits?.pending_response_delay_seconds ?? 0,
})

type NoticeTone = 'ok' | 'warn' | 'error'

interface Notice {
  message: string
  tone: NoticeTone
}

const numberOrNull = (value: string) => (value === '' ? null : Number(value))

function App() {
  const [activePage, setActivePage] = useState<
    'config' | 'db' | 'explorer' | 'dictionary' | 'persona' | 'digest' | 'reactions' | 'constructor' | 'monitoring' | 'scan'
  >('config')
  const [channels, setChannels] = useState<ChannelView[]>([])
  const [overview, setOverview] = useState<ChannelOverview[]>([])
  const [channelFilter, setChannelFilter] = useState('')
  const [manualId, setManualId] = useState('')
  const [selectedChannel, setSelectedChannel] = useState<ChannelView | null>(null)

  const [config, setConfig] = useState<EnhancedChatConfig | null>(null)
  const [searchConfig, setSearchConfig] = useState<SearchConfig | null>(null)

  const [basicForm, setBasicForm] = useState<BasicConfigUpdate | null>(null)
  const [pendingResponseForm, setPendingResponseForm] = useState<PendingResponseConfigUpdate | null>(
    null,
  )
  const [contextForm, setContextForm] = useState<ContextSettings | null>(null)
  const [llmForm, setLlmForm] = useState<LlmParameters | null>(null)
  const [rateForm, setRateForm] = useState<RateLimits | null>(null)
  const [personaBundles, setPersonaBundles] = useState<PersonaBundle[]>([])
  const [personaList, setPersonaList] = useState<Persona[]>([])
  const [personaForm, setPersonaForm] = useState<Persona | null>(null)
  const [personaLang, setPersonaLang] = useState('base')
  const [selectedBot, setSelectedBot] = useState<string | null>(null)
  const [personaMeta, setPersonaMeta] = useState('')
  const [personaNotice, setPersonaNotice] = useState<Notice | null>(null)
  const [personaLoading, setPersonaLoading] = useState(false)
  const [personaSaving, setPersonaSaving] = useState(false)
  const [newBotId, setNewBotId] = useState('')
  const [newPersonaLang, setNewPersonaLang] = useState('')

  const [newTemplate, setNewTemplate] = useState<Partial<ResponseTemplate>>({
    template_name: '',
    template_content: '',
    response_style: 'ADAPTIVE',
    response_tone: 'NEUTRAL',
    max_response_length: 600,
    is_default: false,
    priority: 1,
    active: true,
  })
  const [editingTemplateId, setEditingTemplateId] = useState<number | null>(null)
  const [templateEditForm, setTemplateEditForm] = useState<Partial<ResponseTemplate> | null>(null)
  const [newTrigger, setNewTrigger] = useState<Partial<TriggerCondition>>({
    condition_name: '',
    trigger_type: 'KEYWORD_MATCH',
    keywords: '',
    mention_required: false,
    probability_percent: 100,
    minimum_gap_minutes: 0,
    active: true,
  })
  const [newRestriction, setNewRestriction] = useState<Partial<TopicRestriction>>({
    restriction_name: '',
    restriction_type: 'FORBIDDEN',
    keywords: '',
    action_type: 'LOG_ONLY',
    custom_response: '',
    active: true,
  })

  const [loadingChannels, setLoadingChannels] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [saving, setSaving] = useState<string | null>(null)
  const [templateNotice, setTemplateNotice] = useState<Notice | null>(null)

  const [dbChatId, setDbChatId] = useState('')
  const [dbMessageCount, setDbMessageCount] = useState<MessageCountResponse | null>(null)
  const [dbPurgeResult, setDbPurgeResult] = useState<MessagePurgeResult | null>(null)
  const [dbConfirmChatId, setDbConfirmChatId] = useState('')
  const [dbConfirmWord, setDbConfirmWord] = useState('')
  const [dbLoadingCount, setDbLoadingCount] = useState(false)
  const [dbPurging, setDbPurging] = useState(false)
  const [dbNotice, setDbNotice] = useState<Notice | null>(null)

  const overviewMap = useMemo(() => {
    const map = new Map<number, ChannelOverview>()
    overview.forEach((o) => map.set(o.chatId, o))
    return map
  }, [overview])

  const formatChannelError = (error: unknown) => {
    const message = error instanceof Error ? error.message : 'Не удалось загрузить чаты'
    if (message.includes('ECONNREFUSED') || message.includes('Failed to fetch')) {
      return `${message}. Проверьте, что бэкенд запущен на 8099 и APP_HTTP_ENABLED=true.`
    }
    if (message.includes('404')) {
      return `${message}. Эндпоинт /api/startup-sync/discover-chats недоступен (включите HTTP).`
    }
    return message
  }

  useEffect(() => {
    loadChannelList()
  }, [])

  const defaultPersona = (botId: string, lang: string): Persona => ({
    botId,
    language: lang,
    name: 'Persona',
    description: '',
    behavior: [],
    traits: [],
    limitations: [],
    metadata: {},
  })

  const loadPersonaBundles = async () => {
    try {
      const bundles = await fetchPersonaBundles()
      setPersonaBundles(bundles)
      const target = selectedBot ?? bundles[0]?.botId ?? null
      if (target) {
        setSelectedBot(target)
        await loadPersonaList(target)
      } else {
        setPersonaList([])
        setPersonaForm(null)
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось загрузить персоны'
      setPersonaNotice({ tone: 'warn', message })
    }
  }

  const loadPersonaList = async (botId: string) => {
    setPersonaLoading(true)
    try {
      const list = await fetchPersonasForBot(botId)
      setPersonaList(list)
      const languages = list.map((item: Persona) =>
        item.language && item.language.length > 0 ? item.language : 'base',
      )
      setPersonaBundles((prev) =>
        prev.map((bundle) =>
          bundle.botId === botId
            ? {
                ...bundle,
                languages,
                previewName:
                  bundle.previewName ?? list.find((p: Persona) => p.language === 'base')?.name ?? list[0]?.name,
                previewDescription:
                  bundle.previewDescription ??
                  list.find((p: Persona) => p.language === 'base')?.description ??
                  list[0]?.description,
              }
            : bundle,
        ),
      )
      const lang =
        list.find((p) => p.language === 'base')?.language ??
        list.find((p) => p.language === 'ru')?.language ??
        list[0]?.language ??
        'base'
      setPersonaLang(lang)
      await loadPersona(botId, lang)
    } catch (error) {
      setPersonaList([])
      setPersonaForm(defaultPersona(botId, 'base'))
      const message = error instanceof Error ? error.message : 'Не удалось загрузить персоны'
      setPersonaNotice({ tone: 'warn', message })
    } finally {
      setPersonaLoading(false)
    }
  }

  const loadPersona = async (botId: string, lang: string) => {
    setPersonaLoading(true)
    try {
      const persona = await fetchPersona(botId, lang)
      setPersonaLang(persona.language)
      setPersonaForm(persona)
      setPersonaMeta(persona.metadata ? JSON.stringify(persona.metadata, null, 2) : '')
      setPersonaNotice({ tone: 'ok', message: 'Персона загружена' })
    } catch (error) {
      setPersonaForm(defaultPersona(botId, lang))
      const message = error instanceof Error ? error.message : 'Не удалось загрузить персону'
      setPersonaNotice({ tone: 'warn', message })
    } finally {
      setPersonaLoading(false)
    }
  }

  const savePersonaForm = async () => {
    if (!personaForm || !selectedBot) return
    let parsedMeta: Record<string, unknown> | null = null
    if (personaMeta.trim().length > 0) {
      try {
        parsedMeta = JSON.parse(personaMeta)
      } catch (e) {
        setPersonaNotice({ tone: 'error', message: 'Metadata: некорректный JSON' })
        return
      }
    }
    setPersonaSaving(true)
    try {
      const saved = await savePersona(selectedBot, personaForm.language, {
        ...personaForm,
        metadata: parsedMeta,
      })
      setPersonaForm(saved)
      setPersonaNotice({ tone: 'ok', message: 'Персона сохранена' })
      await loadPersonaBundles()
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось сохранить персону'
      setPersonaNotice({ tone: 'error', message })
    } finally {
      setPersonaSaving(false)
    }
  }

  useEffect(() => {
    loadPersonaBundles()
  }, [])

  const loadChannelList = async () => {
    setLoadingChannels(true)
    try {
      const [discoverList, overviewList] = await Promise.allSettled([
        fetchChannels(),
        fetchChannelOverview(),
      ])

      if (overviewList.status === 'fulfilled') {
        setOverview(overviewList.value)
        if (overviewList.value.length > 0) {
          setChannels(
            overviewList.value.map((item) => ({
              chatId: item.chatId,
              title: item.title,
              joinStatus: item.joinStatus,
              muteStatus: item.muteStatus,
              lastSeen: item.lastSeen,
            })),
          )
        }
      }

      if (discoverList.status === 'fulfilled' && discoverList.value.length > 0) {
        setChannels(discoverList.value)
      }
      if (discoverList.status === 'rejected' && overviewList.status === 'rejected') {
        throw discoverList.reason ?? overviewList.reason
      }
    } catch (error) {
      console.error('Не удалось получить список чатов', error)
      const message = formatChannelError(error)
      setNotice({ tone: 'warn', message })
    } finally {
      setLoadingChannels(false)
    }
  }

  const hydrateForms = (enhanced: EnhancedChatConfig, search: SearchConfig | null) => {
    setConfig(enhanced)
    setEditingTemplateId(null)
    setTemplateEditForm(null)
    setBasicForm({
      prompt_template: enhanced.prompt_template ?? '',
      enabled: enhanced.enabled,
      max_tokens: enhanced.max_tokens,
      temperature: enhanced.temperature,
      language: enhanced.language ?? '',
      primary_channel_id: enhanced.primary_channel_id,
      context_window_size: enhanced.context_window_size,
      respond_to_forwarded_bot_messages: enhanced.respond_to_forwarded_bot_messages ?? false,
    })
    setPendingResponseForm(defaultPendingResponseConfig(enhanced))
    setContextForm(enhanced.context_settings ?? defaultContextSettings(enhanced.id))
    setLlmForm(enhanced.llm_parameters ?? defaultLlmParameters(enhanced.id, enhanced))
    setRateForm(enhanced.rate_limits ?? defaultRateLimits(enhanced.id))
    setSearchConfig(search ?? defaultSearch(enhanced.channel_id))
  }

  const loadConfig = async (channelId: number, channelMeta?: ChannelView | null) => {
    try {
      const [enhanced, search] = await Promise.all([
        fetchEnhancedConfig(channelId),
        fetchSearchConfig(channelId).catch(() => null),
      ])
      setSelectedChannel(
        channelMeta ?? {
          chatId: channelId,
          title: enhanced.channel_title,
          joinStatus: null,
          muteStatus: null,
          lastSeen: null,
        },
      )
      hydrateForms(enhanced, search)
      setNotice({ tone: 'ok', message: 'Конфиг обновлен' })
    } catch (error) {
      const message =
        error instanceof Error ? error.message : 'Не удалось загрузить конфигурацию для чата'
      setNotice({ tone: 'error', message })
      setConfig(null)
    }
  }

  const filteredChannels = useMemo(() => {
    const filter = channelFilter.toLowerCase().trim()
    const source = overview.length > 0 ? overview : channels
    if (!filter) return source
    return source.filter(
      (c) =>
        c.chatId.toString().includes(filter) ||
        (c.title ? c.title.toLowerCase().includes(filter) : false),
    )
  }, [channels, overview, channelFilter])

  const parseChatId = (raw: string) => {
    const trimmed = raw.trim()
    if (!trimmed) return null
    const numeric = Number(trimmed)
    if (Number.isNaN(numeric) || numeric === 0) return null
    return numeric
  }

  const checkDbMessageCount = async () => {
    const id = parseChatId(dbChatId)
    if (!id) {
      setDbNotice({ tone: 'warn', message: 'Введите chatId' })
      return
    }
    setDbLoadingCount(true)
    setDbNotice(null)
    setDbPurgeResult(null)
    try {
      const result = await fetchMessageCount(id)
      setDbMessageCount(result)
      setDbNotice({
        tone: 'ok',
        message: `Найдено сообщений: ${result.message_count.toLocaleString('ru-RU')}`,
      })
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось получить количество сообщений'
      setDbNotice({ tone: 'error', message })
      setDbMessageCount(null)
    } finally {
      setDbLoadingCount(false)
    }
  }

  const purgeDbMessages = async () => {
    const chatId = parseChatId(dbChatId)
    const confirmChatId = parseChatId(dbConfirmChatId)
    const confirmWord = dbConfirmWord.trim().toUpperCase()
    if (!chatId) {
      setDbNotice({ tone: 'warn', message: 'Введите chatId' })
      return
    }
    if (!confirmChatId || confirmChatId !== chatId || confirmWord !== 'DELETE') {
      setDbNotice({ tone: 'warn', message: 'Для удаления введите chatId и слово DELETE' })
      return
    }
    setDbPurging(true)
    setDbNotice(null)
    try {
      const result = await purgeMessages({ chat_id: chatId, confirm_chat_id: confirmChatId })
      setDbPurgeResult(result)
      setDbMessageCount({ chat_id: chatId, message_count: 0 })
      setDbConfirmWord('')
      setDbConfirmChatId('')
      setDbNotice({
        tone: 'ok',
        message: `Удалено сообщений: ${result.deleted_messages.toLocaleString('ru-RU')}`,
      })
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось очистить сообщения'
      setDbNotice({ tone: 'error', message })
    } finally {
      setDbPurging(false)
    }
  }

  const dbResolvedChatId = parseChatId(dbChatId)
  const dbResolvedConfirmChatId = parseChatId(dbConfirmChatId)
  const dbPurgeReady =
    dbResolvedChatId != null &&
    dbResolvedConfirmChatId != null &&
    dbResolvedChatId === dbResolvedConfirmChatId &&
    dbConfirmWord.trim().toUpperCase() === 'DELETE'

  const saveBasic = async () => {
    if (!config || !basicForm) return
    setSaving('basic')
    try {
      await updateBasicConfig(config.channel_id, basicForm)
      await loadConfig(config.channel_id, selectedChannel)
      setNotice({ tone: 'ok', message: 'Базовые настройки сохранены' })
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось сохранить базовые поля'
      setNotice({ tone: 'error', message })
    } finally {
      setSaving(null)
    }
  }

  const savePendingResponse = async () => {
    if (!config || !pendingResponseForm) return
    setSaving('pending-response')
    try {
      await updatePendingResponseConfig(config.channel_id, pendingResponseForm)
      await loadConfig(config.channel_id, selectedChannel)
      setNotice({ tone: 'ok', message: 'Pending Response настройки сохранены' })
    } catch (error) {
      const message =
        error instanceof Error ? error.message : 'Не удалось обновить Pending Response настройки'
      setNotice({ tone: 'error', message })
    } finally {
      setSaving(null)
    }
  }

  const saveContext = async () => {
    if (!config || !contextForm) return
    setSaving('context')
    try {
      await updateContextSettings(config.channel_id, contextForm)
      setNotice({ tone: 'ok', message: 'Контекст обновлен' })
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось обновить контекст'
      setNotice({ tone: 'error', message })
    } finally {
      setSaving(null)
    }
  }

  const saveLlm = async () => {
    if (!config || !llmForm) return
    setSaving('llm')
    try {
      await updateLlmParameters(config.channel_id, llmForm)
      setNotice({ tone: 'ok', message: 'LLM параметры сохранены' })
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось обновить LLM'
      setNotice({ tone: 'error', message })
    } finally {
      setSaving(null)
    }
  }

  const saveRateLimits = async () => {
    if (!config || !rateForm) return
    setSaving('rate')
    try {
      await updateRateLimits(config.channel_id, rateForm)
      setNotice({ tone: 'ok', message: 'Ограничения сохранены' })
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось обновить лимиты'
      setNotice({ tone: 'error', message })
    } finally {
      setSaving(null)
    }
  }

  const handleResetRate = async () => {
    if (!config) return
    setSaving('rate')
    try {
      await resetRateLimits(config.channel_id)
      setNotice({ tone: 'ok', message: 'Счетчики лимитов сброшены' })
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось сбросить лимиты'
      setNotice({ tone: 'error', message })
    } finally {
      setSaving(null)
    }
  }

  const saveSearch = async () => {
    if (!searchConfig) return
    setSaving('search')
    try {
      const updated = await saveSearchConfig(searchConfig)
      setSearchConfig(updated)
      setNotice({ tone: 'ok', message: 'Поисковая конфигурация сохранена' })
    } catch (error) {
      const message =
        error instanceof Error ? error.message : 'Не удалось обновить поисковую конфигурацию'
      setNotice({ tone: 'error', message })
    } finally {
      setSaving(null)
    }
  }

  const makeDefaultTemplate = async (templateId: number) => {
    if (!config) return
    setSaving('template')
    try {
      await setDefaultTemplate(templateId)
      await loadConfig(config.channel_id, selectedChannel)
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось назначить дефолтный шаблон'
      setNotice({ tone: 'error', message })
    } finally {
      setSaving(null)
    }
  }

  const addTemplate = async () => {
    if (!config) return
    if (!newTemplate.template_name || !newTemplate.template_content) {
      const warn = { tone: 'warn' as NoticeTone, message: 'Заполните название и текст шаблона' }
      setNotice(warn)
      setTemplateNotice(warn)
      return
    }
    setSaving('template')
    try {
      await createTemplate(config.channel_id, newTemplate)
      setNewTemplate((prev) => ({ ...prev, template_name: '', template_content: '' }))
      await loadConfig(config.channel_id, selectedChannel)
      const ok = { tone: 'ok' as NoticeTone, message: 'Шаблон добавлен' }
      setNotice(ok)
      setTemplateNotice(ok)
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось добавить шаблон'
      const err = { tone: 'error' as NoticeTone, message }
      setNotice(err)
      setTemplateNotice(err)
    } finally {
      setSaving(null)
    }
  }

  const toggleTemplateActive = async (template: ResponseTemplate) => {
    setSaving('template')
    try {
      await updateTemplate(template.id, { ...template, active: !template.active })
      if (config) {
        await loadConfig(config.channel_id, selectedChannel)
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось изменить шаблон'
      setNotice({ tone: 'error', message })
    } finally {
      setSaving(null)
    }
  }

  const startEditingTemplate = (template: ResponseTemplate) => {
    setEditingTemplateId(template.id)
    setTemplateEditForm({
      template_name: template.template_name,
      template_content: template.template_content,
      response_style: template.response_style ?? 'ADAPTIVE',
      response_tone: template.response_tone ?? 'NEUTRAL',
      max_response_length: template.max_response_length ?? 600,
      priority: template.priority ?? 1,
      active: template.active,
    })
    setTemplateNotice(null)
  }

  const cancelEditingTemplate = () => {
    setEditingTemplateId(null)
    setTemplateEditForm(null)
  }

  const saveEditingTemplate = async () => {
    if (!config || editingTemplateId == null || !templateEditForm) return
    if (!templateEditForm.template_name || !templateEditForm.template_content) {
      const warn = { tone: 'warn' as NoticeTone, message: 'Заполните название и текст шаблона' }
      setNotice(warn)
      setTemplateNotice(warn)
      return
    }
    setSaving('template')
    try {
      await updateTemplate(editingTemplateId, {
        template_name: templateEditForm.template_name,
        template_content: templateEditForm.template_content,
        response_style: templateEditForm.response_style ?? null,
        response_tone: templateEditForm.response_tone ?? null,
        max_response_length: templateEditForm.max_response_length ?? null,
        priority: templateEditForm.priority ?? null,
        active: templateEditForm.active ?? true,
      })
      await loadConfig(config.channel_id, selectedChannel)
      cancelEditingTemplate()
      const ok = { tone: 'ok' as NoticeTone, message: 'Шаблон обновлен' }
      setNotice(ok)
      setTemplateNotice(ok)
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось обновить шаблон'
      const err = { tone: 'error' as NoticeTone, message }
      setNotice(err)
      setTemplateNotice(err)
    } finally {
      setSaving(null)
    }
  }

  const removeTemplate = async (templateId: number) => {
    setSaving('template')
    try {
      await deleteTemplate(templateId)
      if (config) {
        await loadConfig(config.channel_id, selectedChannel)
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось удалить шаблон'
      setNotice({ tone: 'error', message })
    } finally {
      setSaving(null)
    }
  }

  const addTrigger = async () => {
    if (!config) return
    if (!newTrigger.condition_name) {
      setNotice({ tone: 'warn', message: 'Нужно задать имя триггера' })
      return
    }
    setSaving('trigger')
    try {
      await createTrigger(config.channel_id, newTrigger)
      setNewTrigger((prev) => ({ ...prev, condition_name: '', keywords: '' }))
      await loadConfig(config.channel_id, selectedChannel)
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось создать триггер'
      setNotice({ tone: 'error', message })
    } finally {
      setSaving(null)
    }
  }

  const toggleExistingTrigger = async (trigger: TriggerCondition) => {
    setSaving('trigger')
    try {
      await toggleTrigger(trigger.id)
      if (config) {
        await loadConfig(config.channel_id, selectedChannel)
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось изменить триггер'
      setNotice({ tone: 'error', message })
    } finally {
      setSaving(null)
    }
  }

  const removeTrigger = async (triggerId: number) => {
    setSaving('trigger')
    try {
      await deleteTrigger(triggerId)
      if (config) {
        await loadConfig(config.channel_id, selectedChannel)
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось удалить триггер'
      setNotice({ tone: 'error', message })
    } finally {
      setSaving(null)
    }
  }

  const addRestriction = async () => {
    if (!config) return
    if (!newRestriction.restriction_name) {
      setNotice({ tone: 'warn', message: 'Нужно задать название правила' })
      return
    }
    setSaving('restriction')
    try {
      await createRestriction(config.channel_id, newRestriction)
      setNewRestriction((prev) => ({ ...prev, restriction_name: '', keywords: '', categories: '' }))
      await loadConfig(config.channel_id, selectedChannel)
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось добавить ограничение'
      setNotice({ tone: 'error', message })
    } finally {
      setSaving(null)
    }
  }

  const toggleExistingRestriction = async (restriction: TopicRestriction) => {
    setSaving('restriction')
    try {
      await toggleRestriction(restriction.id)
      if (config) {
        await loadConfig(config.channel_id, selectedChannel)
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось изменить ограничение'
      setNotice({ tone: 'error', message })
    } finally {
      setSaving(null)
    }
  }

  const removeRestriction = async (restrictionId: number) => {
    setSaving('restriction')
    try {
      await deleteRestriction(restrictionId)
      if (config) {
        await loadConfig(config.channel_id, selectedChannel)
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось удалить ограничение'
      setNotice({ tone: 'error', message })
    } finally {
      setSaving(null)
    }
  }

  const statusToneClass = notice ? `notice notice--${notice.tone}` : 'notice'

  const stats = useMemo(() => {
    if (!config) return []
    return [
      {
        label: 'LLM',
        value: `${llmForm?.model_name ?? 'deepseek-chat'} @ ${llmForm?.temperature ?? '—'}`,
      },
      {
        label: 'Рейтлимиты',
        value: `${rateForm?.max_messages_per_hour ?? '—'} /ч · ${
          rateForm?.max_messages_per_day ?? '—'
        } /д`,
      },
      {
        label: 'Контекст',
        value: `${contextForm?.history_message_count ?? '—'} msg · ${
          contextForm?.history_time_window_hours ?? '—'
        } ч`,
      },
      {
        label: 'Триггеры',
        value: `${config.trigger_conditions.length} · тематики ${config.topic_restrictions.length}`,
      },
    ]
  }, [config, contextForm, llmForm, rateForm])

  const defaultTemplate = useMemo(
    () => config?.response_templates.find((tpl) => tpl.is_default),
    [config],
  )

  const personaLanguages = useMemo(
    () =>
      personaList.length > 0
        ? personaList.map((p) => (p.language && p.language.length > 0 ? p.language : 'base'))
        : personaLang
        ? [personaLang]
        : [],
    [personaList, personaLang],
  )

  const updatePersonaArray = (
    field: 'behavior' | 'traits' | 'limitations',
    index: number,
    value: string,
  ) => {
    setPersonaForm((prev) => {
      if (!prev) return prev
      const items = [...(prev[field] ?? [])]
      items[index] = value
      return { ...prev, [field]: items }
    })
  }

  const addPersonaItem = (field: 'behavior' | 'traits' | 'limitations') => {
    setPersonaForm((prev) => {
      if (!prev) return prev
      return { ...prev, [field]: [...(prev[field] ?? []), ''] }
    })
  }

  const removePersonaItem = (field: 'behavior' | 'traits' | 'limitations', index: number) => {
    setPersonaForm((prev) => {
      if (!prev) return prev
      return { ...prev, [field]: prev[field].filter((_, i) => i !== index) }
    })
  }

  const handleCreatePersona = () => {
    if (!selectedBot) {
      setPersonaNotice({ tone: 'warn', message: 'Сначала выберите бот ID' })
      return
    }
    const code = newPersonaLang.trim().toLowerCase()
    if (!code) {
      setPersonaNotice({ tone: 'warn', message: 'Введите код языка' })
      return
    }
    const draft = defaultPersona(selectedBot, code)
    setPersonaList((prev) => {
      if (prev.some((p) => p.language === code)) {
        return prev
      }
      return [...prev, draft]
    })
    setPersonaBundles((prev) =>
      prev.map((bundle) =>
        bundle.botId === selectedBot
          ? {
              ...bundle,
              languages: bundle.languages?.includes(code)
                ? bundle.languages
                : [...(bundle.languages ?? []), code],
            }
          : bundle,
      ),
    )
    setPersonaLang(code)
    setPersonaForm(draft)
    setPersonaMeta('')
    setNewPersonaLang('')
    setPersonaNotice({
      tone: 'warn',
      message: `Новая локаль ${code.toUpperCase()} — заполните и сохраните`,
    })
  }

  const handleCreateBot = () => {
    const botId = newBotId.trim()
    if (!botId) {
      setPersonaNotice({ tone: 'warn', message: 'Введите botId' })
      return
    }
    if (personaBundles.some((bundle) => bundle.botId === botId)) {
      setSelectedBot(botId)
      loadPersonaList(botId)
      setNewBotId('')
      return
    }
    const draft = defaultPersona(botId, 'base')
    setPersonaBundles((prev) => [
      ...prev,
      {
        botId,
        languages: ['base'],
        previewName: draft.name,
        previewDescription: draft.description,
      },
    ])
    setSelectedBot(botId)
    setPersonaList([draft])
    setPersonaForm(draft)
    setPersonaMeta('')
    setPersonaLang('base')
    setNewBotId('')
    setPersonaNotice({ tone: 'warn', message: `Создан бот ${botId}. Заполните и сохраните.` })
  }

  return (
    <div className="page">
      <header className="topbar">
        <div>
          <p className="eyebrow">Bot Control Surface</p>
          <h1>Настройки Telegram-чатов</h1>
          <p className="muted">
            Управление персоной, триггерами, лимитами и безопасностью бота для каждого чата.
          </p>
        </div>
        <div className="chips">
          <span className="chip chip--violet">React + Vite</span>
          <span className="chip chip--outline">Dev панель</span>
        </div>
      </header>

      <div className="nav-tabs">
        <button
          className={`tab ${activePage === 'config' ? 'tab--active' : ''}`}
          onClick={() => setActivePage('config')}
        >
          Настройки чатов
        </button>
        <button
          className={`tab ${activePage === 'db' ? 'tab--active' : ''}`}
          onClick={() => setActivePage('db')}
        >
          Инструменты БД
        </button>
        <button
          className={`tab ${activePage === 'explorer' ? 'tab--active' : ''}`}
          onClick={() => setActivePage('explorer')}
        >
          Конструктор БД
        </button>
        <button
          className={`tab ${activePage === 'dictionary' ? 'tab--active' : ''}`}
          onClick={() => setActivePage('dictionary')}
        >
          Справочник таблиц
        </button>
        <button
          className={`tab ${activePage === 'persona' ? 'tab--active' : ''}`}
          onClick={() => setActivePage('persona')}
        >
          Персона
        </button>
        <button
          className={`tab ${activePage === 'digest' ? 'tab--active' : ''}`}
          onClick={() => setActivePage('digest')}
        >
          Дайджесты
        </button>
        <button
          className={`tab ${activePage === 'reactions' ? 'tab--active' : ''}`}
          onClick={() => setActivePage('reactions')}
        >
          Реакции
        </button>
        <button
          className={`tab ${activePage === 'constructor' ? 'tab--active' : ''}`}
          onClick={() => setActivePage('constructor')}
        >
          Конструктор
        </button>
        <button
          className={`tab ${activePage === 'monitoring' ? 'tab--active' : ''}`}
          onClick={() => setActivePage('monitoring')}
        >
          Мониторинг
        </button>
        <button
          className={`tab ${activePage === 'scan' ? 'tab--active' : ''}`}
          onClick={() => setActivePage('scan')}
        >
          Сканирование
        </button>
      </div>

      {notice ? <div className={statusToneClass}>{notice.message}</div> : null}

      {activePage === 'config' ? (
        <div className="layout">
          <aside className="rail">
            <div className="rail__header">
              <div>
                <p className="eyebrow">Чаты</p>
                <h3>Список каналов</h3>
                <p className="muted">Берём каналы из стартап-синхронизации</p>
              </div>
              <button className="ghost" onClick={loadChannelList} disabled={loadingChannels}>
                {loadingChannels ? 'Обновляем...' : 'Обновить'}
              </button>
            </div>
            <div className="input-line">
              <select
                value={selectedChannel?.chatId ?? ''}
                onChange={(e) => {
                  const id = Number(e.target.value)
                  const meta = overviewMap.get(id) || null
                  const targetId = meta?.configChannelChatId ?? id
                  loadConfig(
                    targetId,
                    meta
                      ? {
                          chatId: meta.chatId,
                          title: meta.title,
                          joinStatus: meta.joinStatus,
                          muteStatus: meta.muteStatus,
                          lastSeen: meta.lastSeen,
                        }
                      : null,
                  )
                }}
              >
                <option value="" disabled>
                  ➕ Выберите канал
                </option>
                {(overview.length > 0 ? overview : channels).map((item) => (
                  <option key={item.chatId} value={item.chatId}>
                    {item.title ?? 'Без имени'} (ID {item.chatId})
                  </option>
                ))}
              </select>
            </div>
            <div className="input-line">
              <input
                placeholder="Поиск по названию или chatId"
                value={channelFilter}
                onChange={(e) => setChannelFilter(e.target.value)}
              />
            </div>
            <div className="channel-list">
              {filteredChannels.length === 0 ? (
                <p className="muted">Нет данных. Запустите ingest или попробуйте позже.</p>
              ) : (
                filteredChannels.map((channel) => {
                  const active = selectedChannel?.chatId === channel.chatId
                  const meta = overviewMap.get(channel.chatId)
                  return (
                    <button
                      key={channel.chatId}
                      className={`channel ${active ? 'channel--active' : ''}`}
                      onClick={() => loadConfig(channel.chatId, channel)}
                    >
                      <div className="channel__title">
                        <div>
                          <span className="channel__name">{channel.title ?? 'Без имени'}</span>
                          <p className="muted tiny channel__desc">
                            {(() => {
                              const fallback = meta?.title ?? channel.title ?? 'Без описания'
                              const text =
                                meta?.description && meta.description.trim().length > 0
                                  ? meta.description
                                  : fallback
                              return text.length > 110 ? `${text.slice(0, 110)}…` : text
                            })()}
                          </p>
                        </div>
                        <div className="chips column">
                          <span className="chip chip--outline">ID {channel.chatId}</span>
                          {meta?.subscribers ? (
                            <span className="chip chip--outline">
                              👥 {meta.subscribers.toLocaleString('ru-RU')}
                            </span>
                          ) : null}
                          {meta?.channelScore ? (
                            <span className="chip chip--violet">
                              score {meta.channelScore.toFixed(2)}
                            </span>
                          ) : null}
                        </div>
                      </div>
                      <p className="muted tiny">
                        {channel.joinStatus ?? '—'} · {channel.lastSeen ?? 'нет данных'}
                      </p>
                      {meta ? (
                        <p className="muted tiny">
                          {meta.processingPhase ?? 'phase?'} · триггеры {meta.triggerCount ?? 0} ·
                          ограничения {meta.restrictionCount ?? 0}
                        </p>
                      ) : null}
                    </button>
                  )
                })
              )}
            </div>
            <div className="manual">
              <p className="eyebrow">Подтянуть вручную</p>
              <div className="input-line">
                <input
                  type="number"
                  placeholder="chatId"
                  value={manualId}
                  onChange={(e) => setManualId(e.target.value)}
                />
                <button
                  onClick={() => {
                    const id = Number(manualId)
                    if (manualId.trim().length === 0 || Number.isNaN(id) || id === 0) {
                      return
                    }
                    if (!Number.isNaN(id)) {
                      const meta = overviewMap.get(id) || null
                      const targetId = meta?.configChannelChatId ?? id
                      loadConfig(
                        targetId,
                        meta
                          ? {
                              chatId: meta.chatId,
                              title: meta.title,
                              joinStatus: meta.joinStatus,
                              muteStatus: meta.muteStatus,
                              lastSeen: meta.lastSeen,
                            }
                          : null,
                      )
                    }
                  }}
                >
                  Открыть
                </button>
              </div>
              <p className="muted tiny">Подойдет, если чат есть в БД, но не в выдаче discover.</p>
            </div>
          </aside>

          <main className="content">
            {!config ? (
              <div className="placeholder">
                <h2>Выберите чат</h2>
                <p className="muted">
                  Слева появятся каналы, которые нужно настроить. Нажмите на чат или введите chatId.
                </p>
              </div>
            ) : (
              <>
                <div className="hero">
                  <div>
                    <p className="eyebrow">
                      Чат {config.channel_id} · ChatConfig ID {config.id}
                    </p>
                    <h2>{config.channel_title ?? 'Без названия'}</h2>
                    <p className="muted">
                      Авто-синхрон: {config.auto_sync_enabled ? 'включен' : 'выключен'} · Ответы:{' '}
                      {config.enabled ? 'вкл' : 'выкл'} · Язык:{' '}
                      {config.language ? config.language.toUpperCase() : 'auto'}
                    </p>
                  </div>
                  <div className="stats">
                    {stats.map((item) => (
                      <div className="stat" key={item.label}>
                        <p className="muted tiny">{item.label}</p>
                        <strong>{item.value}</strong>
                      </div>
                    ))}
                  </div>
                </div>

                <div className="grid">
                <Section
                  title="Базовые настройки"
                  accent="persona"
                  description="Ограничения по токенам, язык, промпт и включение бота."
                  actions={
                    <button onClick={saveBasic} disabled={saving === 'basic'}>
                      {saving === 'basic' ? 'Сохраняем...' : 'Сохранить'}
                    </button>
                  }
                >
                  <div className="form-grid">
                    <label>
                      <span>Промпт/темплейт</span>
                      <textarea
                        rows={4}
                        value={basicForm?.prompt_template ?? ''}
                        onChange={(e) =>
                          setBasicForm((prev) => ({ ...prev!, prompt_template: e.target.value }))
                        }
                        placeholder="Как бот отвечает, стиль, формат"
                      />
                    </label>
                    <label>
                      <span>Температура</span>
                      <input
                        type="number"
                        step="0.1"
                        value={basicForm?.temperature ?? ''}
                        onChange={(e) =>
                          setBasicForm((prev) => ({
                            ...prev!,
                            temperature: numberOrNull(e.target.value),
                          }))
                        }
                      />
                    </label>
                    <label>
                      <span>Max tokens</span>
                      <input
                        type="number"
                        value={basicForm?.max_tokens ?? ''}
                        onChange={(e) =>
                          setBasicForm((prev) => ({
                            ...prev!,
                            max_tokens: numberOrNull(e.target.value),
                          }))
                        }
                      />
                    </label>
                    <label>
                      <span>Контекстное окно</span>
                      <input
                        type="number"
                        value={basicForm?.context_window_size ?? ''}
                        onChange={(e) =>
                          setBasicForm((prev) => ({
                            ...prev!,
                            context_window_size: numberOrNull(e.target.value),
                          }))
                        }
                      />
                    </label>
                    <label>
                      <span>Основной канал (primary)</span>
                      <input
                        type="number"
                        value={basicForm?.primary_channel_id ?? ''}
                        onChange={(e) =>
                          setBasicForm((prev) => ({
                            ...prev!,
                            primary_channel_id: numberOrNull(e.target.value),
                          }))
                        }
                      />
                    </label>
                    <label>
                      <span>Язык</span>
                      <input
                        value={basicForm?.language ?? ''}
                        onChange={(e) =>
                          setBasicForm((prev) => ({ ...prev!, language: e.target.value }))
                        }
                        placeholder="ru, en, uk..."
                      />
                    </label>
                    <label className="checkbox">
                      <input
                        type="checkbox"
                        checked={basicForm?.enabled ?? false}
                        onChange={(e) =>
                          setBasicForm((prev) => ({ ...prev!, enabled: e.target.checked }))
                        }
                      />
                      <span>Бот отвечает в этом чате</span>
                    </label>
                    <label className="checkbox">
                      <input
                        type="checkbox"
                        checked={basicForm?.respond_to_forwarded_bot_messages ?? false}
                        onChange={(e) =>
                          setBasicForm((prev) => ({
                            ...prev!,
                            respond_to_forwarded_bot_messages: e.target.checked,
                          }))
                        }
                      />
                      <span>Отвечать на пересланные сообщения ботов</span>
                    </label>
                  </div>
                </Section>

                <Section
                  title="Pending Response"
                  accent="pending"
                  description="Отложенный ответ: сколько human сообщений ждать и/или минимальная задержка по времени."
                  actions={
                    <button onClick={savePendingResponse} disabled={saving === 'pending-response'}>
                      {saving === 'pending-response' ? 'Сохраняем...' : 'Сохранить'}
                    </button>
                  }
                >
                  <div className="form-grid">
                    <label>
                      <span>Ждать human сообщений (count)</span>
                      <input
                        type="number"
                        value={pendingResponseForm?.wait_for_human_replies_count ?? ''}
                        onChange={(e) =>
                          setPendingResponseForm((prev) => ({
                            ...prev!,
                            wait_for_human_replies_count: numberOrNull(e.target.value),
                          }))
                        }
                      />
                      <p className="muted tiny">
                        -1 = отключить (ответ сразу), 0 = только по времени, &gt;0 = ждать N сообщений.
                      </p>
                    </label>
                    <label>
                      <span>Задержка перед отправкой (сек)</span>
                      <input
                        type="number"
                        value={pendingResponseForm?.pending_response_delay_seconds ?? ''}
                        onChange={(e) =>
                          setPendingResponseForm((prev) => ({
                            ...prev!,
                            pending_response_delay_seconds: numberOrNull(e.target.value),
                          }))
                        }
                      />
                      <p className="muted tiny">0 = без задержки. Если count &gt; 0, применится оба условия.</p>
                    </label>
                  </div>
                </Section>

                <Section
                  title="LLM параметры"
                  accent="generation"
                  description="Модель, формат и штрафы. Сюда подтягиваем системный промпт."
                  actions={
                    <button onClick={saveLlm} disabled={saving === 'llm'}>
                      {saving === 'llm' ? 'Сохраняем...' : 'Сохранить'}
                    </button>
                  }
                >
                  <div className="form-grid">
                    <label>
                      <span>Модель</span>
                      <input
                        value={llmForm?.model_name ?? ''}
                        onChange={(e) =>
                          setLlmForm((prev) => ({ ...prev!, model_name: e.target.value }))
                        }
                      />
                    </label>
                    <label>
                      <span>Top P</span>
                      <input
                        type="number"
                        step="0.05"
                        value={llmForm?.top_p ?? ''}
                        onChange={(e) =>
                          setLlmForm((prev) => ({ ...prev!, top_p: numberOrNull(e.target.value) }))
                        }
                      />
                    </label>
                    <label>
                      <span>Frequency penalty</span>
                      <input
                        type="number"
                        step="0.1"
                        value={llmForm?.frequency_penalty ?? ''}
                        onChange={(e) =>
                          setLlmForm((prev) => ({
                            ...prev!,
                            frequency_penalty: numberOrNull(e.target.value),
                          }))
                        }
                      />
                    </label>
                    <label>
                      <span>Presence penalty</span>
                      <input
                        type="number"
                        step="0.1"
                        value={llmForm?.presence_penalty ?? ''}
                        onChange={(e) =>
                          setLlmForm((prev) => ({
                            ...prev!,
                            presence_penalty: numberOrNull(e.target.value),
                          }))
                        }
                      />
                    </label>
                    <label>
                      <span>Custom instructions</span>
                      <textarea
                        rows={3}
                        value={llmForm?.custom_instructions ?? ''}
                        onChange={(e) =>
                          setLlmForm((prev) => ({
                            ...prev!,
                            custom_instructions: e.target.value || null,
                          }))
                        }
                      />
                    </label>
                    <label>
                      <span>Response format</span>
                      <select
                        value={llmForm?.response_format ?? 'TEXT'}
                        onChange={(e) =>
                          setLlmForm((prev) => ({ ...prev!, response_format: e.target.value }))
                        }
                      >
                        {responseFormats.map((item) => (
                          <option key={item.value} value={item.value}>
                            {item.label}
                          </option>
                        ))}
                      </select>
                    </label>
                  </div>
                </Section>

                <Section
                  title="Контекст и безопасность"
                  accent="context"
                  description="Сколько истории подтягиваем и что скрываем/сжимаем."
                  actions={
                    <button onClick={saveContext} disabled={saving === 'context'}>
                      {saving === 'context' ? 'Сохраняем...' : 'Сохранить'}
                    </button>
                  }
                >
                  <div className="form-grid">
                    <label>
                      <span>История (кол-во сообщений)</span>
                      <input
                        type="number"
                        value={contextForm?.history_message_count ?? ''}
                        onChange={(e) =>
                          setContextForm((prev) => ({
                            ...prev!,
                            history_message_count: numberOrNull(e.target.value),
                          }))
                        }
                      />
                    </label>
                    <label>
                      <span>История (часов)</span>
                      <input
                        type="number"
                        value={contextForm?.history_time_window_hours ?? ''}
                        onChange={(e) =>
                          setContextForm((prev) => ({
                            ...prev!,
                            history_time_window_hours: numberOrNull(e.target.value),
                          }))
                        }
                      />
                    </label>
                    <label>
                      <span>Макс токенов в контексте</span>
                      <input
                        type="number"
                        value={contextForm?.max_context_tokens ?? ''}
                        onChange={(e) =>
                          setContextForm((prev) => ({
                            ...prev!,
                            max_context_tokens: numberOrNull(e.target.value),
                          }))
                        }
                      />
                    </label>
                    <label className="checkbox">
                      <input
                        type="checkbox"
                        checked={contextForm?.include_user_context ?? false}
                        onChange={(e) =>
                          setContextForm((prev) => ({
                            ...prev!,
                            include_user_context: e.target.checked,
                          }))
                        }
                      />
                      <span>Подмешивать контекст пользователя</span>
                    </label>
                    <label className="checkbox">
                      <input
                        type="checkbox"
                        checked={contextForm?.include_media_descriptions ?? false}
                        onChange={(e) =>
                          setContextForm((prev) => ({
                            ...prev!,
                            include_media_descriptions: e.target.checked,
                          }))
                        }
                      />
                      <span>Учитывать медиа</span>
                    </label>
                    <label className="checkbox">
                      <input
                        type="checkbox"
                        checked={contextForm?.context_compression_enabled ?? false}
                        onChange={(e) =>
                          setContextForm((prev) => ({
                            ...prev!,
                            context_compression_enabled: e.target.checked,
                          }))
                        }
                      />
                      <span>Включить компрессию контекста</span>
                    </label>
                    <label className="checkbox">
                      <input
                        type="checkbox"
                        checked={contextForm?.preserve_important_messages ?? false}
                        onChange={(e) =>
                          setContextForm((prev) => ({
                            ...prev!,
                            preserve_important_messages: e.target.checked,
                          }))
                        }
                      />
                      <span>Сохранять важные сообщения</span>
                    </label>
                  </div>
                </Section>

                <Section
                  title="Rate limits"
                  accent="limits"
                  description="Жесткие ограничения по сообщениям/токенам."
                  actions={
                    <div className="actions">
                      <button className="ghost" onClick={handleResetRate} disabled={saving === 'rate'}>
                        Сбросить счетчики
                      </button>
                      <button onClick={saveRateLimits} disabled={saving === 'rate'}>
                        {saving === 'rate' ? 'Сохраняем...' : 'Сохранить'}
                      </button>
                    </div>
                  }
                >
                  <div className="form-grid">
                    <label>
                      <span>Сообщений в минуту</span>
                      <input
                        type="number"
                        value={rateForm?.max_messages_per_minute ?? ''}
                        onChange={(e) =>
                          setRateForm((prev) => ({
                            ...prev!,
                            max_messages_per_minute: numberOrNull(e.target.value),
                          }))
                        }
                      />
                    </label>
                    <label>
                      <span>Сообщений в час</span>
                      <input
                        type="number"
                        value={rateForm?.max_messages_per_hour ?? ''}
                        onChange={(e) =>
                          setRateForm((prev) => ({
                            ...prev!,
                            max_messages_per_hour: numberOrNull(e.target.value),
                          }))
                        }
                      />
                    </label>
                    <label>
                      <span>Сообщений в день</span>
                      <input
                        type="number"
                        value={rateForm?.max_messages_per_day ?? ''}
                        onChange={(e) =>
                          setRateForm((prev) => ({
                            ...prev!,
                            max_messages_per_day: numberOrNull(e.target.value),
                          }))
                        }
                      />
                    </label>
                    <label>
                      <span>Использовано сегодня</span>
                      <input type="number" value={rateForm?.current_daily_messages ?? 0} disabled />
                      <p className="muted tiny">Счетчик в базе: <code>bot.rate_limits.current_daily_messages</code></p>
                    </label>
                    <label>
                      <span>Токенов в день</span>
                      <input
                        type="number"
                        value={rateForm?.max_tokens_per_day ?? ''}
                        onChange={(e) =>
                          setRateForm((prev) => ({
                            ...prev!,
                            max_tokens_per_day: numberOrNull(e.target.value),
                          }))
                        }
                      />
                    </label>
                    <label>
                      <span>Кулдаун (мин)</span>
                      <input
                        type="number"
                        value={rateForm?.cooldown_after_limit_minutes ?? ''}
                        onChange={(e) =>
                          setRateForm((prev) => ({
                            ...prev!,
                            cooldown_after_limit_minutes: numberOrNull(e.target.value),
                          }))
                        }
                      />
                    </label>
                    <label>
                      <span>Burst limit</span>
                      <input
                        type="number"
                        value={rateForm?.burst_limit ?? ''}
                        onChange={(e) =>
                          setRateForm((prev) => ({
                            ...prev!,
                            burst_limit: numberOrNull(e.target.value),
                          }))
                        }
                      />
                    </label>
                    <label>
                      <span>Burst окно (сек)</span>
                      <input
                        type="number"
                        value={rateForm?.burst_window_seconds ?? ''}
                        onChange={(e) =>
                          setRateForm((prev) => ({
                            ...prev!,
                            burst_window_seconds: numberOrNull(e.target.value),
                          }))
                        }
                      />
                    </label>
                    <label className="checkbox">
                      <input
                        type="checkbox"
                        checked={rateForm?.user_specific_limits ?? false}
                        onChange={(e) =>
                          setRateForm((prev) => ({
                            ...prev!,
                            user_specific_limits: e.target.checked,
                          }))
                        }
                      />
                      <span>Персональные лимиты для пользователей</span>
                    </label>
                  </div>
                </Section>

                <Section
                  title="Поиск"
                  accent="search"
                  description="Когда подключать веб-поиск и сколько результатов сохранять."
                  actions={
                    <button onClick={saveSearch} disabled={saving === 'search'}>
                      {saving === 'search' ? 'Сохраняем...' : 'Сохранить'}
                    </button>
                  }
                >
                  <div className="form-grid">
                    <label className="checkbox">
                      <input
                        type="checkbox"
                        checked={searchConfig?.search_enabled ?? false}
                        onChange={(e) =>
                          setSearchConfig((prev) =>
                            prev ? { ...prev, search_enabled: e.target.checked } : prev,
                          )
                        }
                      />
                      <span>Включить поиск</span>
                    </label>
                    <label className="checkbox">
                      <input
                        type="checkbox"
                        checked={searchConfig?.auto_search_enabled ?? false}
                        onChange={(e) =>
                          setSearchConfig((prev) =>
                            prev ? { ...prev, auto_search_enabled: e.target.checked } : prev,
                          )
                        }
                      />
                      <span>Автоматически определять, когда искать</span>
                    </label>
                    <label>
                      <span>Провайдер</span>
                      <select
                        value={searchConfig?.search_provider ?? 'GOOGLE'}
                        onChange={(e) =>
                          setSearchConfig((prev) =>
                            prev ? { ...prev, search_provider: e.target.value } : prev,
                          )
                        }
                      >
                        {searchProviders.map((item) => (
                          <option key={item.value} value={item.value}>
                            {item.label}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label>
                      <span>Кол-во результатов</span>
                      <input
                        type="number"
                        value={searchConfig?.max_results ?? ''}
                        onChange={(e) =>
                          setSearchConfig((prev) =>
                            prev ? { ...prev, max_results: Number(e.target.value) } : prev,
                          )
                        }
                      />
                    </label>
                    <label>
                      <span>Кэш (минут)</span>
                      <input
                        type="number"
                        value={searchConfig?.cache_duration_minutes ?? ''}
                        onChange={(e) =>
                          setSearchConfig((prev) =>
                            prev
                              ? { ...prev, cache_duration_minutes: Number(e.target.value) }
                              : prev,
                          )
                        }
                      />
                    </label>
                    <label>
                      <span>Рейт лимит / час</span>
                      <input
                        type="number"
                        value={searchConfig?.rate_limit_per_hour ?? ''}
                        onChange={(e) =>
                          setSearchConfig((prev) =>
                            prev ? { ...prev, rate_limit_per_hour: Number(e.target.value) } : prev,
                          )
                        }
                      />
                    </label>
                    <label>
                      <span>Порог релевантности (0-1)</span>
                      <input
                        type="number"
                        step="0.05"
                        value={searchConfig?.relevance_threshold ?? ''}
                        onChange={(e) =>
                          setSearchConfig((prev) =>
                            prev
                              ? { ...prev, relevance_threshold: Number(e.target.value) }
                              : prev,
                          )
                        }
                      />
                    </label>
                    <label>
                      <span>Триггеры (regex/ключи)</span>
                      <textarea
                        rows={2}
                        value={(searchConfig?.search_triggers ?? []).join('\n')}
                        onChange={(e) =>
                          setSearchConfig((prev) =>
                            prev
                              ? {
                                  ...prev,
                                  search_triggers: e.target.value
                                    .split('\n')
                                    .map((line) => line.trim())
                                    .filter(Boolean),
                                }
                              : prev,
                          )
                        }
                      />
                    </label>
                    <label className="checkbox">
                      <input
                        type="checkbox"
                        checked={searchConfig?.include_attribution ?? false}
                        onChange={(e) =>
                          setSearchConfig((prev) =>
                            prev ? { ...prev, include_attribution: e.target.checked } : prev,
                          )
                        }
                      />
                      <span>Показывать источник</span>
                    </label>
                  </div>
                </Section>

                <Section
                  title="Response templates"
                  accent="templates"
                  description="Быстрые пресеты ответов с приоритетами."
                  actions={
                    <button onClick={addTemplate} disabled={saving === 'template'}>
                      {saving === 'template' ? 'Сохраняем...' : 'Сохранить шаблон'}
                    </button>
                  }
                >
                  {templateNotice ? (
                    <div className={`inline-notice inline-notice--${templateNotice.tone}`}>
                      {templateNotice.message}
                    </div>
                  ) : null}
                  <div className="list">
                    {config.response_templates.length === 0 ? (
                      <p className="muted tiny">Шаблонов нет</p>
                    ) : (
                      config.response_templates.map((template) => {
                        const isEditing = editingTemplateId === template.id
                        return (
                        <div className="item" key={template.id}>
                          <div>
                            <p className="tiny muted">#{template.priority ?? '—'}</p>
                            {isEditing && templateEditForm ? (
                              <div className="form-grid">
                                <label>
                                  <span>Название</span>
                                  <input
                                    value={templateEditForm.template_name ?? ''}
                                    onChange={(e) =>
                                      setTemplateEditForm((prev) => ({
                                        ...prev!,
                                        template_name: e.target.value,
                                      }))
                                    }
                                  />
                                </label>
                                <label>
                                  <span>Содержимое</span>
                                  <textarea
                                    rows={4}
                                    value={templateEditForm.template_content ?? ''}
                                    onChange={(e) =>
                                      setTemplateEditForm((prev) => ({
                                        ...prev!,
                                        template_content: e.target.value,
                                      }))
                                    }
                                  />
                                </label>
                                <label>
                                  <span>Стиль</span>
                                  <select
                                    value={templateEditForm.response_style ?? 'ADAPTIVE'}
                                    onChange={(e) =>
                                      setTemplateEditForm((prev) => ({
                                        ...prev!,
                                        response_style: e.target.value,
                                      }))
                                    }
                                  >
                                    {responseStyles.map((item) => (
                                      <option key={item.value} value={item.value}>
                                        {item.label}
                                      </option>
                                    ))}
                                  </select>
                                </label>
                                <label>
                                  <span>Тон</span>
                                  <select
                                    value={templateEditForm.response_tone ?? 'NEUTRAL'}
                                    onChange={(e) =>
                                      setTemplateEditForm((prev) => ({
                                        ...prev!,
                                        response_tone: e.target.value,
                                      }))
                                    }
                                  >
                                    {responseTones.map((item) => (
                                      <option key={item.value} value={item.value}>
                                        {item.label}
                                      </option>
                                    ))}
                                  </select>
                                </label>
                                <label>
                                  <span>Max длина</span>
                                  <input
                                    type="number"
                                    value={templateEditForm.max_response_length ?? ''}
                                    onChange={(e) =>
                                      setTemplateEditForm((prev) => ({
                                        ...prev!,
                                        max_response_length: numberOrNull(e.target.value),
                                      }))
                                    }
                                  />
                                </label>
                                <label>
                                  <span>Приоритет</span>
                                  <input
                                    type="number"
                                    value={templateEditForm.priority ?? ''}
                                    onChange={(e) =>
                                      setTemplateEditForm((prev) => ({
                                        ...prev!,
                                        priority: numberOrNull(e.target.value),
                                      }))
                                    }
                                  />
                                </label>
                                <label className="checkbox">
                                  <input
                                    type="checkbox"
                                    checked={templateEditForm.active ?? true}
                                    onChange={(e) =>
                                      setTemplateEditForm((prev) => ({
                                        ...prev!,
                                        active: e.target.checked,
                                      }))
                                    }
                                  />
                                  <span>Активен</span>
                                </label>
                              </div>
                            ) : (
                              <>
                                <strong>{template.template_name}</strong>
                                <p className="muted tiny">{template.template_content}</p>
                                <div className="chips">
                                  <span className="chip chip--outline">
                                    {template.response_style ?? 'style?'}
                                  </span>
                                  <span className="chip chip--outline">
                                    {template.response_tone ?? 'tone?'}
                                  </span>
                                  {template.is_default ? (
                                    <span className="chip chip--green">default</span>
                                  ) : null}
                                  {!template.active ? (
                                    <span className="chip chip--warn">off</span>
                                  ) : null}
                                </div>
                              </>
                            )}
                          </div>
                          <div className="actions">
                            <button
                              className="ghost"
                              onClick={() =>
                                isEditing ? cancelEditingTemplate() : startEditingTemplate(template)
                              }
                              disabled={saving === 'template'}
                            >
                              {isEditing ? 'Отмена' : 'Редактировать'}
                            </button>
                            {isEditing ? (
                              <button onClick={saveEditingTemplate} disabled={saving === 'template'}>
                                {saving === 'template' ? 'Сохраняем...' : 'Сохранить'}
                              </button>
                            ) : null}
                            <button
                              className="ghost"
                              onClick={() => toggleTemplateActive(template)}
                              disabled={saving === 'template' || isEditing}
                            >
                              {template.active ? 'Выключить' : 'Включить'}
                            </button>
                            {!template.is_default ? (
                            <button
                              className="ghost"
                              onClick={() => makeDefaultTemplate(template.id)}
                              disabled={saving === 'template' || isEditing}
                            >
                              Сделать дефолтным
                            </button>
                            ) : null}
                            <button
                              className="ghost danger"
                              onClick={() => removeTemplate(template.id)}
                              disabled={saving === 'template' || isEditing}
                            >
                              Удалить
                            </button>
                          </div>
                        </div>
                        )
                      })
                    )}
                  </div>
                  <div className="form-grid">
                    <label>
                      <span>Название</span>
                      <input
                        value={newTemplate.template_name ?? ''}
                        onChange={(e) =>
                          setNewTemplate((prev) => ({ ...prev, template_name: e.target.value }))
                        }
                      />
                    </label>
                    <label>
                      <span>Содержимое</span>
                      <textarea
                        rows={3}
                        value={newTemplate.template_content ?? ''}
                        onChange={(e) =>
                          setNewTemplate((prev) => ({
                            ...prev,
                            template_content: e.target.value,
                          }))
                        }
                      />
                    </label>
                    <label>
                      <span>Стиль</span>
                      <select
                        value={newTemplate.response_style ?? 'ADAPTIVE'}
                        onChange={(e) =>
                          setNewTemplate((prev) => ({ ...prev, response_style: e.target.value }))
                        }
                      >
                        {responseStyles.map((item) => (
                          <option key={item.value} value={item.value}>
                            {item.label}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label>
                      <span>Тон</span>
                      <select
                        value={newTemplate.response_tone ?? 'NEUTRAL'}
                        onChange={(e) =>
                          setNewTemplate((prev) => ({ ...prev, response_tone: e.target.value }))
                        }
                      >
                        {responseTones.map((item) => (
                          <option key={item.value} value={item.value}>
                            {item.label}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label>
                      <span>Max длина</span>
                      <input
                        type="number"
                        value={newTemplate.max_response_length ?? ''}
                        onChange={(e) =>
                          setNewTemplate((prev) => ({
                            ...prev,
                            max_response_length: numberOrNull(e.target.value),
                          }))
                        }
                      />
                    </label>
                    <label>
                      <span>Приоритет</span>
                      <input
                        type="number"
                        value={newTemplate.priority ?? ''}
                        onChange={(e) =>
                          setNewTemplate((prev) => ({
                            ...prev,
                            priority: numberOrNull(e.target.value),
                          }))
                        }
                      />
                    </label>
                    <label className="checkbox">
                      <input
                        type="checkbox"
                        checked={newTemplate.is_default ?? false}
                        onChange={(e) =>
                          setNewTemplate((prev) => ({ ...prev, is_default: e.target.checked }))
                        }
                      />
                      <span>Сделать дефолтным</span>
                    </label>
                    <label className="checkbox">
                      <input
                        type="checkbox"
                        checked={newTemplate.active ?? true}
                        onChange={(e) =>
                          setNewTemplate((prev) => ({ ...prev, active: e.target.checked }))
                        }
                      />
                      <span>Активен</span>
                    </label>
                  </div>
                </Section>

                <Section
                  title="Триггеры"
                  accent="triggers"
                  description="Когда бот срабатывает. Используйте приоритеты и проценты."
                  actions={
                    <button onClick={addTrigger} disabled={saving === 'trigger'}>
                      {saving === 'trigger' ? 'Добавляем...' : 'Добавить'}
                    </button>
                  }
                >
                  <div className="list">
                    {config.trigger_conditions.length === 0 ? (
                      <p className="muted tiny">Триггеров пока нет</p>
                    ) : (
                      config.trigger_conditions.map((trigger) => (
                        <div className="item" key={trigger.id}>
                          <div>
                            <p className="tiny muted">
                              {trigger.trigger_type} · {trigger.priority ?? '—'}prio
                            </p>
                            <strong>{trigger.condition_name}</strong>
                            <p className="muted tiny">{trigger.keywords ?? '—'}</p>
                            <div className="chips">
                              <span className="chip chip--outline">
                                P={trigger.probability_percent ?? '—'}%
                              </span>
                              {trigger.mention_required ? (
                                <span className="chip chip--outline">нужно упоминание</span>
                              ) : null}
                              {!trigger.active ? <span className="chip chip--warn">off</span> : null}
                            </div>
                          </div>
                          <div className="actions">
                            <button
                              className="ghost"
                              onClick={() => toggleExistingTrigger(trigger)}
                              disabled={saving === 'trigger'}
                            >
                              {trigger.active ? 'Выключить' : 'Включить'}
                            </button>
                            <button
                              className="ghost danger"
                              onClick={() => removeTrigger(trigger.id)}
                              disabled={saving === 'trigger'}
                            >
                              Удалить
                            </button>
                          </div>
                        </div>
                      ))
                    )}
                  </div>
                  <div className="form-grid">
                    <label>
                      <span>Название</span>
                      <input
                        value={newTrigger.condition_name ?? ''}
                        onChange={(e) =>
                          setNewTrigger((prev) => ({ ...prev, condition_name: e.target.value }))
                        }
                      />
                    </label>
                    <label>
                      <span>Тип</span>
                      <select
                        value={newTrigger.trigger_type ?? 'KEYWORD_MATCH'}
                        onChange={(e) =>
                          setNewTrigger((prev) => ({ ...prev, trigger_type: e.target.value }))
                        }
                      >
                        {triggerTypes.map((item) => (
                          <option key={item.value} value={item.value}>
                            {item.label}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label>
                      <span>Ключевые слова</span>
                      <input
                        value={newTrigger.keywords ?? ''}
                        onChange={(e) =>
                          setNewTrigger((prev) => ({ ...prev, keywords: e.target.value }))
                        }
                        placeholder="через запятую"
                      />
                    </label>
                    <label>
                      <span>Вероятность %</span>
                      <input
                        type="number"
                        value={newTrigger.probability_percent ?? ''}
                        onChange={(e) =>
                          setNewTrigger((prev) => ({
                            ...prev,
                            probability_percent: Number(e.target.value),
                          }))
                        }
                      />
                    </label>
                    <label>
                      <span>Минимальный геп (мин)</span>
                      <input
                        type="number"
                        value={newTrigger.minimum_gap_minutes ?? ''}
                        onChange={(e) =>
                          setNewTrigger((prev) => ({
                            ...prev,
                            minimum_gap_minutes: numberOrNull(e.target.value),
                          }))
                        }
                      />
                    </label>
                    <label className="checkbox">
                      <input
                        type="checkbox"
                        checked={newTrigger.mention_required ?? false}
                        onChange={(e) =>
                          setNewTrigger((prev) => ({
                            ...prev,
                            mention_required: e.target.checked,
                          }))
                        }
                      />
                      <span>Нужно упоминание</span>
                    </label>
                    <label className="checkbox">
                      <input
                        type="checkbox"
                        checked={newTrigger.active ?? true}
                        onChange={(e) =>
                          setNewTrigger((prev) => ({
                            ...prev,
                            active: e.target.checked,
                          }))
                        }
                      />
                      <span>Активен</span>
                    </label>
                  </div>
                </Section>

                <Section
                  title="Тематические ограничения"
                  accent="safety"
                  description="Что блокируем, редактируем или логируем."
                  actions={
                    <button onClick={addRestriction} disabled={saving === 'restriction'}>
                      {saving === 'restriction' ? 'Добавляем...' : 'Добавить'}
                    </button>
                  }
                >
                  <div className="list">
                    {config.topic_restrictions.length === 0 ? (
                      <p className="muted tiny">Ограничений нет</p>
                    ) : (
                      config.topic_restrictions.map((restriction) => (
                        <div className="item" key={restriction.id}>
                          <div>
                            <p className="tiny muted">
                              {restriction.restriction_type} · {restriction.action_type}
                            </p>
                            <strong>{restriction.restriction_name}</strong>
                            <p className="muted tiny">{restriction.keywords ?? '—'}</p>
                            <div className="chips">
                              {restriction.categories ? (
                                <span className="chip chip--outline">{restriction.categories}</span>
                              ) : null}
                              {!restriction.active ? (
                                <span className="chip chip--warn">off</span>
                              ) : null}
                            </div>
                          </div>
                          <div className="actions">
                            <button
                              className="ghost"
                              onClick={() => toggleExistingRestriction(restriction)}
                              disabled={saving === 'restriction'}
                            >
                              {restriction.active ? 'Выключить' : 'Включить'}
                            </button>
                            <button
                              className="ghost danger"
                              onClick={() => removeRestriction(restriction.id)}
                              disabled={saving === 'restriction'}
                            >
                              Удалить
                            </button>
                          </div>
                        </div>
                      ))
                    )}
                  </div>
                  <div className="form-grid">
                    <label>
                      <span>Название</span>
                      <input
                        value={newRestriction.restriction_name ?? ''}
                        onChange={(e) =>
                          setNewRestriction((prev) => ({
                            ...prev,
                            restriction_name: e.target.value,
                          }))
                        }
                      />
                    </label>
                    <label>
                      <span>Тип</span>
                      <select
                        value={newRestriction.restriction_type ?? 'FORBIDDEN'}
                        onChange={(e) =>
                          setNewRestriction((prev) => ({
                            ...prev,
                            restriction_type: e.target.value,
                          }))
                        }
                      >
                        {restrictionTypes.map((item) => (
                          <option key={item.value} value={item.value}>
                            {item.label}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label>
                      <span>Действие</span>
                      <select
                        value={newRestriction.action_type ?? 'LOG_ONLY'}
                        onChange={(e) =>
                          setNewRestriction((prev) => ({ ...prev, action_type: e.target.value }))
                        }
                      >
                        {actionTypes.map((item) => (
                          <option key={item.value} value={item.value}>
                            {item.label}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label>
                      <span>Ключевые слова</span>
                      <textarea
                        rows={2}
                        value={newRestriction.keywords ?? ''}
                        onChange={(e) =>
                          setNewRestriction((prev) => ({ ...prev, keywords: e.target.value }))
                        }
                        placeholder="через запятую"
                      />
                    </label>
                    <label>
                      <span>Категории</span>
                      <input
                        value={newRestriction.categories ?? ''}
                        onChange={(e) =>
                          setNewRestriction((prev) => ({ ...prev, categories: e.target.value }))
                        }
                      />
                    </label>
                    <label>
                      <span>Своя реплика</span>
                      <textarea
                        rows={2}
                        value={newRestriction.custom_response ?? ''}
                        onChange={(e) =>
                          setNewRestriction((prev) => ({
                            ...prev,
                            custom_response: e.target.value,
                          }))
                        }
                      />
                    </label>
                    <label className="checkbox">
                      <input
                        type="checkbox"
                        checked={newRestriction.active ?? true}
                        onChange={(e) =>
                          setNewRestriction((prev) => ({ ...prev, active: e.target.checked }))
                        }
                      />
                      <span>Активно</span>
                    </label>
                  </div>
                </Section>
              </div>
            </>
          )}
        </main>

          <aside className="summary">
            <div className="summary__card">
              <p className="eyebrow">Взаимодействие</p>
              <h3>Как поведет себя бот</h3>
              <ul>
                <li>
                  Отвечает: <strong>{basicForm?.enabled ? 'да' : 'нет'}</strong>{' '}
                  {contextForm?.history_message_count
                    ? `· контекст ${contextForm?.history_message_count} сообщений`
                    : null}
                </li>
                <li>
                  LLM:{' '}
                  <strong>
                    {llmForm?.model_name ?? '—'} / temp {llmForm?.temperature ?? basicForm?.temperature ?? '—'}
                  </strong>
                </li>
                <li>
                  Формат: <strong>{llmForm?.response_format ?? 'TEXT'}</strong> · Тон:{' '}
                  <strong>
                    {defaultTemplate
                      ? responseTones.find((t) => t.value === defaultTemplate.response_tone)?.label ??
                        defaultTemplate.response_tone
                      : 'разный'}
                  </strong>
                </li>
                <li>
                  Rate limit:{' '}
                  <strong>
                    {rateForm?.max_messages_per_hour ?? '—'}/ч · {rateForm?.burst_limit ?? '—'} burst
                  </strong>
                </li>
                <li>
                  Триггеры: {config?.trigger_conditions.length ?? 0} · Ограничения:{' '}
                  {config?.topic_restrictions.length ?? 0}
                </li>
              </ul>
            </div>
            <div className="summary__card">
              <p className="eyebrow">Проверка</p>
              <h3>Чек-лист перед выкладкой</h3>
              <ul>
                <li>Температура ≤ 1.0 и стоит limit по токенам</li>
                <li>Есть дефолтный response template</li>
                <li>Триггеры не перекрывают друг друга</li>
                <li>Поиск включен только там, где нужен</li>
              </ul>
            </div>
          </aside>
        </div>
      ) : activePage === 'db' ? (
        <div className="dbtools-page">
          <div className="dbtools-hero">
            <div>
              <p className="eyebrow">Database Tools</p>
              <h2>Операции с данными</h2>
              <p className="muted">
                Быстрые проверки и безопасные действия над таблицами. Здесь можно посмотреть, что
                лежит в БД по chatId, и выполнить очистку.
              </p>
            </div>
            <div className="chips">
              <span className="chip chip--outline">Write access</span>
              <span className="chip chip--violet">Safe confirm</span>
            </div>
          </div>

          {dbNotice ? <div className={`notice notice--${dbNotice.tone}`}>{dbNotice.message}</div> : null}

          <div className="grid">
            <Section
              title="Сообщения (bot.messages)"
              accent="data"
              description="Посчитать сообщения по chatId и очистить все записи в messages для этого чата."
              actions={
                <button
                  className="ghost"
                  onClick={checkDbMessageCount}
                  disabled={dbLoadingCount || dbResolvedChatId == null}
                >
                  {dbLoadingCount ? 'Считаем...' : 'Посчитать'}
                </button>
              }
            >
              <div className="form-grid">
                <label>
                  <span>Chat ID</span>
                  <input
                    type="number"
                    placeholder="-1001234567890"
                    value={dbChatId}
                    onChange={(e) => setDbChatId(e.target.value)}
                  />
                </label>
                <div className="summary__card">
                  <p className="eyebrow">Статистика</p>
                  <h3>{dbMessageCount ? dbMessageCount.message_count.toLocaleString('ru-RU') : '—'}</h3>
                  <p className="muted tiny">Всего сообщений в bot.messages для указанного chatId.</p>
                </div>
              </div>

              <div className="danger-zone">
                <div>
                  <p className="eyebrow">Опасная зона</p>
                  <h3>Очистить все сообщения</h3>
                  <p className="muted">
                    Удалит <strong>все</strong> сообщения из bot.messages для chatId. Действие
                    необратимо.
                  </p>
                </div>
                <div className="form-grid">
                  <label>
                    <span>Подтвердите chatId</span>
                    <input
                      type="number"
                      value={dbConfirmChatId}
                      onChange={(e) => setDbConfirmChatId(e.target.value)}
                      placeholder="повторите chatId"
                    />
                  </label>
                  <label>
                    <span>Подтверждение</span>
                    <input
                      value={dbConfirmWord}
                      onChange={(e) => setDbConfirmWord(e.target.value)}
                      placeholder="DELETE"
                    />
                    <p className="muted tiny">Введите слово DELETE, чтобы разблокировать кнопку.</p>
                  </label>
                </div>

                <div className="danger-actions">
                  <button
                    className="ghost danger"
                    onClick={purgeDbMessages}
                    disabled={dbPurging || !dbPurgeReady}
                  >
                    {dbPurging ? 'Удаляем...' : 'Очистить сообщения'}
                  </button>
                  {dbPurgeResult ? (
                    <p className="muted tiny">
                      Было: {dbPurgeResult.message_count_before.toLocaleString('ru-RU')} · Удалено:{' '}
                      {dbPurgeResult.deleted_messages.toLocaleString('ru-RU')}
                    </p>
                  ) : null}
                </div>
              </div>
            </Section>
          </div>
        </div>
      ) : activePage === 'explorer' ? (
        <div className="explorer-page">
          <div className="explorer-hero">
            <div>
              <p className="eyebrow">Schema-driven Explorer</p>
              <h2>Конструктор БД</h2>
              <p className="muted">
                Визуальный конструктор: schema → table → columns → filters → preview. Без raw SQL.
              </p>
            </div>
            <div className="chips">
              <span className="chip chip--outline">Read only</span>
              <span className="chip chip--violet">Safe DSL</span>
            </div>
          </div>
          <DbExplorer />
        </div>
      ) : activePage === 'dictionary' ? (
        <div className="dictionary-page">
          <div className="dictionary-hero">
            <div>
              <p className="eyebrow">Data Playbook</p>
              <h2>Справочник таблиц и полей</h2>
              <p className="muted">
                Описание структур БД, статусы готовности и быстрый поиск по колонкам. Основано на
                docs/db-table-descriptions.md.
              </p>
            </div>
            <div className="chips">
              <span className="chip chip--outline">Схемы bot / tgscan</span>
              <span className="chip chip--violet">Read only</span>
            </div>
          </div>
          <DatabaseDictionary />
        </div>
      ) : activePage === 'digest' ? (
        <DigestPanel />
      ) : activePage === 'reactions' ? (
        <ReactionPanel />
      ) : activePage === 'constructor' ? (
        <ConstructorPage />
      ) : activePage === 'monitoring' ? (
        <MonitoringPanel />
      ) : activePage === 'scan' ? (
        <ScanPanel />
      ) : (
        <div className="persona-page">
          <div className="persona-hero">
            <div>
              <p className="eyebrow">Bot Persona</p>
              <h2>Кто говорит от лица бота {selectedBot ? `· ${selectedBot}` : ''}</h2>
              <p className="muted">
                Настройте имя, поведение, черты и ограничения. Эти тексты попадут в системный промпт и
                ответы на вопросы про личность.
              </p>
            </div>
            <div className="chips">
              <span className="chip chip--violet">Live</span>
            </div>
          </div>

          {personaNotice ? (
            <div className={`notice notice--${personaNotice.tone}`}>{personaNotice.message}</div>
          ) : null}

          <div className="persona-layout">
            <aside className="persona-rail">
              <div className="rail__header">
                <div>
                  <p className="eyebrow">Локали</p>
                  <h3>Все персоны</h3>
                  <p className="muted tiny">Выберите язык, чтобы отредактировать легенду.</p>
                </div>
                <button
                  className="ghost"
                  onClick={() => selectedBot && loadPersonaList(selectedBot)}
                  disabled={personaLoading}
                >
                  {personaLoading ? 'Обновляем...' : 'Обновить'}
                </button>
              </div>
              <div className="persona-list">
                {personaBundles.length === 0 ? (
                  <p className="muted tiny">Пока нет сохранённых персон.</p>
                ) : (
                  personaBundles.map((bundle) => (
                    <div
                      key={bundle.botId}
                      className={`persona-card ${bundle.botId === selectedBot ? 'active' : ''}`}
                    >
                      <div
                        className="persona-card__header persona-card__click"
                        onClick={() => {
                          setSelectedBot(bundle.botId)
                          loadPersonaList(bundle.botId)
                        }}
                      >
                        <span className="chip chip--outline">{bundle.botId}</span>
                        {bundle.updatedAt ? (
                          <span className="tiny muted">
                            {new Date(bundle.updatedAt).toLocaleDateString()}
                          </span>
                        ) : null}
                      </div>
                      <strong>{bundle.previewName ?? 'Без имени'}</strong>
                      <p className="muted tiny">
                        {bundle.previewDescription
                          ? bundle.previewDescription.slice(0, 90) +
                            (bundle.previewDescription.length > 90 ? '…' : '')
                          : 'Описание не задано'}
                      </p>
                      <div className="persona-langs">
                        {(bundle.languages ?? []).map((lang) => (
                          <button
                            key={`${bundle.botId}-${lang}`}
                            className={`chip ${
                              bundle.botId === selectedBot && lang === personaLang
                                ? 'chip--violet'
                                : 'chip--outline'
                            }`}
                            onClick={() => {
                              setSelectedBot(bundle.botId)
                              setPersonaLang(lang)
                              loadPersona(bundle.botId, lang)
                            }}
                          >
                            {lang.toUpperCase()}
                          </button>
                        ))}
                      </div>
                    </div>
                  ))
                )}
              </div>
              <div className="persona-new">
                <input
                  placeholder="botId (например 2000000001)"
                  value={newBotId}
                  onChange={(e) => setNewBotId(e.target.value)}
                />
                <button className="ghost" onClick={handleCreateBot}>
                  + Новый botId
                </button>
              </div>
              <div className="persona-new">
                <input
                  placeholder="код языка (например en)"
                  value={newPersonaLang}
                  onChange={(e) => setNewPersonaLang(e.target.value)}
                />
                <button className="ghost" onClick={handleCreatePersona} disabled={!selectedBot}>
                  + Новая локаль
                </button>
              </div>
            </aside>

            <div className="persona-content">
              {!personaForm ? (
                <div className="placeholder">
                  <h3>Выберите или создайте персону</h3>
                </div>
              ) : (
                <>
                  <div className="persona-grid">
                    <div className="card">
                      <div className="card__header">
                        <div>
                          <p className="eyebrow">Общее</p>
                          <h3>Имя и описание</h3>
                          <p className="muted tiny">
                            Как бот представляется и какую роль играет. Это ядро системного промпта.
                          </p>
                        </div>
                        <button
                          className="ghost"
                          onClick={() => selectedBot && loadPersona(selectedBot, personaLang)}
                          disabled={personaLoading}
                        >
                          {personaLoading ? 'Обновляем...' : 'Обновить'}
                        </button>
                      </div>
                      <div className="form-grid">
                        <label>
                          <span>Имя</span>
                          <input
                            value={personaForm.name}
                            onChange={(e) =>
                              setPersonaForm((prev) => (prev ? { ...prev, name: e.target.value } : prev))
                            }
                          />
                        </label>
                        <label>
                          <span>Язык</span>
                      <select
                        value={personaLang}
                        onChange={(e) => {
                          const lang = e.target.value
                          setPersonaLang(lang)
                          if (selectedBot) {
                            loadPersona(selectedBot, lang)
                          }
                        }}
                      >
                        {personaLanguages.map((lang) => (
                          <option key={lang} value={lang}>
                            {lang.toUpperCase()}
                          </option>
                        ))}
                      </select>
                        </label>
                        <label className="full">
                          <span>Описание</span>
                          <textarea
                            rows={3}
                            value={personaForm.description}
                            onChange={(e) =>
                              setPersonaForm((prev) => (prev ? { ...prev, description: e.target.value } : prev))
                            }
                            placeholder="Кратко опиши бота и его роль"
                          />
                        </label>
                        <label className="full">
                          <span>Metadata (JSON)</span>
                          <textarea
                            rows={4}
                            value={personaMeta}
                            onChange={(e) => setPersonaMeta(e.target.value)}
                            placeholder='{"background":{"location":"..."}}'
                          />
                        </label>
                      </div>
                    </div>

                    <div className="card">
                      <div className="card__header">
                        <div>
                          <p className="eyebrow">Поведение</p>
                          <h3>Как говорить</h3>
                          <p className="muted tiny">Правила, которые попадут в системный промпт.</p>
                        </div>
                        <button className="ghost" onClick={() => addPersonaItem('behavior')}>
                          Добавить правило
                        </button>
                      </div>
                      <div className="list">
                        {(personaForm.behavior ?? []).map((item, idx) => (
                          <div className="item" key={`behavior-${idx}`}>
                            <input
                              value={item}
                              onChange={(e) => updatePersonaArray('behavior', idx, e.target.value)}
                              placeholder="Например: говори естественно, не упоминай ИИ"
                            />
                            <button
                              className="ghost danger"
                              onClick={() => removePersonaItem('behavior', idx)}
                              title="Удалить"
                            >
                              ×
                            </button>
                          </div>
                        ))}
                        {personaForm.behavior.length === 0 ? (
                          <p className="muted tiny">Пока нет правил поведения</p>
                        ) : null}
                      </div>
                    </div>

                    <div className="card two-col">
                      <div>
                        <div className="card__header">
                          <div>
                            <p className="eyebrow">Черты</p>
                            <h3>Личность</h3>
                            <p className="muted tiny">Короткие маркеры характера.</p>
                          </div>
                          <button className="ghost" onClick={() => addPersonaItem('traits')}>
                            Добавить черту
                          </button>
                        </div>
                        <div className="pill-list">
                          {(personaForm.traits ?? []).map((trait, idx) => (
                            <span key={`trait-${idx}`} className="pill">
                              <input
                                value={trait}
                                onChange={(e) => updatePersonaArray('traits', idx, e.target.value)}
                                placeholder="дружелюбный"
                              />
                              <button className="ghost danger" onClick={() => removePersonaItem('traits', idx)}>
                                ×
                              </button>
                            </span>
                          ))}
                          {personaForm.traits.length === 0 ? (
                            <p className="muted tiny">Добавьте хотя бы 2-3 черты</p>
                          ) : null}
                        </div>
                      </div>
                      <div>
                        <div className="card__header">
                          <div>
                            <p className="eyebrow">Ограничения</p>
                            <h3>Чего избегать</h3>
                            <p className="muted tiny">Жёсткие стоп-фразы.</p>
                          </div>
                          <button className="ghost" onClick={() => addPersonaItem('limitations')}>
                            Добавить ограничение
                          </button>
                        </div>
                        <div className="pill-list">
                          {(personaForm.limitations ?? []).map((lim, idx) => (
                            <span key={`lim-${idx}`} className="pill">
                              <input
                                value={lim}
                                onChange={(e) => updatePersonaArray('limitations', idx, e.target.value)}
                                placeholder="не говори, что ты бот"
                              />
                              <button className="ghost danger" onClick={() => removePersonaItem('limitations', idx)}>
                                ×
                              </button>
                            </span>
                          ))}
                          {personaForm.limitations.length === 0 ? (
                            <p className="muted tiny">Добавьте базовые ограничения</p>
                          ) : null}
                        </div>
                      </div>
                    </div>
                  </div>

                  <div className="persona-actions">
                    <button
                      onClick={savePersonaForm}
                      disabled={personaSaving || personaLoading || !selectedBot}
                    >
                      {personaSaving ? 'Сохраняем...' : 'Сохранить персону'}
                    </button>
                    <button
                      className="ghost"
                      onClick={() => selectedBot && loadPersona(selectedBot, personaLang)}
                      disabled={personaLoading || !selectedBot}
                    >
                      Обновить из БД
                    </button>
                  </div>
                </>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

export default App
