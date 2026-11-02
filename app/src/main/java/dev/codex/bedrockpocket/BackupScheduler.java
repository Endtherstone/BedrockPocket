package dev.codex.bedrockpocket;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.io.File;

final class BackupScheduler {
    static final String PREFS="backup_schedule";
    static final String ACTION="dev.codex.bedrockpocket.SCHEDULED_BACKUP";
    private BackupScheduler(){}
    static void configure(Context c,boolean enabled,int intervalHours,int retention)throws Exception{
        if(intervalHours<6||intervalHours>168)throw new IllegalArgumentException("ช่วงสำรองต้องอยู่ระหว่าง 6–168 ชั่วโมง");
        if(retention<1||retention>30)throw new IllegalArgumentException("จำนวนชุดที่เก็บต้องอยู่ระหว่าง 1–30 ชุด");
        c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putBoolean("enabled",enabled).putInt("interval_hours",intervalHours).putInt("retention",retention).apply();
        if(enabled)schedule(c,System.currentTimeMillis()+intervalHours*60L*60L*1000L);else cancel(c);
    }
    static void scheduleNext(Context c,long at){if(c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getBoolean("enabled",false))schedule(c,at);}
    private static void schedule(Context c,long at){AlarmManager manager=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);if(manager==null)return;PendingIntent pending=pending(c);if(Build.VERSION.SDK_INT>=23)manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pending);else manager.set(AlarmManager.RTC_WAKEUP,at,pending);}
    private static void cancel(Context c){AlarmManager manager=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);if(manager!=null)manager.cancel(pending(c));}
    private static PendingIntent pending(Context c){Intent i=new Intent(c,BackupAlarmReceiver.class).setAction(ACTION);return PendingIntent.getBroadcast(c,5801,i,(Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0)|PendingIntent.FLAG_UPDATE_CURRENT);}
}
