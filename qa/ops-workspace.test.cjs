'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const { operationsRoot, playerBotPath } = require('./ops-workspace.cjs')

test('operations workspace accepts explicit and workspace-root overrides', () => {
  assert.equal(
    operationsRoot({ RUSCRAFTING_OPS_HOME: '/tmp/custom-ops' }),
    path.resolve('/tmp/custom-ops')
  )
  assert.equal(
    operationsRoot({ RUSCRAFTING_WORKSPACE_ROOT: '/tmp/custom-workspace' }),
    path.resolve('/tmp/custom-workspace/ruscrafting-ops')
  )
  assert.equal(
    playerBotPath('node_modules/mineflayer', { RUSCRAFTING_OPS_HOME: '/tmp/custom-ops' }),
    path.resolve('/tmp/custom-ops/scripts/player-bot/node_modules/mineflayer')
  )
})

test('QA scripts do not reintroduce monorepo-relative player-bot paths', () => {
  for (const name of ['explorer-smoke.cjs', 'gui-smoke.cjs', 'voucher-smoke.cjs']) {
    const text = fs.readFileSync(path.join(__dirname, name), 'utf8')
    assert.doesNotMatch(text, /\.\.\/\.\.\/scripts\/player-bot/)
    assert.match(text, /playerBotPath/)
  }
})
