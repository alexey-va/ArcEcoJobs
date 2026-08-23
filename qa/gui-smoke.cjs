#!/usr/bin/env node
'use strict'

const path = require('node:path')
const { once } = require('node:events')

const mineflayer = require(path.resolve(__dirname, '../../scripts/player-bot/node_modules/mineflayer'))
const { chatComponentText } = require(path.resolve(__dirname, '../../scripts/player-bot/src/visual-observer.cjs'))

const wait = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds))

function readConfig (environment = process.env) {
  const port = Number.parseInt(environment.ARC_ECOJOBS_QA_PORT || '54294', 10)
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('ARC_ECOJOBS_QA_PORT must be a valid TCP port')
  const locale = environment.ARC_ECOJOBS_QA_LOCALE || 'ru_RU'
  const expectAdmin = (environment.ARC_ECOJOBS_QA_ROLE || 'admin').toLowerCase() === 'admin'
  const verbose = environment.ARC_ECOJOBS_QA_VERBOSE === 'true'
  const allowMutations = environment.ARC_ECOJOBS_QA_ALLOW_MUTATIONS === 'true'
  return {
    host: environment.ARC_ECOJOBS_QA_HOST || 'mc.rus-crafting.ru',
    port,
    username: environment.ARC_ECOJOBS_QA_USERNAME || (expectAdmin ? 'CodexQA_730' : 'CodexQA_731'),
    version: environment.ARC_ECOJOBS_QA_VERSION || '1.21.11',
    locale,
    expectAdmin,
    verbose,
    allowMutations
  }
}

function componentText (value) {
  if (value === null || value === undefined) return ''
  const parsed = chatComponentText(value)
  if (parsed) return parsed
  if (typeof value === 'string') return value
  return String(value.text ?? value.translate ?? '')
}

function itemName (item) {
  return componentText(item?.customName) || componentText(item?.displayName) || item?.name || ''
}

function snapshot (window) {
  return {
    title: componentText(window?.title),
    slots: (window?.slots ?? [])
      .map((item, slot) => {
        if (!item) return null
        const lore = (item.customLore ?? []).map(componentText)
        return {
          slot,
          name: itemName(item),
          type: item.name,
          count: item.count,
          ...(lore.length > 0 ? { lore } : {})
        }
      })
      .filter(Boolean)
  }
}

function isFiller (item) {
  if (!/_stained_glass_pane$/.test(item.type)) return false
  const normalizedName = String(item.name ?? '').trim().toLowerCase().replaceAll(' ', '_')
  return normalizedName === '' || normalizedName === item.type
}

function compactSnapshot (observed) {
  if (!observed?.slots) return observed
  const meaningful = observed.slots.filter((item) => !isFiller(item))
  const items = meaningful.length <= 18
    ? meaningful
    : [...meaningful.slice(0, 12), ...meaningful.slice(-6)]
  const { slots, title, ...details } = observed
  return { title, ...details, items }
}

function hasSlot (state, slot) {
  return state.slots.some((item) => item.slot === slot && !isFiller(item))
}

function hasNamedItem (state, pattern) {
  return state.slots.some((item) => pattern.test(item.name))
}

function slotType (state, slot) {
  return state.slots.find((item) => item.slot === slot && !isFiller(item))?.type
}

function countCatalogCards (state) {
  return state.slots.filter((item) =>
    item.slot >= 10 && item.slot <= 43 &&
    !isFiller(item) &&
    !['arrow', 'barrier'].includes(item.type)
  ).length
}

function record (results, caseName, expected, observed, pass) {
  results.push({ case: caseName, expected, observed: compactSnapshot(observed), pass })
  if (!pass) throw new Error(`${caseName}: expected ${expected}, observed ${JSON.stringify(observed)}`)
}

function waitForWindow (bot, timeoutMs = 7000) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      bot.off('windowOpen', onOpen)
      reject(new Error('window open timeout'))
    }, timeoutMs)
    function onOpen (window) {
      clearTimeout(timeout)
      bot.off('windowOpen', onOpen)
      resolve(window)
    }
    bot.on('windowOpen', onOpen)
  })
}

async function openJobs (bot) {
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
  await wait(350)
  const next = waitForWindow(bot)
  bot.chat('/jobs')
  const window = await next
  await wait(450)
  return window
}

async function clickNext (bot, slot) {
  return (await clickNextMeasured(bot, slot)).window
}

async function clickNextMeasured (bot, slot) {
  await wait(350)
  const next = waitForWindow(bot)
  const startedAt = Date.now()
  await bot.clickWindow(slot, 0, 0)
  const window = await next
  const openMs = Date.now() - startedAt
  await wait(450)
  return { window, openMs }
}

async function waitForCurrentWindow (bot, predicate, timeoutMs = 10000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const state = snapshot(bot.currentWindow)
    if (predicate(state)) return bot.currentWindow
    await wait(50)
  }
  throw new Error(`window state timeout: ${JSON.stringify(compactSnapshot(snapshot(bot.currentWindow)))}`)
}

