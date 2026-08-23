#!/usr/bin/env node
'use strict'

const path = require('node:path')
const { once } = require('node:events')

const mineflayer = require(path.resolve(__dirname, '../../scripts/player-bot/node_modules/mineflayer'))

const wait = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds))

function countItem (bot, type) {
  return bot.inventory.items()
    .filter((item) => item.name === type)
    .reduce((total, item) => total + item.count, 0)
}

async function waitUntil (description, predicate, timeoutMs = 8000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (predicate()) return
    await wait(50)
  }
  throw new Error(`timeout waiting for ${description}`)
}

function waitForChat (bot, pattern, timeoutMs = 8000) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      bot.off('messagestr', onMessage)
      reject(new Error(`chat timeout for ${pattern}`))
    }, timeoutMs)
    function onMessage (message) {
      if (!pattern.test(message)) return
      clearTimeout(timeout)
      bot.off('messagestr', onMessage)
      resolve(message)
    }
    bot.on('messagestr', onMessage)
  })
}

async function commandAndWait (bot, command, pattern) {
  const response = waitForChat(bot, pattern)
  bot.chat(command)
  return response
}

async function useHeldInAir (bot, type) {
  const item = bot.inventory.items().find((entry) => entry.name === type)
  if (!item) throw new Error(`missing ${type} voucher`)
  await bot.equip(item, 'hand')
  await bot.look(bot.entity.yaw, -Math.PI / 2, true)
  bot.activateItem()
}

async function main () {
  if (process.env.ARC_ECOJOBS_QA_ALLOW_MUTATIONS !== 'true') {
    throw new Error('ARC_ECOJOBS_QA_ALLOW_MUTATIONS=true is required for voucher smoke QA')
  }
  const username = process.env.ARC_ECOJOBS_QA_USERNAME || 'CodexQA_730'
  const port = Number.parseInt(process.env.ARC_ECOJOBS_QA_PORT || '54294', 10)
  if (port !== 54294 || !/^CodexQA_73\d$/.test(username)) {
    throw new Error('voucher smoke is restricted to the disposable lab QA identity and port')
  }
  const bot = mineflayer.createBot({
    host: process.env.ARC_ECOJOBS_QA_HOST || 'mc.rus-crafting.ru',
    port,
    username,
    version: process.env.ARC_ECOJOBS_QA_VERSION || '1.21.11',
    auth: 'offline'
  })
  const chats = []
  bot.on('messagestr', (message) => chats.push(message))

  try {
    await Promise.race([
      once(bot, 'spawn'),
      new Promise((_, reject) => setTimeout(() => reject(new Error('spawn timeout')), 30000))
    ])
    bot.setSettings({ locale: 'ru_RU' })
    await wait(1500)

    await commandAndWait(bot, `/arcjobs boost revoke ${username} all`, /Удалено усилений:/i)
    bot.chat(`/clear ${username}`)
    await waitUntil('empty QA inventory', () => bot.inventory.items().length === 0)

    await commandAndWait(
      bot,
      `/arcjobs booster give ${username} focused-xp 2`,
      /Выдано 2 × focused-xp/i
    )
    await waitUntil('two XP vouchers', () => countItem(bot, 'lapis_lazuli') === 2)

    const firstMessage = waitForChat(bot, /Осталось:\s*30м/i)
    await useHeldInAir(bot, 'lapis_lazuli')
    await firstMessage
    await waitUntil('one XP voucher after first use', () => countItem(bot, 'lapis_lazuli') === 1)

    const stackedMessage = waitForChat(bot, /Осталось:\s*1ч(?:\s|\.|$)/i)
    await useHeldInAir(bot, 'lapis_lazuli')
    await stackedMessage
    await waitUntil('both XP vouchers redeemed', () => countItem(bot, 'lapis_lazuli') === 0)

    await commandAndWait(
      bot,
      `/arcjobs booster give ${username} quick-profit 1`,
      /Выдано 1 × quick-profit/i
    )
    await waitUntil('money voucher', () => countItem(bot, 'gold_nugget') === 1)
    const conflictMessage = waitForChat(bot, /другой тип усиления.+талон сохранён/i)
    await useHeldInAir(bot, 'gold_nugget')
    await conflictMessage
    await wait(500)
    if (countItem(bot, 'gold_nugget') !== 1) throw new Error('conflicting voucher was consumed')

    await commandAndWait(bot, `/arcjobs boost revoke ${username} all`, /Удалено усилений:/i)
    bot.chat(`/clear ${username}`)
    await waitUntil('clean QA inventory', () => bot.inventory.items().length === 0)

    console.log(JSON.stringify({
      verdict: 'PASS',
      cases: [
        'right-click in air redeems without vanilla use',
        'two 30m vouchers report 1h total',
        'different boost type is rejected and preserved',
        'QA boost and inventory are cleaned up'
      ],
      recentChats: chats.slice(-12)
    }, null, 2))
    bot.quit('ArcEcoJobs voucher QA complete')
  } catch (error) {
    console.log(JSON.stringify({
      verdict: 'FAIL',
      error: error.stack,
      inventory: bot.inventory?.items().map((item) => ({ type: item.name, count: item.count })) ?? [],
      recentChats: chats.slice(-15)
    }, null, 2))
    bot.end()
    process.exitCode = 1
  }
}

if (require.main === module) void main()
