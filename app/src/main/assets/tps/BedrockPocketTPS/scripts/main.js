import { system } from '@minecraft/server';

let previousTick = system.currentTick;
let previousTime = Date.now();

system.runInterval(() => {
  const currentTick = system.currentTick;
  const now = Date.now();
  const elapsedSeconds = Math.max(0.1, (now - previousTime) / 1000);
  const tps = Math.max(0, Math.min(20, (currentTick - previousTick) / elapsedSeconds));

  console.warn(`BEDROCKPOCKET_TPS:${tps.toFixed(1)};tick=${currentTick}`);

  previousTick = currentTick;
  previousTime = now;
}, 100);