function inventoryCount (bot, type) {
  return bot.inventory.items()
    .filter((item) => item.name === type)
    .reduce((total, item) => total + item.count, 0)
}

async function runScenario (bot, config) {
  const results = []
  const russian = config.locale.toLowerCase().startsWith('ru')
  const expectedMainTitle = russian ? 'Работы' : 'Jobs'
  const expectedCatalogName = russian ? 'Каталог профессий' : 'Job catalog'

  let window = await openJobs(bot)
  let state = snapshot(window)
  record(
    results,
    'main menu',
    `${expectedMainTitle} title, player sections, and correct admin visibility`,
    state,
    state.title === expectedMainTitle &&
      [20, 22, 24, 30, 32].every((slot) => hasSlot(state, slot)) &&
      slotType(state, 20) === 'crafting_table' &&
      !hasNamedItem(state, /^(?:Close|Закрыть)$/i) &&
      hasNamedItem(state, new RegExp(`^${expectedCatalogName}$`)) &&
      hasSlot(state, 49) === config.expectAdmin
  )

  window = await clickNext(bot, 22)
  state = snapshot(window)
  record(results, 'active jobs', 'focused active-jobs view with empty or populated state', state,
    /My jobs|Мои профессии/i.test(state.title) && hasSlot(state, 45))

  window = await openJobs(bot)
  const catalogIconBefore = inventoryCount(bot, 'crafting_table')
  const catalogOpen = await clickNextMeasured(bot, 20)
  window = catalogOpen.window
  state = snapshot(window)
  state.openMs = catalogOpen.openMs
  state.catalogIconInventoryDelta = inventoryCount(bot, 'crafting_table') - catalogIconBefore
  record(results, 'catalog', 'catalog opens under 1.5 s, keeps the selector protected, and has ten job cards', state,
    /catalog|Каталог/i.test(state.title) && countCatalogCards(state) === 10 &&
      state.openMs < 1500 && state.catalogIconInventoryDelta === 0 &&
      !hasNamedItem(state, /^(?:Close|Закрыть)$/i))

  window = await clickNext(bot, 19)
  window = await clickNext(bot, 29)
  state = snapshot(window)
  const hunterLevelLore = state.slots.find((item) => /(?:Level|Уровень) 1$/.test(item.name))?.lore ?? []
  record(results, 'hunter level lore', 'each reward is a separate lore row and money uses a postfix coin glyph', state,
      hunterLevelLore.length >= 6 &&
      hunterLevelLore.every((line) => !/[\u0000-\u001f\u007f]/u.test(line)) &&
      // Mineflayer currently decodes the glyph's two UTF-16 surrogate units as six U+FFFD characters.
      hunterLevelLore.some((line) => /\d(?:[\d ,.]*\d)?\s(?:💰|\uFFFD{6})(?:\s|$)/u.test(line)) &&
      hunterLevelLore.every((line) => !line.includes('$')))

  window = await clickNext(bot, 45)
  window = await clickNext(bot, 45)
  window = await clickNext(bot, 10)
  state = snapshot(window)
  record(results, 'job card', 'overview, levels, leaderboard, boosts, and join/leave action', state,
    [13, 29, 31, 33, 40, 45].every((slot) => hasSlot(state, slot)))

  window = await clickNext(bot, 29)
  state = snapshot(window)
  record(results, 'native level scale', 'first page starts at level 1 and exposes paging', state,
    /levels|уровни/i.test(state.title) && hasNamedItem(state, /(?:Level|Уровень) 1$/) && hasSlot(state, 51))

  window = await clickNext(bot, 51)
  state = snapshot(window)
  record(results, 'native level scale tail', 'second page reaches exact level 50', state,
    hasNamedItem(state, /(?:Level|Уровень) 29$/) && hasNamedItem(state, /(?:Level|Уровень) 50$/))

  window = await clickNext(bot, 45)
  window = await clickNext(bot, 31)
  if (!hasSlot(snapshot(window), 49)) {
    window = await waitForCurrentWindow(bot, (candidate) => hasSlot(candidate, 49))
  }
  state = snapshot(window)
  record(results, 'per-job leaderboard', 'job ranking with an explicit self-rank card', state,
    /Leaders|Лидеры/i.test(state.title) && hasSlot(state, 49))

  window = await clickNext(bot, 45)
  window = await clickNext(bot, 33)
  state = snapshot(window)
  record(results, 'job boost status', 'job summary plus active or empty boost state', state,
    /Boosts|Усиления/i.test(state.title) && hasSlot(state, 4) && hasSlot(state, 22))

  window = await openJobs(bot)
  window = await clickNext(bot, 24)
  state = snapshot(window)
  record(results, 'leaderboard selector', 'global ranking plus ten per-job ranking routes', state,
    hasSlot(state, 4) && countCatalogCards(state) === 10)

  window = await clickNext(bot, 4)
  if (!hasSlot(snapshot(window), 49)) {
    window = await waitForCurrentWindow(bot, (candidate) => hasSlot(candidate, 49))
  }
  state = snapshot(window)
  record(results, 'global leaderboard', 'overall ranking with an explicit self-rank card', state,
    /Overall|Общий/i.test(state.title) && hasSlot(state, 49))

  window = await openJobs(bot)
  window = await clickNext(bot, 30)
  state = snapshot(window)
  record(results, 'global boost status', 'global summary plus active or empty boost state', state,
    /Boosts|Усиления/i.test(state.title) && hasSlot(state, 4) && hasSlot(state, 22))

  window = await openJobs(bot)
  window = await clickNext(bot, 32)
  state = snapshot(window)
  record(results, 'help', 'four focused help topics', state,
    [10, 12, 14, 16, 36].every((slot) => hasSlot(state, slot)) &&
      !hasNamedItem(state, /^(?:Close|Закрыть)$/i))

  if (config.expectAdmin) {
    window = await openJobs(bot)
    window = await clickNext(bot, 49)
    state = snapshot(window)
    const commandLore = state.slots.find((item) => item.slot === 24)?.lore ?? []
    record(results, 'admin menu', 'integration status, reload, presets, and command reference', state,
      [4, 20, 22, 24, 36].every((slot) => hasSlot(state, slot)) &&
        slotType(state, 20) === 'repeater' &&
        !hasNamedItem(state, /^(?:Close|Закрыть)$/i) &&
        commandLore.length >= 13)

    if (config.allowMutations) {
      window = await clickNext(bot, 20)
      state = snapshot(window)
      record(results, 'admin reload', 'configuration reload returns to the admin menu', state,
        /management|Управление/i.test(state.title) && hasSlot(state, 20))

      const coldLeaderboardStartedAt = Date.now()
      window = await openJobs(bot)
      window = await clickNext(bot, 24)
      window = await clickNext(bot, 4)
      if (!hasSlot(snapshot(window), 49)) {
        window = await waitForCurrentWindow(bot, (candidate) => hasSlot(candidate, 49))
      }
      state = snapshot(window)
      state.rebuildMs = Date.now() - coldLeaderboardStartedAt
      record(results, 'cold leaderboard refresh', 'invalidated ranking refreshes in the background within 10 s', state,
        hasSlot(state, 49) && state.rebuildMs < 10000)
    }

    window = await openJobs(bot)
    window = await clickNext(bot, 49)
    window = await clickNext(bot, 22)
    state = snapshot(window)
    const voucherTypes = new Set([
      'amethyst_shard',
      'clock',
      'echo_shard',
      'lapis_lazuli',
      'enchanted_book',
      'prismarine_crystals',
      'gold_nugget',
      'emerald',
      'nautilus_shell'
    ])
    const voucherItems = state.slots.filter((item) => voucherTypes.has(item.type))
    const voucherLore = voucherItems.flatMap((item) => item.lore ?? [])
    const localizedDuration = russian
      ? voucherLore.some((line) => /\d+[чм]/u.test(line)) && voucherLore.every((line) => !/\d+[hm](?:\s|$)/i.test(line))
      : voucherLore.some((line) => /\d+[hm](?:\s|$)/i.test(line))
    record(results, 'booster presets', 'nine distinct signed previews with locale-specific duration units', state,
      voucherItems.length === 9 && new Set(voucherItems.map((item) => item.type)).size === 9 && localizedDuration)
  }

  return results
}

