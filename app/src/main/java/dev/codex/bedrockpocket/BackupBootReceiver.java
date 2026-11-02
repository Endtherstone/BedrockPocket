package dev.codex.bedrockpocket;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BackupBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){if(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())&&context.getSharedPreferences(BackupScheduler.PREFS,Context.MODE_PRIVATE).getBoolean("enabled",false)){int hours=context.getSharedPreferences(BackupScheduler.PREFS,Context.MODE_PRIVATE).getInt("interval_hours",24);BackupScheduler.scheduleNext(context,System.currentTimeMillis()+hours*60L*60L*1000L);}}
}
