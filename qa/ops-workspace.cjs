'use strict'

const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')

function operationsRoot (environment = process.env) {
  if (environment.RUSCRAFTING_OPS_HOME) {
    return path.resolve(environment.RUSCRAFTING_OPS_HOME)
  }
  const workspace = environment.RUSCRAFTING_WORKSPACE_ROOT || path.join(os.homedir(), 'RusCrafting')
  const canonical = path.resolve(workspace, 'ruscrafting-ops')
  if (environment.RUSCRAFTING_WORKSPACE_ROOT) return canonical
  if (fs.existsSync(canonical)) return canonical
  const legacy = path.join(os.homedir(), 'mcserver')
  return fs.existsSync(legacy) ? legacy : canonical
}

function playerBotPath (relativePath, environment = process.env) {
  return path.join(operationsRoot(environment), 'scripts/player-bot', relativePath)
}

module.exports = { operationsRoot, playerBotPath }