async function main () {
  const config = readConfig()
  const chats = []
  const bot = mineflayer.createBot({
    host: config.host,
    port: config.port,
    username: config.username,
    version: config.version,
    auth: 'offline'
  })
  bot.on('messagestr', (message) => chats.push(message))

  try {
    let spawnTimeout
    try {
      await Promise.race([
        once(bot, 'spawn'),
        new Promise((_, reject) => {
          spawnTimeout = setTimeout(() => reject(new Error('spawn timeout')), 30000)
        })
      ])
    } finally {
      clearTimeout(spawnTimeout)
    }
    bot.setSettings({ locale: config.locale })
    await wait(2500)
    const results = await runScenario(bot, config)
    const report = {
      verdict: 'PASS',
      locale: config.locale,
      role: config.expectAdmin ? 'admin' : 'player',
      passed: results.length,
      cases: results.map((result) => result.case),
      recentChats: chats.slice(-10)
    }
    if (config.verbose) report.results = results
    console.log(JSON.stringify(report, null, 2))
    bot.quit('ArcEcoJobs read-only GUI QA complete')
  } catch (error) {
    console.log(JSON.stringify({
      verdict: 'FAIL',
      locale: config.locale,
      role: config.expectAdmin ? 'admin' : 'player',
      error: error.stack,
      recentChats: chats.slice(-10),
      window: compactSnapshot(snapshot(bot.currentWindow))
    }, null, 2))
    bot.end()
    process.exitCode = 1
  }
}

if (require.main === module) void main()

module.exports = {
  compactSnapshot,
  componentText,
  countCatalogCards,
  hasNamedItem,
  hasSlot,
  readConfig,
  slotType,
  snapshot
}
