import { test, expect } from '@drownek/plugwright';

test('jobs command loads the real EcoJobs catalog and opens a profession', async ({ player }) => {
  player.chat('/arcjobs inventory');
  const gui = await player.gui({ title: 'Jobs' });
  await gui.locator(item => item.getDisplayName() === 'Job catalog').click();
  const catalog = await player.gui({ title: 'Job catalog' });
  const miner = catalog.locator(item => item.getDisplayName().includes('Miner'));
  await expect(miner).toHaveLore('Level');
  await miner.click();
  await player.gui({ title: /Miner/ });
});
