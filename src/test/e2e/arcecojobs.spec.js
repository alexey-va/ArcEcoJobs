import { test, expect, waitUntil } from '@drownek/plugwright';
import assert from 'node:assert/strict';

async function state(player) {
  const since = player.messageBuffer.length;
  player.chat('/arce2e state');
  let message;
  await waitUntil(() => {
    message = player.messageBuffer.slice(since).find(entry => /E2E_STATE xp=/.test(String(entry)));
    return Boolean(message);
  }, { timeout: 10000, message: 'E2E state command did not reply' });
  message = String(message);
  const match = message.match(/xp=([0-9.]+) balance=([0-9.]+)/);
  assert.ok(match, `Unexpected state message: ${message}`);
  return { xp: Number(match[1]), balance: Number(match[2]) };
}

async function kill(player, kind = 'zombie', suffix = '') {
  player.chat(`/arce2e spawn ${kind}${suffix ? ` ${suffix}` : ''}`);
  await expect(player).toHaveReceivedMessage(/E2E_SPAWN=/, { timeout: 10000 });
  await waitUntil(() => Object.values(player.bot.entities).some(entity => entity.name === kind), {
    timeout: 10000, message: `No spawned ${kind} reached the bot`,
  });
  const target = Object.values(player.bot.entities).find(entity => entity.name === kind);
  assert.ok(target, `No spawned ${kind} reached the bot`);
  await player.bot.lookAt(target.position.offset(0, 1, 0), true);
  player.bot.attack(target);
  await waitUntil(() => player.bot.entities[target.id] === undefined, {
    timeout: 10000, message: `${kind} did not die to the real melee hit`,
  });
  await player.bot.waitForTicks(25);
}

test('jobs command loads the real EcoJobs catalog and opens a profession', async ({ player }) => {
  player.chat('/arcjobs inventory');
  const gui = await player.gui({ title: 'Jobs' });
  await gui.locator(item => item.getDisplayName() === 'Job catalog').click();
  const catalog = await player.gui({ title: 'Job catalog' });
  const miner = catalog.locator(item => item.getDisplayName().includes('Шахтер'));
  await expect(miner).toHaveLore('Level');
  await miner.click();
  await player.gui({ title: /Шахтер/ });
});

test('real EcoJobs reward path blocks AFK and stationary farms but preserves valid movement and filters', async ({ player }) => {
  await player.makeOp();
  player.chat('/arce2e setup');
  await expect(player).toHaveReceivedMessage('E2E_SETUP');
  // Native libreforge 2026.33 holder cache expires after four seconds.
  await player.bot.waitForTicks(100);
  await player.teleport(0.5, 65, 0.5);
  await player.giveItem('stone_sword', 1);
  await player.bot.equip(player.bot.inventory.items().find(item => item.name === 'stone_sword'), 'hand');

  const beforeAfk = await state(player);
  player.chat('/arce2e afk on');
  await expect(player).toHaveReceivedMessage('E2E_AFK=true');
  await kill(player);
  const afterAfk = await state(player);
  assert.deepEqual(afterAfk, beforeAfk, 'AFK kill must not award XP or money');
  player.chat('/arce2e afk off');
  await expect(player).toHaveReceivedMessage('E2E_AFK=false');

  await kill(player);
  const afterMoving = await state(player);
  assert.equal(afterMoving.xp, beforeAfk.xp + 1, 'active valid kill must award XP');
  assert.equal(afterMoving.balance, beforeAfk.balance + 1, 'active valid kill must award money');

  for (let i = 0; i < 17; i++) await kill(player);
  await kill(player, 'zombie', 'spawner');
  await kill(player, 'cow');
  const cooldownWindow = Date.now() + 31000;
  await waitUntil(() => Date.now() >= cooldownWindow, {
    timeout: 35000, interval: 100, message: 'stationary guard duration did not elapse',
  });
  await kill(player);
  const afterFiltered = await state(player);
  assert.equal(afterFiltered.xp, afterMoving.xp + 18, 'AFK and excluded targets must not consume stationary counter');
  assert.equal(afterFiltered.balance, afterMoving.balance + 18);
  await kill(player);
  assert.deepEqual(await state(player), afterFiltered, 'the stationary threshold kill must not award XP or money');
  await kill(player);
  assert.deepEqual(await state(player), afterFiltered, 'cooldown must reject repeated kills');

  await player.teleport(16.5, 65, 0.5);
  await kill(player);
  const afterMovedSite = await state(player);
  assert.equal(afterMovedSite.xp, afterFiltered.xp + 1, 'moving to a new site must keep rewards active');
  await player.deOp();
});
