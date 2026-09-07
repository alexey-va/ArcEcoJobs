import { test, expect, waitUntil } from '@drownek/plugwright';
import assert from 'node:assert/strict';

async function state(player, jobId = 'slayer') {
  const since = player.messageBuffer.length;
  player.chat(`/arce2e state ${jobId}`);
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

async function placeBlock(player, x, y, z) {
  const nearby = player.bot.entity.position.clone().set(x + 0.5, y + 1, z + 2.5);
  await player.teleport(nearby.x, nearby.y, nearby.z);
  await waitUntil(() => player.bot.entity.position.distanceTo(nearby) < 0.5, {
    timeout: 5000, message: `player did not reach placement at ${x},${y},${z}`,
  });
  const target = player.bot.entity.position.clone().set(x, y, z);
  const reference = player.bot.blockAt(target.clone().set(x, y - 1, z));
  assert.ok(reference, `missing reference block below ${x},${y},${z}`);
  await player.bot.equip(player.bot.inventory.items().find(item => item.name === 'stone'), 'hand');
  await player.bot.placeBlock(reference, target.clone().set(0, 1, 0));
  await waitUntil(() => player.bot.blockAt(target)?.name !== 'air', {
    timeout: 5000, message: `block was not placed at ${x},${y},${z}`,
  });
}

async function breakBlock(player, x, y, z) {
  const nearby = player.bot.entity.position.clone().set(x + 0.5, y + 1, z + 2.5);
  await player.teleport(nearby.x, nearby.y, nearby.z);
  await waitUntil(() => player.bot.entity.position.distanceTo(nearby) < 0.5, {
    timeout: 5000, message: `player did not reach break at ${x},${y},${z}`,
  });
  const target = player.bot.entity.position.clone().set(x, y, z);
  const block = player.bot.blockAt(target);
  assert.ok(block && block.name !== 'air', `missing placed block at ${x},${y},${z}`);
  await player.bot.equip(player.bot.inventory.items().find(item => item.name === 'diamond_pickaxe'), 'hand');
  await player.bot.dig(block);
  await waitUntil(() => player.bot.blockAt(target)?.name === 'air', {
    timeout: 5000, message: `block was not broken at ${x},${y},${z}`,
  });
}

async function waitForState(player, predicate, message, jobId = 'builder') {
  let current;
  await waitUntil(async () => {
    current = await state(player, jobId);
    return predicate(current);
  }, { timeout: 10000, message });
  return current;
}

async function metrics(player) {
  const since = player.messageBuffer.length;
  player.chat('/arce2e metrics');
  let message;
  await waitUntil(() => {
    message = player.messageBuffer.slice(since).find(entry => /E2E_METRICS/.test(String(entry)));
    return Boolean(message);
  }, { timeout: 10000, message: 'E2E metrics command did not reply' });
  const text = String(message);
  if (text.includes('unavailable')) {
    assert.ok(text.includes('required=false'), `Required ARC telemetry is unavailable: ${text}`);
    return null;
  }
  const result = {};
  for (const match of text.matchAll(/(\w+)_(observations|observedMillis)=([0-9]+)/g)) {
    result[`${match[1]}_${match[2]}`] = Number(match[3]);
  }
  return result;
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
  assert.equal(afterMoving.balance, beforeAfk.balance, 'every-N payout must defer the first money reward');

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
  assert.equal(afterFiltered.balance, afterMoving.balance + 6);
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

test('Builder pays new coordinates and blocks repeated coordinates for both XP and money', async ({ player }) => {
  await player.makeOp();
  player.chat('/arce2e setup builder');
  await expect(player).toHaveReceivedMessage('E2E_SETUP');
  await player.teleport(0.5, 65, 0.5);
  await player.giveItem('stone', 64);
  await player.giveItem('diamond_pickaxe', 1);

  const before = await state(player, 'builder');
  // Native ArgumentEvery starts at one and first pays every:12 on trigger 13.
  for (let x = 2; x < 15; x++) await placeBlock(player, x, 65, 0);
  const afterNew = await waitForState(
    player,
    current => current.xp > before.xp && current.balance > before.balance,
    'new Builder coordinates did not award XP and money',
  );
  assert.ok(Math.abs(afterNew.xp - before.xp - 13 * 1.2) < 1e-8);
  assert.equal(afterNew.balance - before.balance, 4);

  const repeated = { x: 2, y: 65, z: 0 };
  const beforeRepeat = await state(player, 'builder');
  for (let i = 0; i < 12; i++) {
    await breakBlock(player, repeated.x, repeated.y, repeated.z);
    await placeBlock(player, repeated.x, repeated.y, repeated.z);
  }
  const afterRepeat = await state(player, 'builder');
  assert.deepEqual(afterRepeat, beforeRepeat, 'repeated Builder coordinate must not award XP or money');

  for (let x = 15; x < 27; x++) await placeBlock(player, x, 65, 0);
  const afterResumed = await waitForState(
    player,
    current => current.xp > afterRepeat.xp && current.balance > afterRepeat.balance,
    'new Builder coordinates did not award XP and money after blocked repeats',
  );
  assert.ok(Math.abs(afterResumed.xp - afterRepeat.xp - 12 * 1.2) < 1e-8);
  assert.equal(afterResumed.balance - afterRepeat.balance, 4);
  await player.deOp();
});

test('native work reaches optional ARC independently of every-N money payouts', async ({ player }) => {
  await player.makeOp();
  player.chat('/arce2e setup');
  await expect(player).toHaveReceivedMessage('E2E_SETUP');
  await player.bot.waitForTicks(100);
  const before = await metrics(player);
  if (before === null) {
    await player.deOp();
    return; // The no-ARC configuration is explicit; required/failed ARC never reaches this branch.
  }
  await state(player);
  assert.deepEqual(await metrics(player), before, 'state/placeholder access must not count as work');
  player.chat('/arcjobs inventory');
  await player.gui({ title: 'Jobs' });
  assert.deepEqual(await metrics(player), before, 'opening the job menu must not count as work');
  player.bot.closeWindow(player.bot.currentWindow);
  await player.teleport(128.5, 65, 128.5);
  await player.giveItem('stone_sword', 1);
  await player.bot.equip(player.bot.inventory.items().find(item => item.name === 'stone_sword'), 'hand');

  const first = await state(player);
  const baseCount = before.slayer_observations ?? 0;
  const baseMillis = before.slayer_observedMillis ?? 0;
  await kill(player);
  await kill(player);
  const afterTwo = await metrics(player);
  assert.equal(afterTwo.slayer_observations, baseCount + 2, 'both native XP actions must be observed before money pays');
  assert.ok(afterTwo.slayer_observedMillis >= baseMillis + 1000, 'ARC must connect the two accepted action samples');
  assert.equal((await state(player)).balance, first.balance, 'every-N payout remains deferred before the third kill');
  await kill(player, 'zombie', 'spawner');
  await kill(player, 'cow');
  assert.deepEqual(await metrics(player), afterTwo, 'excluded targets must not create work observations');

  player.chat('/arce2e afk on');
  await expect(player).toHaveReceivedMessage('E2E_AFK=true');
  await kill(player);
  const afterAfk = await metrics(player);
  assert.deepEqual(afterAfk, afterTwo, 'AFK action must not extend or emit job work');
  player.chat('/arce2e afk off');
  await expect(player).toHaveReceivedMessage('E2E_AFK=false');
  await kill(player);
  const afterBreak = await metrics(player);
  assert.equal(afterBreak.slayer_observations, afterAfk.slayer_observations + 1,
    'first post-AFK work starts a fresh observation');
  assert.equal(afterBreak.slayer_observedMillis, afterAfk.slayer_observedMillis,
    'first post-AFK observation must not bridge the AFK interval');
  assert.equal((await state(player)).balance, first.balance + 1, 'third accepted native action pays the every-N reward');
  await kill(player);
  const afterResume = await metrics(player);
  assert.equal(afterResume.slayer_observations, afterBreak.slayer_observations + 1);
  assert.ok(afterResume.slayer_observedMillis > afterBreak.slayer_observedMillis);
  assert.equal((await state(player)).balance, first.balance + 1, 'later accepted actions do not duplicate the every-N payout');
  await player.deOp();
});
