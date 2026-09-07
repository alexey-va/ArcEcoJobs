import assert from 'node:assert/strict';
import { test, expect, waitUntil, waitForStable } from '@drownek/plugwright';

async function state(player, signal) {
  const since = player.getMessageBufferIndex();
  player.chat('/arce2e state explorer');
  let match;
  await waitUntil(() => {
    const reply = player.messageBuffer.slice(since).map(String).find(message => message.includes('E2E_STATE xp='));
    match = reply?.match(/xp=([0-9.]+) balance=([0-9.]+)/);
    return match;
  }, { signal, message: 'Real explorer XP and Vault balance must be readable' });
  return { xp: Number(match[1]), balance: Number(match[2]) };
}

async function setup(player, signal) {
  await player.makeOp();
  player.chat('/arce2e setup explorer');
  await expect(player).toHaveReceivedMessage('E2E_SETUP');
  // The pinned native libreforge holder cache expires after four seconds.
  await player.bot.waitForTicks(100);
  await player.teleport(4094.5, 65, 4096.5);
  await waitUntil(() => Math.abs(player.bot.entity.position.x - 4094.5) < 0.5, { signal });
}

async function cross(player, x, signal) {
  try {
    const destination = player.bot.entity.position.clone().set(x, 65, 4096.5);
    await player.bot.lookAt(destination.offset(0, 1.5, 0), true);
    player.bot.setControlState('forward', true);
    await waitUntil(() => player.bot.entity.position.distanceTo(destination) < 0.5, {
      signal, timeout: 10000, message: 'Explorer must cross the chunk edge by walking',
    });
  } finally {
    player.bot.clearControlStates();
  }
}

async function expectReward(player, expected, signal) {
  await waitUntil(async () => {
    const actual = await state(player, signal);
    return Math.abs(actual.xp - expected.xp) < 1e-8 && Math.abs(actual.balance - expected.balance) < 1e-8;
  }, { signal, timeout: 10000, interval: 250, message: `Explorer must receive ${JSON.stringify(expected)}` });
}

test('walking discovers a chunk once, persists across rejoin and pays only the first five explorers', async ({ player, createPlayer, signal }) => {
  await setup(player, signal);
  assert.deepEqual(await state(player, signal), { xp: 0, balance: 0 }, 'teleport alone must not discover a chunk');
  await cross(player, 4097.5, signal);
  await expectReward(player, { xp: 1, balance: 1.5 }, signal);
  await cross(player, 4094.5, signal);
  await expectReward(player, { xp: 2, balance: 3 }, signal);
  await cross(player, 4097.5, signal);
  await waitForStable(async () => {
    const current = await state(player, signal);
    return current.xp === 2 && current.balance === 3;
  }, { signal, duration: 1000, interval: 250, message: 'Returning to the same chunk must not mint another reward' });
  await player.rejoin();
  await expectReward(player, { xp: 2, balance: 3 }, signal);
  await cross(player, 4094.5, signal);
  await cross(player, 4097.5, signal);
  assert.deepEqual(await state(player, signal), { xp: 2, balance: 3 }, 'rejoin must preserve discovery deduplication');

  // Native rank multipliers: money 80/60/40/10%, XP 100/80/50/10%; sixth is rejected.
  const rewards = [
    { xp: 1, balance: 1.2 },
    { xp: 0.8, balance: 0.9 },
    { xp: 0.5, balance: 0.6 },
    { xp: 0.1, balance: 0.15 },
    { xp: 0, balance: 0 },
  ];
  for (const [index, reward] of rewards.entries()) {
    const explorer = await createPlayer({ username: `ExploreE2E${index + 2}` });
    await setup(explorer, signal);
    assert.deepEqual(await state(explorer, signal), { xp: 0, balance: 0 });
    await cross(explorer, 4097.5, signal);
    if (index === rewards.length - 1) {
      await waitForStable(async () => {
        const current = await state(explorer, signal);
        return current.xp === 0 && current.balance === 0;
      }, { signal, duration: 1500, interval: 250, message: 'A sixth discoverer must not receive XP or money' });
    } else {
      await expectReward(explorer, reward, signal);
    }
    explorer.bot.quit();
  }
  await expectReward(player, { xp: 2, balance: 3 }, signal);
});
