#!/usr/bin/env node
'use strict'

const { once } = require('node:events')
const { playerBotPath } = require('./ops-workspace.cjs')
const { Vec3 } = require(playerBotPath('node_modules/vec3'))
const mineflayer = require(playerBotPath('node_modules/mineflayer'))
const { pathfinder, Movements, goals } = require(playerBotPath('node_modules/mineflayer-pathfinder'))

const LAB_HOST = 'mc.rus-crafting.ru'
const LAB_PORT = 54294
const LAB_VERSION = '1.21.11'
const QA_IDENTITIES = new Set(['CodexQA_728', 'CodexQA_729', 'CodexQA_730'])
const wait = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds))

function readConfig (environment = process.env) {
  const config = {
    host: environment.ARC_ECOJOBS_QA_HOST || LAB_HOST,
    port: Number.parseInt(environment.ARC_ECOJOBS_QA_PORT || String(LAB_PORT), 10),
    username: environment.ARC_ECOJOBS_QA_USERNAME || 'CodexQA_729',
    version: environment.ARC_ECOJOBS_QA_VERSION || LAB_VERSION
  }
  if (config.host !== LAB_HOST || config.port !== LAB_PORT || config.version !== LAB_VERSION) {
    throw new Error('Explorer mutation smoke is restricted to the isolated Paper lab')
  }
  if (!QA_IDENTITIES.has(config.username)) {
    throw new Error('Explorer mutation smoke requires an allowlisted OP QA identity')
  }
  return config
}

function parseBalance (messages) {
  const line = [...messages].reverse().find((message) => /(?:balance|баланс)/iu.test(message))
  if (!line) return null
  const values = line.match(/-?\d[\d\s\u00a0.,]*/gu)
  if (!values?.length) return null
  let raw = values.at(-1).replace(/[\s\u00a0]/gu, '')
  const comma = raw.lastIndexOf(',')
  const dot = raw.lastIndexOf('.')
  if (comma >= 0 && dot >= 0) {
    const decimal = comma > dot ? ',' : '.'
    raw = raw.replace(decimal === ',' ? /\./gu : /,/gu, '').replace(decimal, '.')
  } else if (comma >= 0) {
    const decimals = raw.length - comma - 1
    raw = decimals > 0 && decimals <= 2 ? raw.replace(',', '.') : raw.replace(/,/gu, '')
  }
  const value = Number.parseFloat(raw)
  return Number.isFinite(value) ? value : null
}

async function commandMessages (bot, messages, command, settleMilliseconds = 1500) {
  const offset = messages.length
  bot.chat(command)
  await wait(settleMilliseconds)
  return messages.slice(offset)
}

async function balance (bot, messages) {
  const response = await commandMessages(bot, messages, '/balance')
  const value = parseBalance(response)
  if (value === null) throw new Error(`Could not parse QA balance response: ${JSON.stringify(response)}`)
  return value
}

async function main () {
  const config = readConfig()
  const messages = []
  const bot = mineflayer.createBot({ ...config, auth: 'offline' })
  let switchedToSurvival = false
  bot.loadPlugin(pathfinder)
  bot.on('messagestr', (message) => messages.push(message))

  try {
    await Promise.race([
      once(bot, 'spawn'),
      wait(30000).then(() => { throw new Error('spawn timeout') })
    ])
    await wait(2500)
    const initial = bot.entity.position.clone()
    await bot.creative.flyTo(new Vec3(initial.x, initial.y + 3, initial.z))
    await commandMessages(bot, messages, '/gamemode survival', 1000)
    switchedToSurvival = true
    const joinMessages = await commandMessages(bot, messages, '/jobs join explorer', 2000)
    const before = await balance(bot, messages)
    const start = bot.entity.position.clone()
    bot.pathfinder.setMovements(new Movements(bot))
    await Promise.race([
      bot.pathfinder.goto(new goals.GoalXZ(Math.floor(start.x) + 80, Math.floor(start.z))),
      wait(60000).then(() => { throw new Error('walking route timeout') })
    ])
    await wait(2500)
    const after = await balance(bot, messages)
    const end = bot.entity.position.clone()
    const startChunk = [Math.floor(start.x) >> 4, Math.floor(start.z) >> 4]
    const endChunk = [Math.floor(end.x) >> 4, Math.floor(end.z) >> 4]
    if (startChunk[0] === endChunk[0] && startChunk[1] === endChunk[1]) {
      throw new Error('QA bot did not cross a chunk boundary')
    }
    if (!(after > before)) {
      throw new Error(`Explorer reward was not credited after chunk movement; delta=${after - before}`)
    }
    console.log(JSON.stringify({
      verdict: 'PASS',
      job: 'explorer',
      startChunk,
      endChunk,
      rewardDelta: Number((after - before).toFixed(2)),
      joinAcknowledged: joinMessages.length > 0
    }, null, 2))
    bot.chat('/gamemode creative')
    bot.quit('ArcEcoJobs explorer QA complete')
  } catch (error) {
    if (switchedToSurvival && bot.entity) {
      bot.chat('/gamemode creative')
      await wait(500)
    }
    console.log(JSON.stringify({
      verdict: 'FAIL',
      error: error.stack,
      recentChats: messages.slice(-12)
    }, null, 2))
    bot.end()
    process.exitCode = 1
  }
}

if (require.main === module) void main()

module.exports = { parseBalance, readConfig }
