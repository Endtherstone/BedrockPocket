package dev.codex.bedrockpocket;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public final class BackupAlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){if(!BackupScheduler.ACTION.equals(intent.getAction()))return;android.content.SharedPreferences prefs=context.getSharedPreferences(BackupScheduler.PREFS,Context.MODE_PRIVATE);if(!prefs.getBoolean("enabled",false))return;PendingResult result=goAsync();new Thread(()->{PowerManager.WakeLock lock=null;long nextDelay=prefs.getInt("interval_hours",24)*60L*60L*1000L;try{PowerManager pm=(PowerManager)context.getSystemService(Context.POWER_SERVICE);if(pm!=null){lock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"BedrockPocket:ScheduledBackup");lock.acquire(8*60*1000L);}String state=context.getSharedPreferences("server",Context.MODE_PRIVATE).getString("server_state","STOPPED");if("RUNNING".equals(state)||"STARTING".equals(state)||"STOPPING".equals(state)){prefs.edit().putString("last_result","เลื่อนสำรอง: BDS ยังทำงานอยู่").putLong("last_run",System.currentTimeMillis()).apply();notify(context,"เลื่อนสำรองข้อมูล","BDS ยังทำงานอยู่ · จะลองใหม่ใน 1 ชั่วโมง");nextDelay=60L*60L*1000L;return;}
            File server=ServerProfileManager.activeServerDir(context),worlds=new File(server,"worlds");if(!worlds.isDirectory())throw new java.io.IOException("ยังไม่มีโฟลเดอร์ worlds");File root=new File(context.getFilesDir(),"backups");if(!root.isDirectory()&&!root.mkdirs())throw new java.io.IOException("สร้างโฟลเดอร์สำรองไม่ได้");String profile=ServerProfileManager.activeId(context);String stamp=new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date());File file=new File(root,"scheduled-"+profile+"-"+stamp+".zip");try(OutputStream out=new FileOutputStream(file)){WorldBackupManager.create(server,out);}prune(root,profile,prefs.getInt("retention",7));prefs.edit().putString("last_result","สำรองสำเร็จ: "+file.getName()).putLong("last_run",System.currentTimeMillis()).apply();notify(context,"สำรองโลกสำเร็จ",ServerProfileManager.name(context,profile)+" · "+file.getName());
        }catch(Exception e){prefs.edit().putString("last_result","สำรองไม่สำเร็จ: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage())).putLong("last_run",System.currentTimeMillis()).apply();notify(context,"สำรองโลกไม่สำเร็จ",e.getMessage()==null?"ตรวจพื้นที่และบันทึกการทำงาน":e.getMessage());}
        finally{if(lock!=null&&lock.isHeld())lock.release();BackupScheduler.scheduleNext(context,System.currentTimeMillis()+nextDelay);result.finish();}
        },"BedrockPocket-ScheduledBackup").start();}
    private void prune(File root,String profile,int keep){File[] files=root.listFiles((dir,name)->name.startsWith("scheduled-"+profile+"-")&&name.endsWith(".zip"));if(files==null)return;Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));for(int i=keep;i<files.length;i++)files[i].delete();}
    private void notify(Context c,String title,String body){String channel="bedrockpocket_backups";NotificationManager manager=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);if(manager==null)return;if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(new NotificationChannel(channel,"สำรองข้อมูล BDS",NotificationManager.IMPORTANCE_DEFAULT));Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(c,channel):new Notification.Builder(c);manager.notify(5810,b.setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle(title).setContentText(body).setAutoCancel(true).build());}
}
