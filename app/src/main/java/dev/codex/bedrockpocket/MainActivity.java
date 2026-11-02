package dev.codex.bedrockpocket;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.os.Build;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Process;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final int MAX_UPDATE_PAGE_BYTES=8*1024*1024;
    private int bg=Color.rgb(17,18,17), panel=Color.rgb(27,29,28), panelVariant=Color.rgb(37,40,38), fg=Color.rgb(244,246,244), muted=Color.rgb(173,179,175), accent=Color.rgb(50,207,117), onAccent=Color.rgb(7,24,14), accentContainer=Color.rgb(38,54,43), blue=Color.rgb(143,231,176), outline=Color.rgb(53,58,55);
    private TextView status, statusDot, hostInfo, logs, cpuValue, ramValue, tpsValue, filePath;
    private ScrollView logsScroll;
    private EditText logSearch;
    private Spinner logFilter;
    private boolean logsHasRendered;
    private ChartView cpuChartView, ramChartView, tpsChartView, playersChartView;
    private Button powerButton, installButton;
    private Button setupButton;
    private LinearLayout setupCard;
    private TextView setupTitle, setupDetail;
    private EditText commandInput;
    private EditText dashboardPasswordInput;
    private TextView dashboardInfo;
    private TextView deviceIpValue, playitIpValue;
    private Button copyDeviceIpButton, copyPlayitIpButton;
    private LinearLayout root, content, nav;
    private final ArrayList<LinearLayout> pages=new ArrayList<>();
    private final ArrayList<View> navItems=new ArrayList<>();
    private final ArrayDeque<Float> cpuSamples=new ArrayDeque<>();
    private final ArrayDeque<Integer> ramSamples=new ArrayDeque<>();
    private final ArrayDeque<Float> tpsSamples=new ArrayDeque<>();
    private final ArrayDeque<Integer> playerSamples=new ArrayDeque<>();
    private File serverDir, logFile, bdsFilesDir, currentFolder;
    private String serverState="STOPPED";
    private String lastServerMessage="";
    private long lastCpuTicks=0, lastSampleTime=0;
    private int currentPage=0;
    private final Map<String,EditText> settingFields=new LinkedHashMap<>();
    private final Map<String,Spinner> settingSpinners=new LinkedHashMap<>();
    private final Map<String,CheckBox> settingToggles=new LinkedHashMap<>();
    private final Map<String,String> settingDefaults=new LinkedHashMap<>();
    private static final int PICK_ADDON=4101, CREATE_BACKUP=4102, PICK_BACKUP=4103, PICK_WORLD_IMPORT=4104, CREATE_WORLD_EXPORT=4105, CREATE_LOG_EXPORT=4106;
    private LinearLayout addonList, backupList, playerList, worldList, trashList;
    private Button worldCreateButton, worldImportButton;
    private boolean ioBusy;
    private String pendingWorldExport;
    private File backupDir;
    private CheckBox eula, tpsToggle;
    private TextView playitStatus;
    private TextView playitAddress, playitTrafficIn, playitTrafficOut, playitConnections, playitPlayers;
    private TextView performanceTpsValue, performancePlayersValue, deviceStatusValue, playitTrafficValue;
    private TextView cpuTempValue, batteryTempValue, uptimeValue, playerSummaryValue, diskSpaceValue, lastEventValue, activeWorldValue, systemRamValue;
    private Button playitStartButton, playitStopButton, playitClaimButton;
    private Button profileButton;
    private EditText ramBudgetInput;
    private CheckBox scheduledBackupToggle;
    private EditText backupIntervalInput, backupRetentionInput;

    private final BroadcastReceiver serverEvents=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            String s=i.getStringExtra(ServerService.EXTRA_STATE), m=i.getStringExtra(ServerService.EXTRA_MESSAGE);
            if(m!=null && m.startsWith("PLAYIT_STATE:")){refreshPlayitState();refreshLogs();return;}
            if(m!=null && m.startsWith("LOG:")){refreshLogs();if(currentPage==7)refreshPlayers();return;}
            if(s!=null){recordServerEvent(s,m);serverState=s;lastServerMessage=m==null?"":m;renderStatus(m);sampleStats();if(currentPage==11)refreshWorlds();}
            refreshLogs();
        }
    };

    @Override public void onCreate(Bundle state){super.onCreate(state);
        applyDynamicColors();
        getWindow().setStatusBarColor(bg);getWindow().setNavigationBarColor(bg);
        if(Build.VERSION.SDK_INT>=26)getWindow().getDecorView().setSystemUiVisibility(0);
        serverDir=new File(getFilesDir(),"bds"); logFile=new File(getFilesDir(),"app.log");try{ServerProfileManager.ensure(this);}catch(Exception e){android.util.Log.e("BedrockPocket","profile migration",e);}bdsFilesDir=ServerProfileManager.activeServerDir(this); currentFolder=bdsFilesDir; backupDir=new File(getFilesDir(),"backups");
        buildShell(); showPage(0); refreshLogs(); loadSettings(); refreshSetupGuide(); sampleStats();
        root.postDelayed(statsLoop,2000);
    }
    private final Runnable statsLoop=new Runnable(){@Override public void run(){sampleStats();refreshPlayitState();if(currentPage==12)refreshWebDashboard();root.postDelayed(this,2000);}};
    @Override protected void onStart(){super.onStart();IntentFilter f=new IntentFilter(ServerService.ACTION_STATE);String permission=getPackageName()+".permission.INTERNAL_STATE";if(Build.VERSION.SDK_INT>=33)registerReceiver(serverEvents,f,permission,null,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(serverEvents,f,permission,null);startService(new Intent(this,ServerService.class).setAction(ServerService.ACTION_QUERY));}
    @Override protected void onStop(){try{unregisterReceiver(serverEvents);}catch(Exception ignored){}super.onStop();}

    private void buildShell(){
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(bg);
        FrameLayout frame=new FrameLayout(this);root.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
        for(int i=0;i<13;i++){ScrollView sc=new ScrollView(this);sc.setFillViewport(true);LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(16),dp(18),dp(16),dp(24));sc.addView(page);frame.addView(sc,new FrameLayout.LayoutParams(-1,-1));pages.add(page);}
        nav=new LinearLayout(this);nav.setOrientation(LinearLayout.HORIZONTAL);nav.setGravity(Gravity.CENTER_VERTICAL);nav.setPadding(dp(7),dp(5),dp(7),dp(5));nav.setBackground(shape(panel,34,outline));LinearLayout.LayoutParams navParams=new LinearLayout.LayoutParams(-1,dp(68));navParams.setMargins(dp(12),0,dp(12),dp(5));root.addView(nav,navParams);
        String[] labels={"หน้าแรก","คอนโซล","สร้าง","ไฟล์","ตั้งค่า"};int[] pageIds={0,1,-1,3,2};
        for(int i=0;i<labels.length;i++){final int index=i;View item;if(i==2){TextView create=label("＋\nสร้าง",14,onAccent,true);create.setGravity(Gravity.CENTER);create.setLineSpacing(0,0.9f);create.setBackground(shape(accent,24,0));create.setContentDescription("สร้างโลกหรือโปรไฟล์เซิร์ฟเวอร์");item=create;}else item=new NavItemView(this,i,labels[i]);item.setOnClickListener(v->{if(index==2)showCreateMenu();else showPage(pageIds[index]);});LinearLayout.LayoutParams itemParams=new LinearLayout.LayoutParams(0,-1,i==2?1.15f:1f);nav.addView(item,itemParams);navItems.add(item);}
        setContentView(root); buildHome(pages.get(0));buildConsole(pages.get(1));buildSettings(pages.get(2));buildFiles(pages.get(3));buildMore(pages.get(4));buildAddons(pages.get(5));buildBackups(pages.get(6));buildPlayers(pages.get(7));buildTps(pages.get(8));buildPlayit(pages.get(9));buildAbout(pages.get(10));buildWorlds(pages.get(11));buildWebDashboard(pages.get(12));applyMaterialStyles(root);stylePrimaryButton(powerButton);
    }
    private void showPage(int index){currentPage=index;for(int i=0;i<pages.size();i++){View wrapper=(View)pages.get(i).getParent();wrapper.setVisibility(i==index?View.VISIBLE:View.GONE);if(i==index)wrapper.scrollTo(0,0);}int selected=index==0?0:index==1?1:index==3?3:index==2?4:-1;for(int i=0;i<navItems.size();i++){View item=navItems.get(i);boolean active=i==selected;if(i==2){item.setBackground(shape(accent,24,0));}else if(item instanceof NavItemView){((NavItemView)item).setSelected(active);item.setBackground(active?shape(accentContainer,20,0):null);}}if(index==1){if(commandInput!=null)commandInput.clearFocus();refreshLogs();if(logsScroll!=null)logsScroll.post(()->{logsScroll.scrollTo(0,logs.getBottom());((ScrollView)pages.get(1).getParent()).scrollTo(0,0);});}if(index==3)refreshFileList();if(index==5)refreshAddons();if(index==6)refreshBackups();if(index==7)refreshPlayers();if(index==8)refreshTpsPage();if(index==11)refreshWorlds();if(index==12)refreshWebDashboard();}

    private void buildHome(LinearLayout p){
        LinearLayout brand=new LinearLayout(this);brand.setGravity(Gravity.CENTER_VERTICAL);TextView title=label("BedrockPocket",16,accent,true);title.setSingleLine(true);brand.addView(title,new LinearLayout.LayoutParams(0,-2,1));Button tools=new Button(this);tools.setText("จัดการ");tools.setOnClickListener(v->showPage(4));brand.addView(tools);profileButton=new Button(this);profileButton.setText(ServerProfileManager.name(this,ServerProfileManager.activeId(this)));profileButton.setTextSize(12);profileButton.setOnClickListener(v->manageServerProfiles());brand.addView(profileButton);p.addView(brand,top(6));
        setupCard=card(p);setupCard.setPadding(dp(16),dp(14),dp(16),dp(12));setupTitle=label("เริ่มต้นใช้งาน",18,fg,true);setupCard.addView(setupTitle);setupDetail=label("ตรวจความพร้อมและตั้งค่าเซิร์ฟเวอร์ทีละขั้น",13,muted,false);setupCard.addView(setupDetail,top(4));setupButton=new Button(this);setupButton.setText("เริ่มตั้งค่าเซิร์ฟเวอร์");setupButton.setOnClickListener(v->showSetupWizard());setupCard.addView(setupButton,top(8));
        p.addView(label("เซิร์ฟเวอร์ของฉัน",14,muted,false),top(8));
        LinearLayout card=card(p);card.setPadding(dp(18),dp(18),dp(18),dp(18));LinearLayout stateRow=new LinearLayout(this);stateRow.setGravity(Gravity.CENTER_VERTICAL);statusDot=label("●",16,muted,true);stateRow.addView(statusDot);status=label("กำลังตรวจสอบ…",13,fg,true);LinearLayout.LayoutParams stateTextParams=new LinearLayout.LayoutParams(-2,-2);stateTextParams.leftMargin=dp(9);stateRow.addView(status,stateTextParams);card.addView(stateRow);
        card.addView(label("Bedrock Dedicated Server",20,fg,true),top(15));card.addView(label(ServerInstaller.SERVER_VERSION+"  ·  โฮสต์บนอุปกรณ์นี้",13,muted,false),top(5));hostInfo=label("ที่อยู่จะแสดงเมื่อเซิร์ฟเวอร์ออนไลน์",13,muted,false);card.addView(hostInfo,top(9));
        powerButton=new Button(this);powerButton.setText("เริ่มเซิร์ฟเวอร์");powerButton.setOnClickListener(v->{if("RUNNING".equals(serverState)||"STARTING".equals(serverState))stopServer();else startServer();});card.addView(powerButton,top(15));
        LinearLayout quick=new LinearLayout(this);quick.setGravity(Gravity.CENTER);quick.setOrientation(LinearLayout.HORIZONTAL);card.addView(quick,top(10));addHomeShortcut(quick,"ผู้เล่น","ออนไลน์และสิทธิ์",()->showPage(7));addHomeShortcut(quick,"โลก","สลับ/ส่งออก",()->showPage(11));addHomeShortcut(quick,"ไฟล์","ไฟล์ BDS",()->showPage(3));
        LinearLayout quick2=new LinearLayout(this);quick2.setGravity(Gravity.CENTER);quick2.setOrientation(LinearLayout.HORIZONTAL);card.addView(quick2,top(7));addHomeShortcut(quick2,"Console","คำสั่งและ log",()->showPage(1));addHomeShortcut(quick2,"สำรอง","ZIP / Cloud",()->showPage(6));addHomeShortcut(quick2,"Playit","tunnel",()->showPage(9));
        Button allTools=new Button(this);allTools.setText("เครื่องมือและการจัดการทั้งหมด");allTools.setOnClickListener(v->showPage(4));card.addView(allTools,top(9));
        LinearLayout summary=card(p);summary.addView(label("สถานะเครื่องและเซิร์ฟเวอร์",14,muted,true));activeWorldValue=label("โลก  —",15,fg,true);summary.addView(activeWorldValue,top(7));uptimeValue=label("Uptime  —",15,fg,true);summary.addView(uptimeValue,top(5));playerSummaryValue=label("ผู้เล่น  —",15,fg,true);summary.addView(playerSummaryValue,top(5));diskSpaceValue=label("พื้นที่ว่าง  —",15,fg,true);summary.addView(diskSpaceValue,top(5));systemRamValue=label("RAM เครื่อง  —",15,fg,true);summary.addView(systemRamValue,top(5));
        LinearLayout deviceIpRow=new LinearLayout(this);deviceIpRow.setGravity(Gravity.CENTER_VERTICAL);deviceIpValue=label("IP เครื่อง  —",14,fg,true);deviceIpRow.addView(deviceIpValue,new LinearLayout.LayoutParams(0,-2,1));copyDeviceIpButton=new Button(this);copyDeviceIpButton.setText("คัดลอก");copyDeviceIpButton.setOnClickListener(v->copyStatusIp("IP เครื่อง",localAddress()));deviceIpRow.addView(copyDeviceIpButton,new LinearLayout.LayoutParams(-2,dp(44)));summary.addView(deviceIpRow,top(6));
        LinearLayout playitIpRow=new LinearLayout(this);playitIpRow.setGravity(Gravity.CENTER_VERTICAL);playitIpValue=label("Playit IP  —",14,fg,true);playitIpRow.addView(playitIpValue,new LinearLayout.LayoutParams(0,-2,1));copyPlayitIpButton=new Button(this);copyPlayitIpButton.setText("คัดลอก");copyPlayitIpButton.setEnabled(false);copyPlayitIpButton.setOnClickListener(v->copyStatusIp("Playit IP",PlayitService.currentTunnelAddress()));playitIpRow.addView(copyPlayitIpButton,new LinearLayout.LayoutParams(-2,dp(44)));summary.addView(playitIpRow,top(4));
        lastEventValue=label("เหตุการณ์ล่าสุด  —",13,muted,false);summary.addView(lastEventValue,top(8));
        LinearLayout ticks=card(p);ticks.addView(label("ประสิทธิภาพเกม",13,muted,true));tpsValue=label("TPS  — · เปิดตัววัดในเครื่องมือ",18,fg,true);ticks.addView(tpsValue,top(6));
        installButton=new Button(this);installButton.setText("ติดตั้ง / ซ่อม BDS และ Linux runtime");installButton.setOnClickListener(v->installServer());p.addView(installButton,top(8));
        p.addView(label("ประสิทธิภาพ",18,fg,true),top(22));
        LinearLayout cpu=card(p);cpuValue=label("CPU  —",14,fg,true);cpu.addView(cpuValue);cpuChartView=new ChartView(this,cpuSamples,blue,"CPU");cpu.addView(cpuChartView,new LinearLayout.LayoutParams(-1,dp(115)));
        LinearLayout ram=card(p);ramValue=label("RAM  —",14,fg,true);ram.addView(ramValue);ramChartView=new ChartView(this,ramSamples,accent,"RAM");ram.addView(ramChartView,new LinearLayout.LayoutParams(-1,dp(115)));
        LinearLayout tps=card(p);performanceTpsValue=label("TPS  — · เปิดตัววัดจากเครื่องมือ",14,fg,true);tps.addView(performanceTpsValue);tpsChartView=new ChartView(this,tpsSamples,blue,"TPS");tps.addView(tpsChartView,new LinearLayout.LayoutParams(-1,dp(115)));
        LinearLayout online=card(p);performancePlayersValue=label("ผู้เล่นออนไลน์  —",14,fg,true);online.addView(performancePlayersValue);playersChartView=new ChartView(this,playerSamples,accent,"PLAYERS");online.addView(playersChartView,new LinearLayout.LayoutParams(-1,dp(115)));
        LinearLayout device=card(p);device.addView(label("อุณหภูมิอุปกรณ์",13,muted,true));cpuTempValue=label("อุณหภูมิ CPU  —",15,fg,true);device.addView(cpuTempValue,top(7));batteryTempValue=label("อุณหภูมิแบตเตอรี่  —",15,fg,true);device.addView(batteryTempValue,top(5));deviceStatusValue=label("แบตเตอรี่  —",13,muted,false);device.addView(deviceStatusValue,top(5));
        LinearLayout network=card(p);network.addView(label("ข้อมูลผ่าน Playit Agent",13,muted,true));playitTrafficValue=label("รับ — MB  ·  ส่ง — MB",16,fg,true);network.addView(playitTrafficValue,top(7));
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);p.addView(actions,top(12));Button update=new Button(this);update.setText("เช็คอัปเดต");update.setOnClickListener(v->checkUpdates());actions.addView(update,new LinearLayout.LayoutParams(0,dp(48),1));
        p.addView(label("กราฟเก็บตัวอย่างในหน่วยความจำระหว่างเปิดแอป · TPS ต้องเปิดตัววัด · ข้อมูลเครือข่ายนับจาก Playit Agent เท่านั้น",12,muted,false),top(6));
    }
    private void addHomeShortcut(LinearLayout row,String title,String hint,Runnable action){LinearLayout button=new LinearLayout(this);button.setOrientation(LinearLayout.VERTICAL);button.setGravity(Gravity.CENTER);button.setPadding(dp(5),dp(6),dp(5),dp(6));button.setBackground(shape(panelVariant,17,outline));button.addView(label(title,13,fg,true));TextView detail=label(hint,10,muted,false);detail.setGravity(Gravity.CENTER);button.addView(detail,top(3));button.setOnClickListener(v->action.run());button.setContentDescription(title+" · "+hint);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(62),1);lp.setMargins(dp(4),0,dp(4),0);row.addView(button,lp);}
    private void refreshSetupGuide(){
        if(setupCard==null)return;
        boolean installed=ServerInstaller.isInstalled(serverDir),accepted=getSharedPreferences("server",MODE_PRIVATE).getBoolean("eula_accepted",false),supported=supportsArm64();
        boolean busy="INSTALLING".equals(serverState)||"STARTING".equals(serverState)||"STOPPING".equals(serverState);
        boolean needsGuide=!installed||!accepted||"ERROR".equals(serverState)||busy;
        setupCard.setVisibility(needsGuide?View.VISIBLE:View.GONE);
        if(!needsGuide)return;
        if("INSTALLING".equals(serverState)){setupTitle.setText("กำลังติดตั้งเซิร์ฟเวอร์");setupDetail.setText(lastServerMessage.isEmpty()?"เตรียม Linux และ Mojang BDS…":lastServerMessage);setupButton.setText("กำลังติดตั้ง…");setupButton.setEnabled(false);return;}
        setupButton.setEnabled(true);
        if(!supported){setupTitle.setText("อุปกรณ์นี้ยังไม่รองรับ");setupDetail.setText("Bedrock Dedicated Server ทางนี้ต้องใช้ Android ARM64 64-bit");setupButton.setText("ดูรายละเอียด");}
        else if(!installed){setupTitle.setText("ขั้นตอน 1 จาก 3 · ติดตั้ง BDS");boolean staged=ServerInstaller.hasStagedBds(new File(getFilesDir(),"bds-installing"));setupDetail.setText(staged?"ดาวน์โหลด BDS แล้ว · กดต่อเพื่อติดตั้ง Linux runtime":"ตรวจพื้นที่ อินเทอร์เน็ต และ EULA ก่อนดาวน์โหลด BDS กับ Linux runtime");setupButton.setText("เริ่มตั้งค่าเซิร์ฟเวอร์");}
        else if(!accepted){setupTitle.setText("ขั้นตอน 2 จาก 3 · ยอมรับ EULA");setupDetail.setText("อ่านเงื่อนไข Mojang แล้วกดยอมรับก่อนเริ่ม BDS");setupButton.setText("อ่าน EULA และยอมรับ");}
        else if("STARTING".equals(serverState)){setupTitle.setText("ขั้นตอน 3 จาก 3 · กำลังเริ่ม BDS");setupDetail.setText(lastServerMessage);setupButton.setText("กำลังเริ่ม…");setupButton.setEnabled(false);}
        else if("STOPPING".equals(serverState)){setupTitle.setText("กำลังหยุดเซิร์ฟเวอร์");setupDetail.setText(lastServerMessage);setupButton.setText("กำลังหยุด…");setupButton.setEnabled(false);}
        else{setupTitle.setText("เซิร์ฟเวอร์เริ่มไม่สำเร็จ");setupDetail.setText(lastServerMessage.isEmpty()?"ดูรายละเอียดข้อผิดพลาดใน Console แล้วลองใหม่":lastServerMessage);setupButton.setText("ลองตั้งค่า / เริ่มใหม่");}
        setupButton.setOnClickListener(v->showSetupWizard());
    }
    private void showSetupWizard(){
        boolean supported=supportsArm64(),installed=ServerInstaller.isInstalled(serverDir);
        android.app.ActivityManager.MemoryInfo memory=new android.app.ActivityManager.MemoryInfo();((android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(memory);
        long free=serverDir.getParentFile().getUsableSpace();boolean connected=false;try{android.net.ConnectivityManager cm=(android.net.ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);android.net.NetworkInfo active=cm==null?null:cm.getActiveNetworkInfo();connected=active!=null&&active.isConnected();}catch(Exception ignored){}final boolean network=connected;
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(4),dp(4),dp(4),dp(4));
        body.addView(label("1  ตรวจอุปกรณ์  ·  "+(supported?"ARM64 พร้อม":"ต้องใช้ Android ARM64 64-bit"),14,supported?accent:muted,true));
        body.addView(label("2  พื้นที่ว่าง  ·  "+formatStorage(free)+(free>=2_500_000_000L?" พร้อม":" · ต้องมีอย่างน้อย 2.5 GB"),13,muted,false),top(7));
        body.addView(label("3  RAM ว่าง  ·  "+formatStorage(memory.availMem)+"  ·  เครือข่าย "+(network?"พร้อม":"ยังไม่เชื่อมต่อ"),13,muted,false),top(5));
        body.addView(label("4  Mojang EULA  ·  "+(getSharedPreferences("server",MODE_PRIVATE).getBoolean("eula_accepted",false)?"ยอมรับแล้ว":"รอการยอมรับ"),13,muted,false),top(5));
        CheckBox accept=check(body,"ฉันอ่านและยอมรับ Minecraft EULA");accept.setChecked(getSharedPreferences("server",MODE_PRIVATE).getBoolean("eula_accepted",false));
        TextView link=label("อ่าน Minecraft EULA",13,blue,true);link.setOnClickListener(v->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.minecraft.net/eula"))));body.addView(link,top(2));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("ตัวช่วยตั้งค่าเซิร์ฟเวอร์").setView(body).setNegativeButton("ยกเลิก",null).setPositiveButton(installed?"เริ่มเซิร์ฟเวอร์":"ติดตั้ง BDS",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{if(!supported){message("อุปกรณ์นี้ต้องใช้ Android ARM64 64-bit");return;}if(!accept.isChecked()){message("อ่านและยอมรับ Minecraft EULA ก่อนดำเนินการ");return;}if(!installed&&free<2_500_000_000L){message("พื้นที่ว่างไม่ถึง 2.5 GB");return;}if(!installed&&!network){message("เชื่อมต่ออินเทอร์เน็ตก่อนติดตั้ง");return;}getSharedPreferences("server",MODE_PRIVATE).edit().putBoolean("eula_accepted",true).apply();eula.setChecked(true);dialog.dismiss();refreshSetupGuide();if(installed)startServer();else installServer();}));dialog.show();
    }
    private void showCreateMenu(){
        ArrayList<String> actions=new ArrayList<>();if(!ServerInstaller.isInstalled(serverDir))actions.add("ตั้งค่าและติดตั้งเซิร์ฟเวอร์");actions.add("สร้างโลกใหม่");if(ServerInstaller.isInstalled(serverDir))actions.add("สร้างโปรไฟล์เซิร์ฟเวอร์");actions.add("นำเข้าโลก .mcworld");
        new AlertDialog.Builder(this).setTitle("สร้าง").setItems(actions.toArray(new String[0]),(dialog,which)->{String action=actions.get(which);if(action.startsWith("ตั้งค่า")){showSetupWizard();return;}if(action.equals("สร้างโลกใหม่")){createWorldFromAction();return;}if(action.equals("สร้างโปรไฟล์เซิร์ฟเวอร์")){createServerProfile();return;}if(!ServerInstaller.isInstalled(serverDir)){showSetupWizard();return;}if(worldOpsAllowed()){showPage(11);pickWorldImport();}}).setNegativeButton("ปิด",null).show();
    }
    private void createWorldFromAction(){if(!ServerInstaller.isInstalled(serverDir)){showSetupWizard();return;}if(!worldOpsAllowed())return;showPage(11);promptWorldName("สร้างโลกใหม่","ชื่อโลกใหม่","World",name->runIo("สร้างโลก",()->{WorldManager.createWorld(bdsFilesDir,name);return "ตั้ง "+name+" เป็นโลกที่จะสร้างเมื่อเริ่ม BDS ครั้งถัดไป";},this::refreshWorlds));}
    private void manageServerProfiles(){
        if(isServerBusy()){message("หยุด BDS ก่อนสลับหรือจัดการโปรไฟล์");return;}
        try{List<ServerProfileManager.Profile> profiles=ServerProfileManager.list(this);String[] names=new String[profiles.size()];for(int i=0;i<profiles.size();i++)names[i]=profiles.get(i).name+(profiles.get(i).id.equals(ServerProfileManager.activeId(this))?"  ·  ใช้งานอยู่":"");
            new AlertDialog.Builder(this).setTitle("โปรไฟล์เซิร์ฟเวอร์ · เปิดได้ทีละตัว").setItems(names,(d,w)->showProfileActions(profiles.get(w))).setNeutralButton("สร้างโปรไฟล์",(d,w)->createServerProfile()).setNegativeButton("ปิด",null).show();
        }catch(Exception e){message("อ่านโปรไฟล์ไม่ได้: "+e.getMessage());}
    }
    private void showProfileActions(ServerProfileManager.Profile profile){
        String active=ServerProfileManager.activeId(this);ArrayList<String> actions=new ArrayList<>();if(!profile.id.equals(active))actions.add("สลับมาโปรไฟล์นี้");actions.add("เปลี่ยนชื่อ");if(!"default".equals(profile.id))actions.add("ลบโปรไฟล์");
        new AlertDialog.Builder(this).setTitle(profile.name).setItems(actions.toArray(new String[0]),(d,w)->{String action=actions.get(w);if(action.startsWith("สลับ")){try{ServerProfileManager.setActive(this,profile.id);bdsFilesDir=ServerProfileManager.activeServerDir(this);currentFolder=bdsFilesDir;loadSettings();if(profileButton!=null)profileButton.setText(profile.name);refreshFileList();refreshAddons();refreshBackups();refreshWorlds();refreshPlayers();startService(new Intent(this,ServerService.class).setAction(ServerService.ACTION_QUERY));sampleStats();}catch(Exception e){message("สลับโปรไฟล์ไม่ได้: "+e.getMessage());}}else if(action.equals("เปลี่ยนชื่อ")){EditText input=new EditText(this);input.setSingleLine(true);input.setText(profile.name);input.setSelection(input.length());new AlertDialog.Builder(this).setTitle("เปลี่ยนชื่อโปรไฟล์").setView(input).setNegativeButton("ยกเลิก",null).setPositiveButton("บันทึก",(x,y)->{try{ServerProfileManager.rename(this,profile.id,input.getText().toString());if(profileButton!=null&&profile.id.equals(active))profileButton.setText(input.getText());message("เปลี่ยนชื่อโปรไฟล์แล้ว");}catch(Exception e){message("เปลี่ยนชื่อไม่ได้: "+e.getMessage());}}).show();}else new AlertDialog.Builder(this).setTitle("ลบโปรไฟล์").setMessage("ลบ "+profile.name+" และไฟล์โลก/แอดออนทั้งหมดในโปรไฟล์นี้ถาวรหรือไม่? สำรองก่อนลบหากต้องการเก็บข้อมูล").setNegativeButton("ยกเลิก",null).setPositiveButton("ลบ",(x,y)->{try{ServerProfileManager.delete(this,profile.id);message("ลบโปรไฟล์แล้ว");}catch(Exception e){message("ลบโปรไฟล์ไม่ได้: "+e.getMessage());}}).show();}).show();
    }
    private void createServerProfile(){if(isServerBusy()){message("หยุด BDS ก่อนสร้างโปรไฟล์");return;}if(!ServerInstaller.isInstalled(serverDir)){message("ติดตั้ง runtime และ BDS ก่อนสร้างโปรไฟล์เพิ่ม");return;}EditText input=new EditText(this);input.setSingleLine(true);input.setHint("เช่น Creative / Survival");new AlertDialog.Builder(this).setTitle("สร้างโปรไฟล์จากไฟล์ตั้งค่าหลัก").setMessage("โลกจะเริ่มว่างและถูกสร้างเมื่อเปิดโปรไฟล์ครั้งแรก แอดออนและค่าตั้งค่าจะคัดลอกจากโปรไฟล์หลัก").setView(input).setNegativeButton("ยกเลิก",null).setPositiveButton("สร้าง",(d,w)->runIo("สร้างโปรไฟล์",()->{ServerProfileManager.create(this,input.getText().toString());return "สร้างโปรไฟล์แล้ว · เลือกชื่อโปรไฟล์ด้านบนเพื่อสลับ";},()->{})).show();}
    private void buildConsole(LinearLayout p){p.addView(label("Console",25,fg,true));p.addView(label("บันทึกจากเซิร์ฟเวอร์และคำสั่ง",13,muted,false),top(3));
        LinearLayout bar=new LinearLayout(this);bar.setOrientation(LinearLayout.HORIZONTAL);p.addView(bar,top(12));Button refresh=new Button(this);refresh.setText("รีเฟรช");refresh.setOnClickListener(v->refreshLogs());bar.addView(refresh,new LinearLayout.LayoutParams(0,dp(46),1));Button clear=new Button(this);clear.setText("เคลียร์ log");clear.setOnClickListener(v->{ServerInstaller.clearLog(logFile);refreshLogs();});bar.addView(clear,new LinearLayout.LayoutParams(0,dp(46),1));Button export=new Button(this);export.setText("ส่งออก");export.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("text/plain");i.putExtra(Intent.EXTRA_TITLE,"BedrockPocket-log-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",java.util.Locale.US).format(new java.util.Date())+".txt");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,CREATE_LOG_EXPORT);});bar.addView(export,new LinearLayout.LayoutParams(0,dp(46),1));
        logSearch=new EditText(this);logSearch.setSingleLine(true);logSearch.setTextColor(fg);logSearch.setHintTextColor(muted);logSearch.setHint("ค้นหาใน log");p.addView(logSearch,top(6));logFilter=new Spinner(this);logFilter.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"ทุกประเภท","ข้อผิดพลาด","คำเตือน","คำสั่ง","ข้อมูล"}));p.addView(logFilter,new LinearLayout.LayoutParams(-1,dp(44)));logSearch.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){refreshLogs();}public void afterTextChanged(android.text.Editable e){}});logFilter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> a,View v,int pos,long id){refreshLogs();}public void onNothingSelected(android.widget.AdapterView<?> a){}});
        FrameLayout logFrame=new FrameLayout(this);logFrame.setBackground(shape(panel,16,outline));
        logsScroll=new ScrollView(this);logsScroll.setFillViewport(true);logsScroll.setVerticalScrollBarEnabled(true);logsScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        logs=label("ยังไม่มี log",12,Color.rgb(203,213,225),false);logs.setTypeface(Typeface.MONOSPACE);logs.setTextIsSelectable(true);logs.setPadding(dp(12),dp(12),dp(12),dp(12));logsScroll.addView(logs,new ScrollView.LayoutParams(-1,-2));logFrame.addView(logsScroll,new FrameLayout.LayoutParams(-1,-1));
        p.addView(logFrame,top(8));LinearLayout.LayoutParams logFrameParams=(LinearLayout.LayoutParams)logFrame.getLayoutParams();logFrameParams.height=dp(420);logFrame.setLayoutParams(logFrameParams);
        LinearLayout send=new LinearLayout(this);send.setGravity(Gravity.CENTER_VERTICAL);send.setOrientation(LinearLayout.HORIZONTAL);p.addView(send,top(10));commandInput=new EditText(this);commandInput.setSingleLine(true);commandInput.setTextColor(fg);commandInput.setHintTextColor(muted);commandInput.setHint("พิมพ์คำสั่ง เช่น list หรือ say สวัสดี");commandInput.setInputType(InputType.TYPE_CLASS_TEXT);send.addView(commandInput,new LinearLayout.LayoutParams(0,dp(54),1));Button go=new Button(this);go.setText("ส่ง");go.setOnClickListener(v->sendCommand());send.addView(go,new LinearLayout.LayoutParams(dp(76),dp(50)));
        p.addView(label("คำสั่งใช้ได้เมื่อ BDS ทำงาน",12,muted,false),top(4));
    }
        private void buildSettings(LinearLayout p){
        p.addView(label("ตั้งค่าเซิร์ฟเวอร์",25,fg,true));
        p.addView(label("ปรับละเอียดได้เป็นหมวด · ค่าใหม่มีผลหลังหยุดแล้วเริ่ม BDS อีกครั้ง",13,muted,false),top(4));
        LinearLayout general=settingsSection(p,"ทั่วไปและโลก",true);
        addTextSetting(general,"server-name","ชื่อเซิร์ฟเวอร์","Dedicated Server",InputType.TYPE_CLASS_TEXT,"ห้ามใส่เครื่องหมาย ;");
        addChoiceSetting(general,"gamemode","โหมดเกม","survival",new String[]{"survival","creative","adventure"},new String[]{"เอาชีวิตรอด","สร้างสรรค์","ผจญภัย"});
        addChoiceSetting(general,"difficulty","ระดับความยาก","easy",new String[]{"peaceful","easy","normal","hard"},new String[]{"สงบ","ง่าย","ปกติ","ยาก"});
        addToggleSetting(general,"force-gamemode","บังคับโหมดเกมตามค่าด้านบน",false);
        addTextSetting(general,"level-name","ชื่อโลก / โฟลเดอร์โลก","Bedrock level",InputType.TYPE_CLASS_TEXT,"ใช้ชื่อให้ตรงกับโฟลเดอร์ใน worlds");
        addTextSetting(general,"level-seed","Seed โลก","",InputType.TYPE_CLASS_TEXT,"มีผลเฉพาะตอนสร้างโลกใหม่ · โลกเดิมจะไม่เปลี่ยน seed");

        LinearLayout players=settingsSection(p,"ผู้เล่นและสิทธิ์",true);
        addTextSetting(players,"max-players","จำนวนผู้เล่นสูงสุด","10",InputType.TYPE_CLASS_NUMBER,"จำนวนมากขึ้นใช้ทรัพยากรมากขึ้น");
        addToggleSetting(players,"online-mode","ตรวจสอบบัญชี Xbox Live",true);
        addToggleSetting(players,"allow-list","จำกัดผู้เล่นด้วย allowlist",false);
        addChoiceSetting(players,"default-player-permission-level","สิทธิ์ผู้เล่นใหม่","member",new String[]{"visitor","member","operator"},new String[]{"ผู้เยี่ยมชม","สมาชิก","ผู้ดูแลระบบ (Operator)"});
        addTextSetting(players,"player-idle-timeout","เตะผู้เล่นเมื่อไม่ขยับ (นาที)","30",InputType.TYPE_CLASS_NUMBER,"ใส่ 0 เพื่อไม่เตะผู้เล่นที่อยู่นิ่ง");
        addToggleSetting(players,"allow-cheats","อนุญาตสูตรโกง",false);

        LinearLayout performance=settingsSection(p,"โลกและประสิทธิภาพ",true);
        addTextSetting(performance,"view-distance","ระยะมองเห็น (chunks)","12",InputType.TYPE_CLASS_NUMBER,"ค่าต่ำช่วยลดภาระเครื่อง · ขั้นต่ำ 5");
        addTextSetting(performance,"tick-distance","ระยะอัปเดตโลก (chunks)","4",InputType.TYPE_CLASS_NUMBER,"ช่วงที่รองรับ 4–12 · ค่าต่ำช่วยลด CPU");
        addTextSetting(performance,"max-threads","จำนวน threads สูงสุด","4",InputType.TYPE_CLASS_NUMBER,"ค่าต่ำช่วยจำกัดการใช้ CPU บนมือถือ");
        addToggleSetting(performance,"client-side-chunk-generation-enabled","ให้เครื่องผู้เล่นช่วยสร้างภาพ chunks",true);
        addTextSetting(performance,"server-build-radius-ratio","สัดส่วนสร้าง chunks ฝั่งเซิร์ฟเวอร์","Disabled",InputType.TYPE_CLASS_TEXT,"Disabled หรือเลข 0.0–1.0");
        p.addView(label("งบประมาณ RAM แนะนำ (MB)",13,muted,true),top(10));ramBudgetInput=new EditText(this);ramBudgetInput.setSingleLine(true);ramBudgetInput.setInputType(InputType.TYPE_CLASS_NUMBER);ramBudgetInput.setTextColor(fg);ramBudgetInput.setHintTextColor(muted);ramBudgetInput.setHint("1024");p.addView(ramBudgetInput,new LinearLayout.LayoutParams(-1,dp(48)));p.addView(label("เป็นเกณฑ์เตือนก่อนเริ่มเท่านั้น · Android ยังเป็นผู้จัดสรร/ปิดโปรเซส ไม่มีการอ้างว่าเป็น RAM hard limit",11,muted,false),top(2));

        LinearLayout network=settingsSection(p,"เครือข่าย",false);
        addTextSetting(network,"server-port","พอร์ต IPv4","19132",InputType.TYPE_CLASS_NUMBER,"พอร์ต NetherNet signaling เริ่มต้น");
        addTextSetting(network,"server-portv6","พอร์ต IPv6","19133",InputType.TYPE_CLASS_NUMBER,"ค่า 1–65535");
        addToggleSetting(network,"enable-lan-visibility","ค้นพบเซิร์ฟเวอร์ใน LAN",true);
        addChoiceSetting(network,"transport","ชนิดการเชื่อมต่อ","nethernet",new String[]{"nethernet"},new String[]{"NetherNet (จำเป็นสำหรับ BDS รุ่นนี้)"});
        addTextSetting(network,"compression-threshold","ขนาดขั้นต่ำก่อนบีบอัด","1",InputType.TYPE_CLASS_NUMBER,"ช่วง 0–65535");
        addChoiceSetting(network,"compression-algorithm","อัลกอริทึมบีบอัด","zlib",new String[]{"zlib","snappy"},new String[]{"zlib","Snappy"});

        LinearLayout gameplay=settingsSection(p,"การเล่นและความปลอดภัย",false);
        addToggleSetting(gameplay,"texturepack-required","บังคับใช้ Resource Pack",false);
        addToggleSetting(gameplay,"disable-custom-skins","ปิดสกินที่ผู้เล่นปรับแต่ง",false);
        addToggleSetting(gameplay,"disable-player-interaction","ปิดการโต้ตอบระหว่างผู้เล่น",false);
        addChoiceSetting(gameplay,"chat-restriction","ข้อจำกัดแชต","None",new String[]{"None","Dropped","Disabled"},new String[]{"ไม่จำกัด","ซ่อนข้อความแชต","ปิดหน้าต่างแชตสำหรับผู้เล่นทั่วไป"});
        addToggleSetting(gameplay,"server-authoritative-movement-strict","ตรวจการเคลื่อนที่เข้มงวด",false);
        addToggleSetting(gameplay,"server-authoritative-dismount-strict","ตรวจตำแหน่งลงจากพาหนะเข้มงวด",false);
        addToggleSetting(gameplay,"server-authoritative-entity-interactions-strict","ตรวจการโต้ตอบ Entity เข้มงวด",false);
        addToggleSetting(gameplay,"server-authoritative-block-breaking","ตรวจการขุดบล็อกฝั่งเซิร์ฟเวอร์",false);
        addTextSetting(gameplay,"server-authoritative-block-breaking-range-scalar","ตัวคูณระยะขุดบล็อก","1.5",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL,"ต้องมากกว่า 1.0");
        addToggleSetting(gameplay,"content-log-file-enabled","บันทึก Content errors ลงไฟล์",false);

        LinearLayout scripting=settingsSection(p,"Script debugger (ขั้นสูง)",false);
        addToggleSetting(scripting,"allow-inbound-script-debugging","อนุญาต debugger เชื่อมเข้ามา",false);
        addToggleSetting(scripting,"allow-outbound-script-debugging","อนุญาตเซิร์ฟเวอร์เชื่อม debugger ออกไป",false);
        addChoiceSetting(scripting,"script-debugger-auto-attach","แนบ debugger อัตโนมัติ","disabled",new String[]{"disabled","connect","listen"},new String[]{"ปิด","เชื่อมไปยัง debugger","รอ debugger เชื่อมเข้า"});
        addTextSetting(scripting,"script-debugger-auto-attach-connect-address","ที่อยู่ debugger","localhost:19144",InputType.TYPE_CLASS_TEXT,"รูปแบบ hostname:port");
        addTextSetting(scripting,"force-inbound-debug-port","พอร์ต debugger ขาเข้า","19144",InputType.TYPE_CLASS_NUMBER,"เปิดใช้เมื่อเลือก listen");
        addTextSetting(scripting,"script-debugger-auto-attach-timeout","เวลารอ debugger (วินาที)","0",InputType.TYPE_CLASS_NUMBER,"0 คือไม่รอเพิ่ม");
        addTextSetting(scripting,"script-debugger-passcode","รหัสผ่าน debugger","",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD,"อย่าเปิด inbound debugger บนเครือข่ายสาธารณะ");

        eula=check(p,"ฉันอ่านและยอมรับ Minecraft EULA");
        eula.setChecked(getSharedPreferences("server",MODE_PRIVATE).getBoolean("eula_accepted",false));
        eula.setOnCheckedChangeListener((b,c)->getSharedPreferences("server",MODE_PRIVATE).edit().putBoolean("eula_accepted",c).apply());
        TextView eulaLink=label("เปิด Minecraft EULA",13,blue,true);eulaLink.setOnClickListener(v->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.minecraft.net/eula"))));p.addView(eulaLink,top(2));
        Button save=new Button(this);save.setText("บันทึกการตั้งค่าทั้งหมด");save.setOnClickListener(v->saveSettings());p.addView(save,top(14));
        Button reload=new Button(this);reload.setText("โหลดค่าจาก server.properties ใหม่");reload.setOnClickListener(v->{loadSettings();new AlertDialog.Builder(this).setMessage("โหลดค่าปัจจุบันจากไฟล์แล้ว").setPositiveButton("ตกลง",null).show();});p.addView(reload,top(6));
        Button update=new Button(this);update.setText("เช็คอัปเดต BDS");update.setOnClickListener(v->checkUpdates());p.addView(update,top(8));
    }private void buildFiles(LinearLayout p){p.addView(label("ตัวจัดการไฟล์",25,fg,true));p.addView(label("ดูและแก้ไฟล์ภายในโฟลเดอร์ BDS",13,muted,false),top(4));filePath=label("",12,blue,false);p.addView(filePath,top(12));Button up=new Button(this);up.setText("โฟลเดอร์ก่อนหน้า");up.setOnClickListener(v->{File parent=currentFolder.getParentFile();if(parent!=null&&parent.getPath().startsWith(bdsFilesDir.getPath())){currentFolder=parent;refreshFileList();}});p.addView(up,top(6));LinearLayout list= new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);list.setTag("file-list");p.addView(list,top(8));}

    private void buildMore(LinearLayout p){p.addView(label("จัดการเซิร์ฟเวอร์",26,fg,true));p.addView(label("รวมทุกฟีเจอร์ไว้ที่นี่ · เลือกหัวข้อที่ต้องการ",14,muted,false),top(4));String[] names={"โลก","แอดออน","สำรองข้อมูล","ผู้เล่น","วัด TPS","Playit.gg","Web Dashboard ใน LAN","เกี่ยวกับและความปลอดภัย"};String[] hints={"สร้าง สลับ นำเข้า/ส่งออก และจัดการโลก","ติดตั้งและลบ Behavior/Resource Pack","สำรองหรือกู้คืนโลกในเครื่องและ Cloud","ดูผู้เล่น ใช้ OP, Gamemode, Kick และ Ban","วัดอัตรา tick จริงจาก Script API ของโลก","ติดตั้งและรัน Playit Agent จริงบน Linux ARM64","ควบคุมเซิร์ฟเวอร์ผ่าน HTTPS ในวง LAN ด้วยรหัสผ่าน","ผู้สร้าง แหล่งดาวน์โหลด สิทธิ์ และการจัดเก็บไฟล์"};int[] pageIds={11,5,6,7,8,9,12,10};for(int i=0;i<names.length;i++){final int page=pageIds[i];LinearLayout c=card(p);c.addView(label(names[i],18,fg,true));c.addView(label(hints[i],13,muted,false),top(4));Button open=new Button(this);open.setText("เปิด");open.setOnClickListener(v->showPage(page));c.addView(open,top(8));}}

    private void buildWebDashboard(LinearLayout p){
        subpageHeader(p,"Web Dashboard ใน LAN");
        p.addView(label("ปิดไว้จนกว่าจะกดเปิด · ให้เครื่องอื่นใน Wi-Fi วงเดียวกันควบคุม BDS ผ่าน HTTPS",13,muted,false),top(5));
        LinearLayout safety=card(p);safety.addView(label("ความปลอดภัย",17,fg,true));
        safety.addView(label("ตั้งรหัสผ่านอย่างน้อย 12 ตัวอักษร · เซิร์ฟเวอร์รับเฉพาะ IP ส่วนตัวใน LAN · จำกัดการเดารหัส · session หมดอายุ · ทุกคำสั่งใช้ CSRF token · ไม่มี HTTP แบบไม่เข้ารหัส",13,muted,false),top(6));
        dashboardPasswordInput=new EditText(this);dashboardPasswordInput.setSingleLine(true);dashboardPasswordInput.setTextColor(fg);dashboardPasswordInput.setHintTextColor(muted);dashboardPasswordInput.setHint("ตั้ง/เปลี่ยนรหัสผ่าน Dashboard (12 ตัวขึ้นไป)");dashboardPasswordInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);dashboardPasswordInput.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(128)});p.addView(dashboardPasswordInput,top(10));
        Button savePassword=new Button(this);savePassword.setText("บันทึกรหัสผ่าน");savePassword.setOnClickListener(v->saveWebDashboardPassword());p.addView(savePassword,top(5));
        Button forgetPassword=new Button(this);forgetPassword.setText("ลบรหัสผ่านและปิด Dashboard");forgetPassword.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("ลบรหัสผ่าน Dashboard").setMessage("ปิด Dashboard และลบข้อมูลรหัสผ่านที่บันทึกไว้หรือไม่?").setNegativeButton("ยกเลิก",null).setPositiveButton("ลบ",(d,w)->{DashboardSecurity.clearPassword(this);stopService(new Intent(this,LanDashboardService.class));refreshWebDashboard();message("ปิด Dashboard และลบรหัสผ่านแล้ว");}).show());p.addView(forgetPassword,top(5));
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);Button enable=new Button(this);enable.setText("เปิด Dashboard");enable.setOnClickListener(v->enableWebDashboard());actions.addView(enable,new LinearLayout.LayoutParams(0,dp(50),1));Button disable=new Button(this);disable.setText("ปิด Dashboard");disable.setOnClickListener(v->disableWebDashboard());actions.addView(disable,new LinearLayout.LayoutParams(0,dp(50),1));p.addView(actions,top(8));
        LinearLayout info=card(p);dashboardInfo=label("กำลังอ่านสถานะ…",14,fg,false);dashboardInfo.setTextIsSelectable(true);info.addView(dashboardInfo);
        p.addView(label("เบราว์เซอร์จะแจ้งเตือนใบรับรอง HTTPS ที่แอปสร้างเอง ให้ตรวจ SHA-256 fingerprint บนหน้านี้ให้ตรงก่อนดำเนินการต่อ ใบรับรองจะเปลี่ยนเมื่อเปิด Dashboard ใหม่",12,muted,false),top(8));
    }

    private void refreshWebDashboard(){if(dashboardInfo==null)return;android.content.SharedPreferences prefs=getSharedPreferences(DashboardSecurity.PREFS,MODE_PRIVATE);boolean enabled=prefs.getBoolean("enabled",false);String runtime=enabled?prefs.getString("runtime","กำลังเริ่ม"):"ปิดอยู่";String url=prefs.getString("urls","");if(url.isEmpty()){InetAddress address=DashboardSecurity.primaryLanAddress();if(address==null)url="เชื่อมต่อ Wi-Fi เพื่อดู URL";else{String host=address.getHostAddress();if(address instanceof java.net.Inet6Address)host="["+host.split("%",2)[0]+"]";url="https://"+host+":"+LanDashboardService.PORT;}}String fingerprint=prefs.getString("fingerprint","");dashboardInfo.setText("สถานะ: "+runtime+"\nเปิดใช้งาน: "+(enabled?"ใช่":"ไม่")+"\nURL: "+url+"\nSHA-256 certificate fingerprint: "+(fingerprint.isEmpty()?"ยังไม่มี · เปิด Dashboard ก่อน":"\n"+fingerprint)+"\nรหัสผ่าน: "+(DashboardSecurity.hasPassword(this)?"ตั้งแล้ว":"ยังไม่ได้ตั้ง"));}
    private void saveWebDashboardPassword(){String raw=dashboardPasswordInput.getText().toString();if(raw.length()<12){message("รหัสผ่าน Dashboard ต้องมีอย่างน้อย 12 ตัวอักษร");return;}char[] password=raw.toCharArray();dashboardPasswordInput.setText("");new Thread(()->{try{DashboardSecurity.setPassword(this,password);runOnUiThread(()->{message("บันทึกรหัสผ่าน Dashboard แล้ว");if(getSharedPreferences(DashboardSecurity.PREFS,MODE_PRIVATE).getBoolean("enabled",false))startService(new Intent(this,LanDashboardService.class).setAction(LanDashboardService.ACTION_RECONFIGURE));refreshWebDashboard();});}catch(Exception e){java.util.Arrays.fill(password,'\0');runOnUiThread(()->message("บันทึกรหัสผ่านไม่สำเร็จ: "+e.getMessage()));}},"Dashboard-Password").start();}
    private void enableWebDashboard(){if(!DashboardSecurity.hasPassword(this)){message("ตั้งรหัสผ่านอย่างน้อย 12 ตัวอักษรก่อนเปิด Dashboard");return;}getSharedPreferences(DashboardSecurity.PREFS,MODE_PRIVATE).edit().putBoolean("enabled",true).apply();Intent i=new Intent(this,LanDashboardService.class).setAction(LanDashboardService.ACTION_START);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);refreshWebDashboard();}
    private void disableWebDashboard(){startService(new Intent(this,LanDashboardService.class).setAction(LanDashboardService.ACTION_STOP));refreshWebDashboard();}
    private void buildAbout(LinearLayout p){subpageHeader(p,"เกี่ยวกับและความปลอดภัย");String version="ไม่ทราบ";try{version=getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception ignored){}LinearLayout creator=card(p);creator.addView(label("BedrockPocket",20,fg,true));creator.addView(label("เวอร์ชัน "+version+" · สร้างโดย Endtherstone",14,muted,false),top(6));creator.addView(label("เครื่องมือชุมชนสำหรับจัดการ Minecraft Bedrock Dedicated Server บน Android โดยใช้ Linux runtime",13,muted,false),top(6));creator.addView(label("ไม่เกี่ยวข้อง ไม่ได้รับการรับรอง และไม่สังกัด Mojang หรือ Microsoft",12,muted,false),top(6));
        LinearLayout trust=card(p);trust.addView(label("ความปลอดภัยและความน่าเชื่อถือ",17,fg,true));String[] facts={"ไฟล์ Ubuntu base และ BDS ดาวน์โหลดผ่าน HTTPS พร้อมตรวจ SHA-256; แพ็กเกจ Ubuntu เพิ่มเติมตรวจลายเซ็น repository โดย APT.","Playit Agent รับจาก GitHub Release ทางการของ playit-cloud และตรวจ SHA-256.","การติดตั้งแอดออนปฏิเสธพาธ ZIP ที่พยายามเขียนออกนอกโฟลเดอร์ และจำกัดขนาดแตกไฟล์.","แอปไม่ต้องใช้ root; Android สำรองข้อมูลแอปอัตโนมัติถูกปิดไว้.","ปิดการเชื่อมต่อ HTTP แบบไม่เข้ารหัส.","ไฟล์เซิร์ฟเวอร์อยู่ในพื้นที่ส่วนตัวของแอป จึงไม่เห็นตรงๆ ในแอปจัดการไฟล์ทั่วไป; ส่งออกไฟล์สำรองโลกผ่านตัวเลือกไฟล์ของ Android ได้.","แอปมีสิทธิ์ INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE และ WAKE_LOCK."};for(String fact:facts)trust.addView(label("•  "+fact,13,muted,false),top(7));
        LinearLayout links=card(p);links.addView(label("แหล่งอ้างอิงทางการ",17,fg,true));addAboutLink(links,"ดาวน์โหลด Bedrock Dedicated Server","https://www.minecraft.net/en-us/download/server/bedrock");addAboutLink(links,"Minecraft EULA","https://www.minecraft.net/eula");addAboutLink(links,"Playit Agent source code","https://github.com/playit-cloud/playit-agent");addAboutLink(links,"Ubuntu releases","https://cdimage.ubuntu.com/");
        LinearLayout licenses=card(p);licenses.addView(label("ซอฟต์แวร์ภายนอก",17,fg,true));licenses.addView(label("ใช้ PRoot (MIT License) และ Apache Commons Compress, Commons IO, Commons Lang (Apache License 2.0). ไฟล์ประกาศสิทธิ์อยู่ในชุดติดตั้งของแอป",13,muted,false),top(6));licenses.addView(label("BDS รองรับอย่างเป็นทางการบน Windows และ Ubuntu; การรันผ่าน Android/Linux runtime เป็นการใช้งานโดยชุมชนและ Mojang ไม่รับรอง",12,muted,false),top(6));}
    private void addAboutLink(LinearLayout parent,String title,String url){TextView link=label(title+"  ↗",14,blue,true);link.setOnClickListener(v->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url))));parent.addView(link,top(10));}
    private void subpageHeader(LinearLayout p,String title){TextView back=label("‹  จัดการ",14,accent,true);back.setGravity(Gravity.CENTER_VERTICAL);back.setOnClickListener(v->showPage(4));p.addView(back,new LinearLayout.LayoutParams(-1,dp(38)));p.addView(label(title,26,fg,true),top(4));}
    private void buildWorlds(LinearLayout p){subpageHeader(p,"จัดการโลก");p.addView(label("สร้าง สลับ นำเข้า ส่งออก และทำสำเนาโลก Bedrock · ต้องหยุด BDS ก่อนแก้ไขโลก",13,muted,false),top(5));LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);worldCreateButton=new Button(this);worldCreateButton.setText("สร้างโลก");worldCreateButton.setOnClickListener(v->{if(worldOpsAllowed())promptWorldName("สร้างโลกใหม่","ชื่อโลกใหม่","World",name->runIo("สร้างโลก",()->{WorldManager.createWorld(bdsFilesDir,name);return "ตั้ง "+name+" เป็นโลกที่จะสร้างเมื่อเริ่ม BDS ครั้งถัดไป";},this::refreshWorlds));});actions.addView(worldCreateButton,new LinearLayout.LayoutParams(0,dp(48),1));worldImportButton=new Button(this);worldImportButton.setText("นำเข้า .mcworld");worldImportButton.setOnClickListener(v->pickWorldImport());actions.addView(worldImportButton,new LinearLayout.LayoutParams(0,dp(48),1));p.addView(actions,top(8));worldList=new LinearLayout(this);worldList.setOrientation(LinearLayout.VERTICAL);p.addView(worldList,top(8));p.addView(label("ถังขยะโลก",18,fg,true),top(20));p.addView(label("โลกที่ย้ายมาที่นี่กู้คืนได้จนกว่าจะลบถาวร",12,muted,false),top(3));trashList=new LinearLayout(this);trashList.setOrientation(LinearLayout.VERTICAL);p.addView(trashList,top(8));}
    private void refreshWorlds(){if(worldList==null)return;boolean stopped=!isServerBusy();worldCreateButton.setEnabled(stopped);worldImportButton.setEnabled(stopped);worldList.removeAllViews();trashList.removeAllViews();try{List<WorldManager.WorldInfo> worlds=WorldManager.list(bdsFilesDir);if(worlds.isEmpty())worldList.addView(label("ยังไม่มีโลก · สร้างโลกใหม่หรือนำเข้าไฟล์ .mcworld",14,muted,false));for(WorldManager.WorldInfo world:worlds)addWorldCard(world);List<WorldManager.TrashInfo> trash=WorldManager.listTrash(bdsFilesDir);if(trash.isEmpty())trashList.addView(label("ถังขยะว่าง",14,muted,false));for(WorldManager.TrashInfo item:trash)addTrashCard(item);if(!stopped){disableWorldButtons(worldList);disableWorldButtons(trashList);}}catch(Exception e){worldList.addView(label("อ่านรายการโลกไม่สำเร็จ: "+e.getMessage(),14,muted,false));}}
    private void disableWorldButtons(View view){if(view instanceof Button){view.setEnabled(false);return;}if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)disableWorldButtons(group.getChildAt(i));}}
    private void addWorldCard(WorldManager.WorldInfo world){LinearLayout c=card(worldList);LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);head.addView(label(world.name,18,fg,true),new LinearLayout.LayoutParams(0,-2,1));if(world.active)head.addView(label("กำลังเลือก",12,accent,true));c.addView(head);c.addView(label(world.ready?"พร้อมใช้งาน · level.dat และฐานข้อมูลพบแล้ว":"โลกใหม่จะถูกสร้างเมื่อเริ่ม BDS",12,muted,false),top(5));LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);Button select=new Button(this);select.setText(world.active?"โลกที่เลือก":"สลับไปโลกนี้");select.setEnabled(world.ready&&!world.active);select.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("สลับโลก").setMessage("ตั้ง "+world.name+" เป็นโลกของ BDS ในการเริ่มครั้งถัดไปหรือไม่?").setNegativeButton("ยกเลิก",null).setPositiveButton("สลับโลก",(d,w)->runIo("สลับโลก",()->{WorldManager.switchWorld(bdsFilesDir,world.name);return "เลือก "+world.name+" แล้ว · มีผลเมื่อเริ่ม BDS ครั้งถัดไป";},this::refreshWorlds)).show());actions.addView(select,new LinearLayout.LayoutParams(0,dp(46),1));Button export=new Button(this);export.setText("ส่งออก");export.setEnabled(world.ready);export.setOnClickListener(v->createWorldExport(world.name));actions.addView(export,new LinearLayout.LayoutParams(0,dp(46),1));c.addView(actions,top(7));LinearLayout more=new LinearLayout(this);more.setOrientation(LinearLayout.HORIZONTAL);Button rename=new Button(this);rename.setText("เปลี่ยนชื่อ");rename.setOnClickListener(v->promptWorldName("เปลี่ยนชื่อโลก",world.name,world.name,name->runIo("เปลี่ยนชื่อโลก",()->{WorldManager.renameWorld(bdsFilesDir,world.name,name);return "เปลี่ยนชื่อเป็น "+name+" แล้ว";},this::refreshWorlds)));more.addView(rename,new LinearLayout.LayoutParams(0,dp(44),1));Button copyWorld=new Button(this);copyWorld.setText("ทำสำเนา");copyWorld.setEnabled(world.ready);copyWorld.setOnClickListener(v->promptWorldName("ทำสำเนาโลก",world.name+" copy",world.name+" copy",name->runIo("ทำสำเนาโลก",()->{WorldManager.copyWorld(bdsFilesDir,world.name,name);return "ทำสำเนาโลกเป็น "+name+" แล้ว";},this::refreshWorlds)));more.addView(copyWorld,new LinearLayout.LayoutParams(0,dp(44),1));Button trash=new Button(this);trash.setText("ย้ายไปถังขยะ");trash.setEnabled(world.ready&&!world.active);trash.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("ย้ายโลกไปถังขยะ").setMessage("ย้าย "+world.name+" ไปถังขยะหรือไม่? ยังสามารถกู้คืนได้").setNegativeButton("ยกเลิก",null).setPositiveButton("ย้าย",(d,w)->runIo("ย้ายโลก",()->{WorldManager.moveToTrash(bdsFilesDir,world.name);return "ย้ายโลกไปถังขยะแล้ว";},this::refreshWorlds)).show());more.addView(trash,new LinearLayout.LayoutParams(0,dp(44),1));c.addView(more,top(4));}
    private void addTrashCard(WorldManager.TrashInfo item){LinearLayout c=card(trashList);c.addView(label(item.name,15,fg,true));LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);Button restore=new Button(this);restore.setText("กู้คืน");restore.setOnClickListener(v->{if(worldOpsAllowed())promptWorldName("กู้คืนโลก",item.name,item.name,name->runIo("กู้คืนโลก",()->{WorldManager.restoreTrash(bdsFilesDir,item.key,name);return "กู้คืน "+name+" แล้ว";},this::refreshWorlds));});row.addView(restore,new LinearLayout.LayoutParams(0,dp(44),1));Button delete=new Button(this);delete.setText("ลบถาวร");delete.setOnClickListener(v->{if(!worldOpsAllowed())return;new AlertDialog.Builder(this).setTitle("ลบโลกถาวร").setMessage("ลบ "+item.name+" จากถังขยะถาวร? การลบนี้ย้อนคืนไม่ได้").setNegativeButton("ยกเลิก",null).setPositiveButton("ลบถาวร",(d,w)->runIo("ลบโลกถาวร",()->{WorldManager.deleteTrash(bdsFilesDir,item.key);return "ลบโลกถาวรแล้ว";},this::refreshWorlds)).show();});row.addView(delete,new LinearLayout.LayoutParams(0,dp(44),1));c.addView(row,top(5));}
    private interface WorldNameAction{void run(String name);}
    private void promptWorldName(String title,String hint,String initial,WorldNameAction action){EditText input=new EditText(this);input.setSingleLine(true);input.setHint(hint);input.setText(initial);input.setSelection(input.getText().length());input.setTextColor(fg);input.setHintTextColor(muted);new AlertDialog.Builder(this).setTitle(title).setView(input).setNegativeButton("ยกเลิก",null).setPositiveButton("ตกลง",(d,w)->{String name=input.getText().toString();try{WorldManager.validateName(name);action.run(name.trim());}catch(Exception e){message(e.getMessage());}}).show();}
    private boolean worldOpsAllowed(){if(isServerBusy()){message("หยุด BDS ให้สนิทก่อนจัดการโลก");return false;}return true;}
    private boolean isServerBusy(){return "RUNNING".equals(serverState)||"STARTING".equals(serverState)||"STOPPING".equals(serverState)||"INSTALLING".equals(serverState)||ServerService.metricsRootPid()>0;}
    private void pickWorldImport(){if(!worldOpsAllowed())return;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("application/octet-stream");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/octet-stream","application/zip","application/x-mcworld"});i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,PICK_WORLD_IMPORT);}
    private void createWorldExport(String worldName){if(!worldOpsAllowed())return;pendingWorldExport=worldName;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("application/octet-stream");i.putExtra(Intent.EXTRA_TITLE,worldName+".mcworld");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,CREATE_WORLD_EXPORT);}
    private void buildAddons(LinearLayout p){subpageHeader(p,"ติดตั้งแอดออน");p.addView(label("เลือกไฟล์ .mcpack หรือ .mcaddon · ระบบแยก Behavior Pack และ Resource Pack จาก manifest",13,muted,false),top(4));Button pick=new Button(this);pick.setText("เลือกไฟล์แอดออน");pick.setOnClickListener(v->pickAddon());p.addView(pick,top(10));addonList=new LinearLayout(this);addonList.setOrientation(LinearLayout.VERTICAL);p.addView(addonList,top(10));}
    private void buildBackups(LinearLayout p){subpageHeader(p,"สำรองข้อมูลโลก");p.addView(label("สำรองโฟลเดอร์ worlds ทั้งชุด · สำรอง/กู้คืนได้เมื่อหยุดเซิร์ฟเวอร์",13,muted,false),top(4));Button local=new Button(this);local.setText("สำรองไว้ในเครื่อง");local.setOnClickListener(v->createLocalBackup());p.addView(local,top(10));Button cloud=new Button(this);cloud.setText("บันทึกไปยัง Google Drive / Cloud");cloud.setOnClickListener(v->createCloudBackup());p.addView(cloud,top(6));p.addView(label("ใช้ Android Document Picker เพื่อเลือก Drive หรือพื้นที่ Cloud · การซิงก์ Drive โดยตรงจะเปิดใช้หลังตั้งค่า OAuth client ของแอป",11,muted,false),top(3));Button restore=new Button(this);restore.setText("กู้คืนจากไฟล์สำรอง");restore.setOnClickListener(v->pickBackup());p.addView(restore,top(6));LinearLayout schedule=card(p);schedule.addView(label("สำรองตามเวลา · ทำงานเมื่อ BDS หยุดเท่านั้น",15,fg,true));scheduledBackupToggle=check(schedule,"เปิดสำรองอัตโนมัติในเครื่อง");android.content.SharedPreferences prefs=getSharedPreferences(BackupScheduler.PREFS,MODE_PRIVATE);scheduledBackupToggle.setChecked(prefs.getBoolean("enabled",false));schedule.addView(label("ช่วงเวลา (6–168 ชั่วโมง)",12,muted,true),top(6));backupIntervalInput=new EditText(this);backupIntervalInput.setSingleLine(true);backupIntervalInput.setInputType(InputType.TYPE_CLASS_NUMBER);backupIntervalInput.setTextColor(fg);backupIntervalInput.setText(Integer.toString(prefs.getInt("interval_hours",24)));schedule.addView(backupIntervalInput,new LinearLayout.LayoutParams(-1,dp(46)));schedule.addView(label("จำนวนชุดสำรองที่เก็บไว้ (1–30)",12,muted,true),top(5));backupRetentionInput=new EditText(this);backupRetentionInput.setSingleLine(true);backupRetentionInput.setInputType(InputType.TYPE_CLASS_NUMBER);backupRetentionInput.setTextColor(fg);backupRetentionInput.setText(Integer.toString(prefs.getInt("retention",7)));schedule.addView(backupRetentionInput,new LinearLayout.LayoutParams(-1,dp(46)));Button saveSchedule=new Button(this);saveSchedule.setText("บันทึกตารางสำรอง");saveSchedule.setOnClickListener(v->saveBackupSchedule());schedule.addView(saveSchedule,top(6));backupList=new LinearLayout(this);backupList.setOrientation(LinearLayout.VERTICAL);p.addView(backupList,top(12));}
    private void saveBackupSchedule(){try{int hours=Integer.parseInt(backupIntervalInput.getText().toString().trim()),retention=Integer.parseInt(backupRetentionInput.getText().toString().trim());BackupScheduler.configure(this,scheduledBackupToggle.isChecked(),hours,retention);refreshBackups();message(scheduledBackupToggle.isChecked()?"บันทึกตารางแล้ว · ถ้า BDS ทำงาน ระบบจะเลื่อนไปลองอีกครั้งใน 1 ชั่วโมง":"ปิดตารางสำรองแล้ว");}catch(Exception e){message("บันทึกตารางไม่ได้: "+e.getMessage());}}
    private void buildPlayers(LinearLayout p){subpageHeader(p,"จัดการผู้เล่น");p.addView(label("ดึงรายชื่อจาก log ของ BDS · คำสั่งจัดการใช้ได้ขณะเซิร์ฟเวอร์ทำงาน",13,muted,false),top(4));LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);Button refresh=new Button(this);refresh.setText("รีเฟรช");refresh.setOnClickListener(v->{if("RUNNING".equals(serverState))sendServerCommand("list");refreshPlayers();});row.addView(refresh,new LinearLayout.LayoutParams(0,dp(48),1));Button banned=new Button(this);banned.setText("รายการแบน");banned.setOnClickListener(v->showBannedPlayers());row.addView(banned,new LinearLayout.LayoutParams(0,dp(48),1));p.addView(row,top(8));playerList=new LinearLayout(this);playerList.setOrientation(LinearLayout.VERTICAL);p.addView(playerList,top(8));}

    private void buildTps(LinearLayout p){subpageHeader(p,"วัด TPS");p.addView(label("TPS วัดจากจำนวน world tick จริงต่อวินาที · 20 TPS คือเป้าหมายปกติ",14,muted,false),top(5));tpsToggle=check(p,"ติดตั้งตัววัด TPS ในโลกปัจจุบัน");tpsToggle.setChecked(getSharedPreferences("server",MODE_PRIVATE).getBoolean("tps_enabled",false));p.addView(label("ตัววัด TPS ฝังมากับแอปแล้ว · เมื่อติดตั้ง แอปจะคัดลอก Behavior Pack ไปยังเซิร์ฟเวอร์และเพิ่มในรายการแพ็กของโลก พร้อมเปิด console content log เพื่ออ่านตัวเลข การเปลี่ยนแปลงทำตอนเซิร์ฟเวอร์หยุด และปิดกลับได้จากหน้านี้",12,muted,false),top(5));Button apply=new Button(this);apply.setText("บันทึกและใช้กับโลก");apply.setOnClickListener(v->{boolean enable=tpsToggle.isChecked();if("RUNNING".equals(serverState)||"STARTING".equals(serverState)||"STOPPING".equals(serverState)){message("หยุดเซิร์ฟเวอร์ก่อนเปลี่ยนแพ็กวัด TPS");return;}runIo("ตั้งค่าตัววัด TPS",()->{if(enable)TpsManager.enable(this,bdsFilesDir);else TpsManager.disable(bdsFilesDir);getSharedPreferences("server",MODE_PRIVATE).edit().putBoolean("tps_enabled",enable).apply();return enable?"ติดตั้งตัววัด TPS จากไฟล์ที่ฝังในแอปแล้ว · เริ่ม BDS ใหม่เพื่อเก็บค่า":"ปิดตัววัดและนำ Behavior Pack ออกจากโลกแล้ว";},()->{sampleStats();});});p.addView(apply,top(12));}
    private void refreshTpsPage(){if(tpsToggle!=null)tpsToggle.setChecked(getSharedPreferences("server",MODE_PRIVATE).getBoolean("tps_enabled",false));}
    private void buildPlayit(LinearLayout p){
        subpageHeader(p,"Playit.gg");
        p.addView(label("ตัวควบคุมนี้ดาวน์โหลดและรัน Playit Agent ทางการจริงใน Linux ARM64 ของแอป ไม่ได้จำลองสถานะ",14,muted,false),top(5));
        LinearLayout card=card(p);card.addView(label("Playit Agent",13,muted,true));playitStatus=label("ยังไม่ได้เริ่ม",18,fg,true);card.addView(playitStatus,top(7));
        LinearLayout tunnel=card(p);tunnel.addView(label("สถานะการเชื่อมต่อ · IP / ที่อยู่สาธารณะ",13,muted,true));playitAddress=label("ยังไม่พบ tunnel",17,fg,true);tunnel.addView(playitAddress,top(7));
        LinearLayout traffic=card(p);traffic.addView(label("ปริมาณข้อมูลของ Playit Agent",13,muted,true));playitTrafficIn=label("รับ  — MB",16,fg,true);playitTrafficOut=label("ส่ง  — MB",16,fg,true);traffic.addView(playitTrafficIn,top(7));traffic.addView(playitTrafficOut,top(4));
        LinearLayout counts=card(p);counts.addView(label("การเชื่อมต่อและผู้เล่น",13,muted,true));playitConnections=label("Connections ใน tunnel (TCP + UDP)  —",15,fg,true);playitPlayers=label("ผู้เล่นออนไลน์ใน BDS  —",15,fg,true);counts.addView(playitConnections,top(7));counts.addView(playitPlayers,top(4));
        playitStartButton=new Button(this);playitStartButton.setText("ติดตั้ง / เริ่ม Agent จริง");playitStartButton.setOnClickListener(v->startPlayitAgent());p.addView(playitStartButton,top(12));
        playitStopButton=new Button(this);playitStopButton.setText("หยุด Agent");playitStopButton.setOnClickListener(v->stopPlayitAgent());p.addView(playitStopButton,top(5));
        playitClaimButton=new Button(this);playitClaimButton.setText("เปิดลิงก์ยืนยัน Agent");playitClaimButton.setOnClickListener(v->{String url=PlayitService.currentClaimUrl();if(url!=null)startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));});p.addView(playitClaimButton,top(5));
        p.addView(label("ดาวน์โหลดจาก GitHub Release ของ playit-cloud/playit-agent และตรวจ SHA-256 ก่อนรัน · การเริ่ม Agent ยังไม่สร้าง tunnel ให้อัตโนมัติ ต้องผูกกับบัญชีและสร้าง Minecraft Bedrock (NetherNet) tunnel ใน Playit",12,muted,false),top(10));
        p.addView(label("สำหรับ BDS คู่มือ Playit ระบุให้ใช้ WaterdogPE proxy คั่นและตั้ง backend ตาม Configuration help ของ tunnel ก่อนผู้เล่นจะเชื่อมจากอินเทอร์เน็ตได้ · ผู้เล่นยังต้องลงชื่อเข้าใช้ Xbox Live",12,muted,false),top(8));
        Button guide=new Button(this);guide.setText("เปิดคู่มือ Playit สำหรับ Bedrock");guide.setOnClickListener(v->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://playit.gg/support/minecraft-bedrock-nethernet/"))));p.addView(guide,top(12));
        Button dashboard=new Button(this);dashboard.setText("เปิดบัญชี / Dashboard Playit");dashboard.setOnClickListener(v->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://playit.gg/"))));p.addView(dashboard,top(6));
        refreshPlayitState();
    }

    private void startPlayitAgent(){
        if(!ServerInstaller.isInstalled(serverDir)){message("ติดตั้ง BDS และ Linux runtime ให้เสร็จก่อน แล้วจึงเริ่ม Playit Agent ได้");return;}
        Intent i=new Intent(this,PlayitService.class).setAction(PlayitService.ACTION_START);
        if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);
        refreshPlayitState();
    }
    private void stopPlayitAgent(){startService(new Intent(this,PlayitService.class).setAction(PlayitService.ACTION_STOP));refreshPlayitState();}
    private void refreshPlayitState(){
        if(playitStatus==null)return;
        String state=PlayitService.currentState();String detail;
        switch(state){case "STARTING":detail="กำลังดาวน์โหลด / เริ่ม Agent";break;case "WAITING_FOR_CLAIM":detail="รอยืนยัน Agent กับบัญชี Playit";break;case "RUNNING":detail="Agent process กำลังทำงาน";break;case "STOPPING":detail="กำลังหยุด Agent";break;case "ERROR":detail="เริ่ม Agent ไม่สำเร็จ · ดู Console log";break;default:detail=PlayitManager.isInstalled(getFilesDir())?"ติดตั้งแล้ว · หยุดอยู่":"ยังไม่ได้ติดตั้ง";}
        playitStatus.setText(detail);
        if(playitStartButton!=null)playitStartButton.setEnabled(!"STARTING".equals(state)&&!"RUNNING".equals(state)&&!"WAITING_FOR_CLAIM".equals(state));
        if(playitStopButton!=null)playitStopButton.setEnabled("STARTING".equals(state)||"RUNNING".equals(state)||"WAITING_FOR_CLAIM".equals(state));
        if(playitClaimButton!=null)playitClaimButton.setVisibility(PlayitService.currentClaimUrl()==null?View.GONE:View.VISIBLE);
        String address=PlayitService.currentTunnelAddress();
        if(playitAddress!=null)playitAddress.setText(address==null?("RUNNING".equals(state)?"Agent ยังไม่รายงาน tunnel · ตรวจบัญชีและสร้าง tunnel ใน Dashboard":"ยังไม่เชื่อมต่อ"):"เชื่อมต่อ · "+address);
        if(playitTrafficIn!=null){
            boolean running="RUNNING".equals(state)||"WAITING_FOR_CLAIM".equals(state);
            boolean metrics=running&&PlayitService.hasMetrics();
            playitTrafficIn.setText("รับ  "+(metrics?formatPlayitMb(PlayitService.currentBytesIn()):"—")+" MB");
            playitTrafficOut.setText("ส่ง  "+(metrics?formatPlayitMb(PlayitService.currentBytesOut()):"—")+" MB");
            playitConnections.setText("การเชื่อมต่อใน tunnel  "+(metrics?PlayitService.currentActiveConnections():"—"));
            int players="RUNNING".equals(serverState)?onlinePlayerCount():0;
            playitPlayers.setText("ผู้เล่นออนไลน์ใน BDS  "+("RUNNING".equals(serverState)?players:"0")+" คน");
        }
    }

    private String formatPlayitMb(long bytes){return String.format(java.util.Locale.US,"%.2f",bytes/1_000_000d);}
    private int onlinePlayerCount(){
        if(!"RUNNING".equals(serverState))return 0;
        int count=0;for(Boolean isOnline:onlinePlayersFromLog().values())if(Boolean.TRUE.equals(isOnline))count++;return count;
    }

    private void pickAddon(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/zip","application/octet-stream","application/x-mcaddon","application/x-mcpack"});i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,PICK_ADDON);}
    private void pickBackup(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("application/zip");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,PICK_BACKUP);}
    private void createCloudBackup(){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("application/zip");i.putExtra(Intent.EXTRA_TITLE,"BedrockPocket-worlds-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",java.util.Locale.US).format(new java.util.Date())+".zip");startActivityForResult(i,CREATE_BACKUP);}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode==CREATE_WORLD_EXPORT&&resultCode!=RESULT_OK){pendingWorldExport=null;return;}if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();
        if(requestCode==PICK_WORLD_IMPORT){String suggested=displayName(uri);if(suggested.toLowerCase(java.util.Locale.ROOT).endsWith(".mcworld"))suggested=suggested.substring(0,suggested.length()-8);final String initial=suggested;promptWorldName("นำเข้าโลก .mcworld","ชื่อโลกที่จะเพิ่ม",initial,name->runIo("นำเข้าโลก",()->{File temp=new File(getCacheDir(),"mcworld-import-"+System.currentTimeMillis()+".mcworld");try{try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new java.io.FileOutputStream(temp)){if(in==null)throw new IOException("เปิดไฟล์ที่เลือกไม่ได้");copyLimited(in,out,WorldManager.MAX_ARCHIVE_BYTES,temp.getParentFile());}WorldManager.importMcworld(temp,bdsFilesDir,name);return "นำเข้าโลก "+name+" แล้ว";}finally{temp.delete();}},this::refreshWorlds));}
        else if(requestCode==CREATE_WORLD_EXPORT){String worldName=pendingWorldExport;pendingWorldExport=null;if(worldName!=null)runIo("ส่งออกโลก",()->{File world=new File(new File(bdsFilesDir,"worlds"),worldName);try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("เปิดปลายทางไม่ได้");WorldManager.exportMcworld(world,out);}return "ส่งออก "+worldName+" เป็น .mcworld แล้ว";},()->{});}
        else if(requestCode==CREATE_LOG_EXPORT){try(InputStream in=new java.io.FileInputStream(logFile);OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("เปิดปลายทางไม่ได้");copy(in,out);message("ส่งออก log แล้ว");}catch(Exception e){message("ส่งออก log ไม่สำเร็จ: "+e.getMessage());}}
        else if(requestCode==PICK_ADDON){runIo("ติดตั้งแอดออน",()->{File temp=new File(getCacheDir(),"addon-import-"+System.currentTimeMillis()+".zip");try{try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new java.io.FileOutputStream(temp)){if(in==null)throw new IOException("เปิดไฟล์ที่เลือกไม่ได้");copy(in,out);}List<AddonManager.Pack> packs=AddonManager.install(temp,bdsFilesDir);return "ติดตั้งสำเร็จ "+packs.size()+" แพ็ก";}finally{temp.delete();}},this::refreshAddons);}
        else if(requestCode==CREATE_BACKUP){runIo("สำรองไปยัง Cloud",()->{try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("เปิดปลายทางไม่ได้");WorldBackupManager.create(bdsFilesDir,out);}return "สร้างไฟล์สำรองแล้ว · ตรวจสอบว่าเลือก Google Drive เป็นปลายทาง";},()->{});}
        else if(requestCode==PICK_BACKUP){new AlertDialog.Builder(this).setTitle("ยืนยันการกู้คืน").setMessage("การกู้คืนจะแทนที่โฟลเดอร์ worlds ปัจจุบัน โดยเก็บโลกเดิมเป็นไฟล์สำรองก่อน ต้องการดำเนินการหรือไม่?").setNegativeButton("ยกเลิก",null).setPositiveButton("กู้คืน",(d,w)->runIo("กู้คืนโลก",()->{File temp=new File(getCacheDir(),"world-restore-"+System.currentTimeMillis()+".zip");try{try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new java.io.FileOutputStream(temp)){if(in==null)throw new IOException("อ่านไฟล์ไม่ได้");copy(in,out);}WorldBackupManager.restore(temp,bdsFilesDir,backupDir);return "กู้คืนโลกสำเร็จ · เก็บโลกก่อนกู้คืนไว้ในรายการสำรอง";}finally{temp.delete();}},()->{})).show();}}
    private String displayName(Uri uri){try(android.database.Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()){String name=c.getString(0);if(name!=null&&!name.trim().isEmpty())return name;}}catch(Exception ignored){}return "Imported World";}
    private interface IoAction{String run()throws Exception;}private void runIo(String title,IoAction work,Runnable done){if(isServerBusy()){message("หยุดเซิร์ฟเวอร์ก่อนทำรายการนี้");return;}if(ioBusy){message("กำลังทำรายการอื่นอยู่ รอสักครู่");return;}ioBusy=true;if(powerButton!=null)powerButton.setEnabled(false);new Thread(()->{String result;boolean ok=true;try{result=work.run();}catch(Exception e){ok=false;result=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}String message=result;boolean success=ok;runOnUiThread(()->{ioBusy=false;renderStatus(null);new AlertDialog.Builder(this).setTitle(success?title+"สำเร็จ":title+"ไม่สำเร็จ").setMessage(message).setPositiveButton("ตกลง",null).show();if(success)done.run();});},"BedrockPocket-IO").start();}
    private void createLocalBackup(){runIo("สำรองในเครื่อง",()->{if(!backupDir.isDirectory()&&!backupDir.mkdirs())throw new IOException("สร้างโฟลเดอร์สำรองไม่ได้");File out=new File(backupDir,"worlds-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",java.util.Locale.US).format(new java.util.Date())+".zip");try(OutputStream stream=new java.io.FileOutputStream(out)){WorldBackupManager.create(bdsFilesDir,stream);}return "บันทึกที่ "+out.getName();},this::refreshBackups);}
    private void copy(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[32768];int n;while((n=in.read(b))>0)out.write(b,0,n);}
    private void copyLimited(InputStream in,OutputStream out,long limit,File targetDirectory)throws IOException{byte[] b=new byte[32768];long total=0;int n;while((n=in.read(b))>0){total+=n;if(total>limit)throw new IOException("ไฟล์โลกใหญ่เกินขีดจำกัด 8 GB");if(n>targetDirectory.getUsableSpace())throw new IOException("พื้นที่ว่างในเครื่องไม่พอสำหรับไฟล์โลก");out.write(b,0,n);}}
    private void refreshAddons(){if(addonList==null)return;addonList.removeAllViews();List<AddonManager.Pack> packs=AddonManager.list(bdsFilesDir);if(packs.isEmpty()){addonList.addView(label("ยังไม่มีแอดออนที่ติดตั้ง",14,muted,false));return;}for(AddonManager.Pack pack:packs){LinearLayout card=card(addonList);card.addView(label(pack.name,16,fg,true));card.addView(label(pack.type+" · v"+pack.version,12,muted,false),top(3));Button del=new Button(this);del.setText("ลบแอดออน");del.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("ยืนยันการลบ").setMessage("ลบ "+pack.name+" หรือไม่? ไฟล์แพ็กนี้จะถูกลบออก").setNegativeButton("ยกเลิก",null).setPositiveButton("ลบ",(d,w)->{try{AddonManager.deleteTree(pack.directory);refreshAddons();}catch(Exception e){message("ลบไม่สำเร็จ: "+e.getMessage());}}).show());card.addView(del,top(5));applyMaterialStyles(card);}}
    private void refreshBackups(){if(backupList==null)return;backupList.removeAllViews();boolean scheduled=getSharedPreferences(BackupScheduler.PREFS,MODE_PRIVATE).getBoolean("enabled",false);String last=getSharedPreferences(BackupScheduler.PREFS,MODE_PRIVATE).getString("last_result","ยังไม่มีผลสำรองตามเวลา");backupList.addView(label("ตั้งเวลา: "+(scheduled?"เปิด":"ปิด")+" · "+last,12,muted,false),top(2));if(!backupDir.isDirectory()){backupList.addView(label("ยังไม่มีไฟล์สำรองในเครื่อง",14,muted,false),top(5));return;}File[] files=backupDir.listFiles((d,n)->n.endsWith(".zip"));if(files==null||files.length==0){backupList.addView(label("ยังไม่มีไฟล์สำรองในเครื่อง",14,muted,false),top(5));return;}java.util.Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));for(File f:files){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(8),dp(6),dp(8),dp(6));row.setBackground(shape(panel,18,0));TextView n=label(f.getName(),13,fg,true);row.addView(n,new LinearLayout.LayoutParams(0,dp(48),1));Button restore=new Button(this);restore.setText("กู้คืน");restore.setOnClickListener(v->new AlertDialog.Builder(this).setMessage("แทนที่โลกปัจจุบันด้วยไฟล์นี้หรือไม่? โลกปัจจุบันจะถูกสำรองไว้ก่อน").setNegativeButton("ยกเลิก",null).setPositiveButton("กู้คืน",(d,w)->runIo("กู้คืนโลก",()->{WorldBackupManager.restore(f,bdsFilesDir,backupDir);return "กู้คืนจาก "+f.getName()+" สำเร็จ · โลกก่อนหน้าเก็บไว้ในรายการสำรอง";},()->{})).show());row.addView(restore);Button del=new Button(this);del.setText("ลบ");del.setOnClickListener(v->new AlertDialog.Builder(this).setMessage("ลบไฟล์สำรอง "+f.getName()+" หรือไม่?").setNegativeButton("ยกเลิก",null).setPositiveButton("ลบ",(d,w)->{if(f.delete())refreshBackups();else message("ลบไฟล์ไม่สำเร็จ");}).show());row.addView(del);backupList.addView(row,top(5));applyMaterialStyles(row);}}
    private java.util.LinkedHashMap<String,Boolean> onlinePlayersFromLog(){
        java.util.LinkedHashMap<String,Boolean> online=new java.util.LinkedHashMap<>();
        java.util.regex.Matcher events=java.util.regex.Pattern.compile("(?i)Player (connected|disconnected):\\s*([^,\\r\\n]+)|There are \\d+\\s*/\\s*\\d+ players online:\\s*([^\\r\\n]*)").matcher(ServerInstaller.readLog(logFile));
        while(events.find()){
            if(events.group(1)!=null){String name=events.group(2).trim();if(!name.isEmpty())online.put(name,events.group(1).equalsIgnoreCase("connected"));}
            else{String names=events.group(3).trim();java.util.HashSet<String> listed=new java.util.HashSet<>();if(!names.isEmpty())for(String name:names.split(",\\s*"))if(!name.isBlank())listed.add(name.trim());for(String name:new java.util.ArrayList<>(online.keySet()))online.put(name,listed.contains(name));for(String name:listed)online.put(name,true);}
        }
        return online;
    }
    private void refreshPlayers(){if(playerList==null)return;playerList.removeAllViews();Map<String,Boolean> online=onlinePlayersFromLog();int count=0;for(Map.Entry<String,Boolean> e:online.entrySet())if(e.getValue()){count++;addPlayerCard(e.getKey());}if(count==0)playerList.addView(label("ไม่พบผู้เล่นออนไลน์ใน log ล่าสุด"+("RUNNING".equals(serverState)?" · กดรีเฟรชเพื่อสั่ง list":" · เซิร์ฟเวอร์หยุดอยู่"),14,muted,false));}
    private void addPlayerCard(String name){LinearLayout c=card(playerList);c.addView(label("●  "+name,16,accent,true));LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);Button op=new Button(this);op.setText("ให้ OP");op.setOnClickListener(v->playerCommand("op",name));actions.addView(op,new LinearLayout.LayoutParams(0,dp(46),1));Button gm=new Button(this);gm.setText("Gamemode");gm.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("เลือก Gamemode").setItems(new String[]{"Survival","Creative","Adventure"},(d,w)->playerCommand("gamemode "+new String[]{"survival","creative","adventure"}[w],name)).show());actions.addView(gm,new LinearLayout.LayoutParams(0,dp(46),1));Button kick=new Button(this);kick.setText("Kick");kick.setOnClickListener(v->new AlertDialog.Builder(this).setMessage("เตะ "+name+" ออกจากเซิร์ฟเวอร์หรือไม่?").setNegativeButton("ยกเลิก",null).setPositiveButton("Kick",(d,w)->playerCommand("kick",name)).show());actions.addView(kick,new LinearLayout.LayoutParams(0,dp(46),1));Button ban=new Button(this);ban.setText("Ban");ban.setOnClickListener(v->new AlertDialog.Builder(this).setMessage("แบน "+name+"? แอปจะเตะตอนนี้และเตะซ้ำเมื่อชื่อนี้กลับเข้ามา").setNegativeButton("ยกเลิก",null).setPositiveButton("Ban",(d,w)->banPlayer(name)).show());actions.addView(ban,new LinearLayout.LayoutParams(0,dp(46),1));c.addView(actions,top(4));applyMaterialStyles(c);}
    private void playerCommand(String cmd,String player){if(!"RUNNING".equals(serverState)){message("ต้องเปิดเซิร์ฟเวอร์ก่อน");return;}sendServerCommand(cmd+" \""+player.replace("\"","").replace("\n","").replace("\r","")+"\"");}
    private void sendServerCommand(String cmd){Intent i=new Intent(this,ServerService.class).setAction(ServerService.ACTION_COMMAND).putExtra(ServerService.EXTRA_MESSAGE,cmd);startService(i);}
    private void banPlayer(String name){java.util.Set<String> bans=getSharedPreferences("player_bans",MODE_PRIVATE).getStringSet("names",new java.util.HashSet<>());java.util.HashSet<String> next=new java.util.HashSet<>(bans);next.add(name);getSharedPreferences("player_bans",MODE_PRIVATE).edit().putStringSet("names",next).apply();playerCommand("kick",name);refreshPlayers();}
    private void showBannedPlayers(){java.util.Set<String> bans=getSharedPreferences("player_bans",MODE_PRIVATE).getStringSet("names",new java.util.HashSet<>());String[] names=bans.toArray(new String[0]);if(names.length==0){message("ไม่มีรายชื่อแบน");return;}new AlertDialog.Builder(this).setTitle("แตะชื่อเพื่อปลดแบน").setItems(names,(d,w)->{java.util.HashSet<String> next=new java.util.HashSet<>(bans);next.remove(names[w]);getSharedPreferences("player_bans",MODE_PRIVATE).edit().putStringSet("names",next).apply();message("ปลดแบน "+names[w]+" แล้ว");}).setNegativeButton("ปิด",null).show();}
    private void message(String text){new AlertDialog.Builder(this).setMessage(text).setPositiveButton("ตกลง",null).show();}
    private void copyStatusIp(String label,String value){if(value==null||value.trim().isEmpty()||value.startsWith("ดู IP")){android.widget.Toast.makeText(this,"ยังไม่มีที่อยู่ให้คัดลอก",android.widget.Toast.LENGTH_SHORT).show();return;}ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText(label,value));android.widget.Toast.makeText(this,"คัดลอก "+label+" แล้ว",android.widget.Toast.LENGTH_SHORT).show();}

    private void renderStatus(String message){if(status==null)return;String detail=message==null?"":message;
        if("RUNNING".equals(serverState)){status.setText("ONLINE");status.setTextColor(accent);statusDot.setTextColor(accent);hostInfo.setText("Bedrock · "+localAddress()+":19132");powerButton.setText("หยุดเซิร์ฟเวอร์");powerButton.setEnabled(true);}
        else if("STARTING".equals(serverState)||"STOPPING".equals(serverState)||"INSTALLING".equals(serverState)){status.setText("กำลัง"+("STARTING".equals(serverState)?"เริ่ม":"INSTALLING".equals(serverState)?"ติดตั้ง":"หยุด")+"…  "+detail);status.setTextColor(blue);statusDot.setTextColor(blue);powerButton.setEnabled(false);powerButton.setText("กำลังทำงาน…");}
        else if("ERROR".equals(serverState)){status.setText("เริ่มไม่สำเร็จ");status.setTextColor(Color.rgb(248,113,113));statusDot.setTextColor(Color.rgb(248,113,113));powerButton.setText("ลองเริ่มใหม่");powerButton.setEnabled(true);}
        else {status.setText("OFFLINE");status.setTextColor(muted);statusDot.setTextColor(muted);hostInfo.setText("ที่อยู่จะแสดงเมื่อเซิร์ฟเวอร์ออนไลน์");powerButton.setText("เริ่มเซิร์ฟเวอร์");powerButton.setEnabled(ServerInstaller.isInstalled(serverDir));}
        if(powerButton!=null)stylePrimaryButton(powerButton);
        if(installButton!=null)installButton.setEnabled(!"INSTALLING".equals(serverState));refreshSetupGuide();
    }
    private boolean supportsArm64(){return Build.SUPPORTED_64_BIT_ABIS!=null&&Build.SUPPORTED_64_BIT_ABIS.length>0;}
    private void startServer(){if(!ServerInstaller.isInstalled(serverDir)){showSetupWizard();return;}if(!supportsArm64()){message("BDS ทางการผ่าน Box64 ต้องใช้ Android ARM64 64-bit · เครื่อง 32-bit ไม่รองรับ");return;}if(!getSharedPreferences("server",MODE_PRIVATE).getBoolean("eula_accepted",false)){showSetupWizard();return;}android.app.ActivityManager.MemoryInfo memory=new android.app.ActivityManager.MemoryInfo();((android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(memory);long budget=getSharedPreferences("server",MODE_PRIVATE).getLong("ram_budget_mb",1024);if(memory.availMem<budget*1024L*1024L){new AlertDialog.Builder(this).setTitle("RAM เหลือน้อย").setMessage("RAM ว่าง "+formatStorage(memory.availMem)+" · งบประมาณแนะนำของเซิร์ฟเวอร์ "+budget+" MB\nการเปิดต่ออาจทำให้ Android ปิดแอปหรือเซิร์ฟเวอร์").setNegativeButton("ยกเลิก",null).setPositiveButton("เปิดต่อ",(d,w)->launchSelectedServer()).show();return;}launchSelectedServer();}
    private void launchSelectedServer(){serverState="STARTING";renderStatus("เปิด "+ServerProfileManager.name(this,ServerProfileManager.activeId(this)));Intent i=new Intent(this,ServerService.class).setAction(ServerService.ACTION_START);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
    private void stopServer(){serverState="STOPPING";renderStatus("ส่งคำสั่งหยุด");startService(new Intent(this,ServerService.class).setAction(ServerService.ACTION_STOP));}
    private void installServer(){if(!supportsArm64()){message("อุปกรณ์นี้ไม่มี Android ARM64 64-bit · BDS ทางการและ Box64 ต้องใช้ ARM64 จึงติดตั้งไม่ได้");return;}if(!getSharedPreferences("server",MODE_PRIVATE).getBoolean("eula_accepted",false)){showSetupWizard();return;}android.app.ActivityManager.MemoryInfo memory=new android.app.ActivityManager.MemoryInfo();((android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(memory);boolean hasNetwork=false;try{android.net.ConnectivityManager cm=(android.net.ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);android.net.NetworkInfo active=cm==null?null:cm.getActiveNetworkInfo();hasNetwork=active!=null&&active.isConnected();}catch(Exception ignored){}final boolean networkConnected=hasNetwork;long free=serverDir.getParentFile().getUsableSpace();String details="สถาปัตยกรรม: ARM64\nพื้นที่ว่าง: "+formatStorage(free)+" (ต้องมีอย่างน้อย 2.5 GB)\nRAM ว่าง: "+formatStorage(memory.availMem)+"\nเครือข่าย: "+(networkConnected?"เชื่อมต่อแล้ว":"ยังไม่เชื่อมต่อ")+"\nEULA: ยอมรับแล้ว\n\nการติดตั้งดาวน์โหลด Ubuntu, Box64 และ Mojang BDS อาจใช้เวลานาน ให้เสียบชาร์จและเปิดแอปค้างไว้";new AlertDialog.Builder(this).setTitle("ตรวจความพร้อมก่อนติดตั้ง").setMessage(details).setNegativeButton("ยกเลิก",null).setPositiveButton(free<2_500_000_000L||!networkConnected?"ตรวจอีกครั้ง":"เริ่มติดตั้ง",(d,w)->{if(free<2_500_000_000L){message("พื้นที่ว่างไม่ถึง 2.5 GB");return;}if(!networkConnected){message("เชื่อมต่ออินเทอร์เน็ตก่อนติดตั้ง");return;}Intent i=new Intent(this,ServerService.class).setAction(ServerService.ACTION_INSTALL);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);serverState="INSTALLING";lastServerMessage="กำลังเตรียม Linux runtime";renderStatus(lastServerMessage);}).show();}
        private void checkUpdates(){
        new AlertDialog.Builder(this).setTitle("ตรวจสอบอัปเดต").setMessage("กำลังตรวจหน้าดาวน์โหลดทางการของ Mojang…").setPositiveButton("ตกลง",null).show();
        new Thread(()->{
            String result;
            java.net.HttpURLConnection connection=null;
            try {
                connection=(java.net.HttpURLConnection)new java.net.URL("https://www.minecraft.net/en-us/download/server/bedrock").openConnection();
                connection.setConnectTimeout(8000);connection.setReadTimeout(8000);connection.setRequestProperty("User-Agent","BedrockPocket/0.8.32");
                String html=readUpdatePage(connection.getInputStream());
                java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("bedrock-server-(\\d+\\.\\d+\\.\\d+\\.\\d+)\\.zip").matcher(html);
                java.util.TreeSet<String> versions=new java.util.TreeSet<>((a,b)->{String[]x=a.split("\\."),y=b.split("\\.");for(int i=0;i<Math.min(x.length,y.length);i++){int c=Integer.compare(Integer.parseInt(x[i]),Integer.parseInt(y[i]));if(c!=0)return c;}return Integer.compare(x.length,y.length);});
                while(matcher.find())versions.add(matcher.group(1));
                String latest=versions.isEmpty()?null:versions.last();
                result=latest==null?"เปิดหน้าเว็บทางการได้ แต่หาเลขรุ่น BDS ในหน้าไม่พบ รุ่นในแอป: "+ServerInstaller.SERVER_VERSION:latest.equals(ServerInstaller.SERVER_VERSION.replace("Mojang BDS ",""))?"BDS ในแอปเป็นรุ่นล่าสุดที่พบจากหน้า Mojang: "+latest:"พบรุ่นทางการ "+latest+" · รุ่นในแอป "+ServerInstaller.SERVER_VERSION+" · ยังไม่ได้ติดตั้งอัปเดตอัตโนมัติ";
            }catch(Exception e){result="ตรวจหน้า Mojang ไม่สำเร็จ: "+e.getMessage();}
            finally{if(connection!=null)connection.disconnect();}
            String message=result;runOnUiThread(()->new AlertDialog.Builder(this).setTitle("ผลตรวจอัปเดต").setMessage(message).setPositiveButton("ตกลง",null).show());
        },"BDS-Update-Check").start();
    }

    private static String readUpdatePage(InputStream input)throws IOException{
        try(InputStream in=input;java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int total=0,n;
            while((n=in.read(buffer))!=-1){total+=n;if(total>MAX_UPDATE_PAGE_BYTES)throw new IOException("หน้าอัปเดตใหญ่เกิน 8 MB");output.write(buffer,0,n);}
            return new String(output.toByteArray(),StandardCharsets.UTF_8);
        }
    }

        private LinearLayout settingsSection(LinearLayout parent,String title,boolean expanded){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(12),dp(6),dp(12),dp(12));card.setBackgroundColor(panel);parent.addView(card,top(10));
        Button header=new Button(this);header.setAllCaps(false);header.setText((expanded?"▾  ":"▸  ")+title);card.addView(header,new LinearLayout.LayoutParams(-1,dp(48)));
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);card.addView(body);body.setVisibility(expanded?View.VISIBLE:View.GONE);
        header.setOnClickListener(v->{boolean open=body.getVisibility()!=View.VISIBLE;body.setVisibility(open?View.VISIBLE:View.GONE);header.setText((open?"▾  ":"▸  ")+title);});
        return body;
    }
    private void addTextSetting(LinearLayout p,String key,String title,String def,int inputType,String help){
        settingDefaults.put(key,def);p.addView(label(title,13,muted,true),top(10));EditText e=new EditText(this);e.setSingleLine(true);e.setTextColor(fg);e.setHintTextColor(muted);e.setBackgroundColor(bg);e.setPadding(dp(12),0,dp(12),0);e.setInputType(inputType);e.setTag(key);e.setHint(def.isEmpty()?"ไม่กำหนด":def);p.addView(e,new LinearLayout.LayoutParams(-1,dp(48)));settingFields.put(key,e);if(help!=null&&!help.isEmpty())p.addView(label(help,11,muted,false),top(2));
    }
    private void addChoiceSetting(LinearLayout p,String key,String title,String def,String[] values,String[] labels){
        settingDefaults.put(key,def);p.addView(label(title,13,muted,true),top(10));Spinner spinner=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,labels);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);spinner.setAdapter(adapter);int selected=0;for(int i=0;i<values.length;i++)if(values[i].equals(def))selected=i;spinner.setSelection(selected);p.addView(spinner,new LinearLayout.LayoutParams(-1,dp(48)));spinner.setTag(values);settingSpinners.put(key,spinner);
    }
    private void addToggleSetting(LinearLayout p,String key,String title,boolean def){settingDefaults.put(key,Boolean.toString(def));CheckBox toggle=check(p,title);toggle.setTag(key);settingToggles.put(key,toggle);}
    private CheckBox check(LinearLayout p,String title){CheckBox c=new CheckBox(this);c.setText(title);c.setTextColor(fg);p.addView(c,top(4));return c;}
    private void loadSettings(){
        Map<String,String> values=readProperties(new File(bdsFilesDir,"server.properties"));
        if(ramBudgetInput!=null)ramBudgetInput.setText(Long.toString(getSharedPreferences("server",MODE_PRIVATE).getLong("ram_budget_mb",1024)));
        for(String key:settingFields.keySet())settingFields.get(key).setText(values.getOrDefault(key,settingDefaults.getOrDefault(key,"")));
        for(String key:settingSpinners.keySet()){Spinner spinner=settingSpinners.get(key);String[] options=(String[])spinner.getTag();String value=values.getOrDefault(key,settingDefaults.getOrDefault(key,""));int index=0;for(int i=0;i<options.length;i++)if(options[i].equalsIgnoreCase(value)){index=i;break;}spinner.setSelection(index);}
        for(String key:settingToggles.keySet())settingToggles.get(key).setChecked(Boolean.parseBoolean(values.getOrDefault(key,settingDefaults.get(key))));
    }
    private String defaultSetting(String key){return settingDefaults.getOrDefault(key,"");}private Map<String,String> readProperties(File f){Map<String,String> m=new LinkedHashMap<>();if(f.isFile())try{for(String line:java.nio.file.Files.readAllLines(f.toPath(),StandardCharsets.UTF_8)){int x=line.indexOf('=');if(x>0)m.put(line.substring(0,x).trim(),line.substring(x+1).trim());}}catch(Exception ignored){}return m;}
        private void saveSettings(){
        if("RUNNING".equals(serverState)||"STARTING".equals(serverState)){new AlertDialog.Builder(this).setMessage("หยุดเซิร์ฟเวอร์ก่อนบันทึกการตั้งค่า").setPositiveButton("ตกลง",null).show();return;}
        Map<String,String> values=readProperties(new File(bdsFilesDir,"server.properties"));
        for(String key:settingFields.keySet())values.put(key,settingFields.get(key).getText().toString().trim());
        for(String key:settingSpinners.keySet()){Spinner spinner=settingSpinners.get(key);String[] options=(String[])spinner.getTag();values.put(key,options[spinner.getSelectedItemPosition()]);}
        for(String key:settingToggles.keySet())values.put(key,Boolean.toString(settingToggles.get(key).isChecked()));
        values.put("transport","nethernet");
        String error=validateSettings(values);if(error!=null){new AlertDialog.Builder(this).setTitle("ตรวจค่าก่อนบันทึก").setMessage(error).setPositiveButton("ตกลง",null).show();return;}
        long ramBudget=1024;try{ramBudget=Long.parseLong(ramBudgetInput==null?"1024":ramBudgetInput.getText().toString().trim());if(ramBudget<512||ramBudget>8192)throw new NumberFormatException();}catch(Exception e){message("งบประมาณ RAM ต้องอยู่ระหว่าง 512–8192 MB");return;}
        StringBuilder text=new StringBuilder();for(Map.Entry<String,String> entry:values.entrySet())text.append(entry.getKey()).append('=').append(entry.getValue().replace("\n","").replace("\r","")).append('\n');
        try{if(!bdsFilesDir.isDirectory())throw new IOException("ยังไม่มีโฟลเดอร์เซิร์ฟเวอร์");java.nio.file.Files.write(new File(bdsFilesDir,"server.properties").toPath(),text.toString().getBytes(StandardCharsets.UTF_8));getSharedPreferences("server",MODE_PRIVATE).edit().putLong("ram_budget_mb",ramBudget).apply();new AlertDialog.Builder(this).setMessage("บันทึกแล้ว · การตั้งค่าจะมีผลเมื่อเริ่ม BDS ใหม่").setPositiveButton("ตกลง",null).show();}
        catch(Exception e){new AlertDialog.Builder(this).setMessage("บันทึกไม่สำเร็จ: "+e.getMessage()).setPositiveButton("ตกลง",null).show();}
    }
    private String validateSettings(Map<String,String> v){
        String name=v.getOrDefault("server-name","");if(name.isEmpty()||name.indexOf(';')>=0)return "ชื่อเซิร์ฟเวอร์ต้องไม่ว่างและห้ามมี ;";
        String level=v.getOrDefault("level-name","");if(level.isEmpty()||level.contains("/")||level.contains("\\\\")||level.equals(".")||level.equals(".."))return "ชื่อโลกต้องเป็นชื่อโฟลเดอร์ที่ถูกต้อง";
        String[] numeric={"max-players","server-port","server-portv6","view-distance","tick-distance","player-idle-timeout","max-threads","compression-threshold","force-inbound-debug-port","script-debugger-auto-attach-timeout"};
        for(String key:numeric){try{long n=Long.parseLong(v.getOrDefault(key,""));long min=0,max=Long.MAX_VALUE;if(key.equals("max-players")||key.equals("max-threads")){min=1;}else if(key.equals("server-port")||key.equals("server-portv6")||key.equals("force-inbound-debug-port")){min=1;max=65535;}else if(key.equals("view-distance")){min=5;}else if(key.equals("tick-distance")){min=4;max=12;}else if(key.equals("compression-threshold")){max=65535;}if(n<min||n>max)return key+" ต้องอยู่ในช่วง "+min+"–"+max;}catch(Exception e){return key+" ต้องเป็นจำนวนเต็ม";}}
        for(String key:new String[]{"server-authoritative-block-breaking-range-scalar","player-position-acceptance-threshold","player-movement-action-direction-threshold","server-build-radius-ratio"}){String value=v.getOrDefault(key,"");if(key.equals("server-build-radius-ratio")&&value.equalsIgnoreCase("Disabled"))continue;try{double n=Double.parseDouble(value);if(!Double.isFinite(n))throw new NumberFormatException();if(key.equals("server-authoritative-block-breaking-range-scalar")&&n<=1.0)return key+" ต้องมากกว่า 1.0";if(key.equals("player-movement-action-direction-threshold")&&(n<0||n>1))return key+" ต้องอยู่ระหว่าง 0 ถึง 1";if(key.equals("server-build-radius-ratio")&&(n<0||n>1))return key+" ต้องเป็น Disabled หรือ 0 ถึง 1";}catch(Exception e){return key+" ต้องเป็นตัวเลขที่ถูกต้อง";}}
        return null;
    }private void sendCommand(){String cmd=commandInput.getText().toString().trim();if(cmd.isEmpty())return;Intent i=new Intent(this,ServerService.class).setAction(ServerService.ACTION_COMMAND).putExtra(ServerService.EXTRA_MESSAGE,cmd);startService(i);ServerInstaller.appendLog(logFile,"> "+cmd);commandInput.setText("");refreshLogs();}
    private void refreshLogs(){
        if(logs==null)return;
        boolean followTail=logsScroll!=null&&(!logsHasRendered||logsScroll.getChildAt(0).getBottom()<=logsScroll.getScrollY()+logsScroll.getHeight()+dp(24));
        String source=ServerInstaller.readLog(logFile);String query=logSearch==null?"":logSearch.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);int type=logFilter==null?0:logFilter.getSelectedItemPosition();StringBuilder shown=new StringBuilder();String[] lines=source.split("\\r?\\n");for(String line:lines){String lower=line.toLowerCase(java.util.Locale.ROOT);if(!query.isEmpty()&&!lower.contains(query))continue;boolean error=lower.contains("error")||lower.contains("failed")||lower.contains("ไม่สำเร็จ")||lower.contains("ผิดพลาด");boolean warning=lower.contains("warn")||lower.contains("คำเตือน");boolean command=line.startsWith("> ");if(type==1&&!error||type==2&&!warning||type==3&&!command||type==4&&(error||warning||command))continue;if(shown.length()>0)shown.append('\n');shown.append(line);}logs.setText(shown.length()==0?"ไม่พบรายการที่ตรงกับตัวกรอง":shown.toString());logsHasRendered=true;
        if(followTail&&logsScroll!=null)logsScroll.post(()->logsScroll.scrollTo(0,logs.getBottom()));
    }
    private boolean isSafeBdsFile(File file){try{if(java.nio.file.Files.isSymbolicLink(file.toPath()))return false;String root=bdsFilesDir.getCanonicalPath()+File.separator;return file.getCanonicalPath().startsWith(root);}catch(Exception e){return false;}}
    private void refreshFileList(){if(filePath==null)return;LinearLayout list=(LinearLayout)pages.get(3).findViewWithTag("file-list");list.removeAllViews();if(!isSafeBdsFile(currentFolder)&&!currentFolder.equals(bdsFilesDir)){currentFolder=bdsFilesDir;message("ป้องกันการเข้าถึงพาธนอกโฟลเดอร์ BDS");}filePath.setText(currentFolder.getAbsolutePath().replace(bdsFilesDir.getAbsolutePath(),"BDS"));if(!bdsFilesDir.exists()){list.addView(label("ติดตั้ง BDS ก่อนจึงจะจัดการไฟล์ได้",14,muted,false));return;}File[] files=currentFolder.listFiles();if(files==null){list.addView(label("ไม่สามารถอ่านโฟลเดอร์นี้ได้",14,muted,false));return;}java.util.Arrays.sort(files,(a,b)->{if(a.isDirectory()!=b.isDirectory())return a.isDirectory()?-1:1;return a.getName().compareToIgnoreCase(b.getName());});for(File f:files){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(12),dp(8),dp(8),dp(8));row.setBackground(shape(panel,18,outline));boolean safe=isSafeBdsFile(f);TextView name=label((f.isDirectory()?"โฟลเดอร์  ":"ไฟล์  ")+f.getName()+(safe?"":"  · ลิงก์ที่ถูกบล็อก"),14,safe?fg:muted,true);row.addView(name,new LinearLayout.LayoutParams(0,dp(48),1));row.setOnClickListener(v->{if(!safe){message("แอปไม่เปิดหรือลบ symlink เพื่อป้องกันการออกนอกโฟลเดอร์ BDS");return;}if(f.isDirectory()){currentFolder=f;refreshFileList();}else editFile(f);});row.setOnLongClickListener(v->{if(!safe){message("แอปบล็อก symlink เพื่อความปลอดภัย");return true;}if(f.isDirectory()){new AlertDialog.Builder(this).setMessage("โฟลเดอร์จะไม่ถูกลบจากหน้านี้").setPositiveButton("ตกลง",null).show();}else new AlertDialog.Builder(this).setTitle("ลบไฟล์").setMessage("ลบ "+f.getName()+" หรือไม่?").setNegativeButton("ยกเลิก",null).setPositiveButton("ลบ",(d,w)->{if(isSafeBdsFile(f)&&f.delete())refreshFileList();else new AlertDialog.Builder(this).setMessage("ลบไฟล์ไม่สำเร็จ หรือพาธไม่ปลอดภัย").setPositiveButton("ตกลง",null).show();}).show();return true;});list.addView(row,top(5));}}
    private void editFile(File file){if(!isSafeBdsFile(file)){message("พาธไฟล์ไม่ปลอดภัย");return;}if(file.length()>512*1024){new AlertDialog.Builder(this).setMessage("ไฟล์ใหญ่เกินกว่าจะแก้ในแอปได้").setPositiveButton("ตกลง",null).show();return;}String initial="";try{initial=new String(java.nio.file.Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);}catch(Exception ignored){}EditText edit=new EditText(this);edit.setText(initial);edit.setTextColor(fg);edit.setGravity(Gravity.TOP);edit.setMinLines(10);edit.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);new AlertDialog.Builder(this).setTitle(file.getName()).setView(edit).setPositiveButton("บันทึก",(d,w)->{try{if(!isSafeBdsFile(file))throw new IOException("พาธไฟล์ไม่ปลอดภัย");java.nio.file.Files.write(file.toPath(),edit.getText().toString().getBytes(StandardCharsets.UTF_8));refreshFileList();}catch(Exception e){new AlertDialog.Builder(this).setMessage("บันทึกไม่สำเร็จ: "+e.getMessage()).setPositiveButton("ตกลง",null).show();}}).setNegativeButton("ปิด",null).show();}

    private void sampleStats(){
        if(root==null)return;
        long now=System.currentTimeMillis();
        int[] pids=serverProcessTree(ServerService.metricsRootPid());
        long cpuTicks=readCpuTicks(pids);
        float cpu=0f;
        if(lastSampleTime>0&&pids.length>0){long elapsed=Math.max(1,now-lastSampleTime);cpu=Math.max(0f,(cpuTicks-lastCpuTicks)*1000f/(elapsed*Math.max(1,Runtime.getRuntime().availableProcessors())));}
        lastCpuTicks=cpuTicks;lastSampleTime=now;
        long rssKb=0;for(int pid:pids)rssKb+=readResidentKb(pid);int ramMb=(int)Math.max(0,rssKb/1024);
        push(cpuSamples,cpu);push(ramSamples,ramMb);
        if(cpuValue!=null)cpuValue.setText(String.format(java.util.Locale.ROOT,"CPU เซิร์ฟเวอร์  %.1f%% · %d cores",cpu,Runtime.getRuntime().availableProcessors()));
        if(ramValue!=null)ramValue.setText("RAM เซิร์ฟเวอร์  "+ramMb+" MB (RSS)");
        String log=ServerInstaller.readLog(logFile);
        boolean tpsEnabled=getSharedPreferences("server",MODE_PRIVATE).getBoolean("tps_enabled",false);
        java.util.regex.Matcher tpsMatch=java.util.regex.Pattern.compile("BEDROCKPOCKET_TPS:([0-9]+(?:\\.[0-9]+)?)").matcher(log);
        String latestTps=null;while(tpsMatch.find())latestTps=tpsMatch.group(1);
        if(!tpsEnabled){if(tpsValue!=null)tpsValue.setText("TPS  — · เปิดตัววัดในเครื่องมือ");if(performanceTpsValue!=null)performanceTpsValue.setText("TPS  — · เปิดตัววัดในเครื่องมือ");}
        else if(latestTps!=null&&"RUNNING".equals(serverState)){
            float currentTps;try{currentTps=Float.parseFloat(latestTps);}catch(NumberFormatException e){currentTps=-1f;}
            if(currentTps>=0){push(tpsSamples,currentTps);String value=String.format(java.util.Locale.ROOT,"%.1f",currentTps);if(tpsValue!=null)tpsValue.setText("TPS  "+value+" / 20");if(performanceTpsValue!=null)performanceTpsValue.setText("TPS  "+value+" / 20  ·  เฉลี่ย "+average(tpsSamples)+" / 20");}
        }else{if(tpsValue!=null)tpsValue.setText("TPS  รอข้อมูลจาก Script API");if(performanceTpsValue!=null)performanceTpsValue.setText("TPS  รอข้อมูลจาก Script API");}
        if("RUNNING".equals(serverState)){int online=onlinePlayerCount();push(playerSamples,online);if(performancePlayersValue!=null)performancePlayersValue.setText("ผู้เล่นออนไลน์  "+online+" คน  ·  สูงสุด "+maximum(playerSamples));}
        else if(performancePlayersValue!=null)performancePlayersValue.setText("ผู้เล่นออนไลน์  — · เซิร์ฟเวอร์หยุดอยู่");
        updateDeviceStatus();
        updateHomeDashboard();
        if(playitTrafficValue!=null)playitTrafficValue.setText(PlayitService.hasMetrics()?"รับ "+formatPlayitMb(PlayitService.currentBytesIn())+" MB  ·  ส่ง "+formatPlayitMb(PlayitService.currentBytesOut())+" MB":"รับ — MB  ·  ส่ง — MB · รอ Playit Agent");
        if(cpuChartView!=null)cpuChartView.invalidate();if(ramChartView!=null)ramChartView.invalidate();if(tpsChartView!=null)tpsChartView.invalidate();if(playersChartView!=null)playersChartView.invalidate();
    }
    private String average(ArrayDeque<Float> values){if(values.isEmpty())return "—";float sum=0;for(Float value:values)sum+=value;return String.format(java.util.Locale.ROOT,"%.1f",sum/values.size());}
    private int maximum(ArrayDeque<Integer> values){int max=0;for(Integer value:values)max=Math.max(max,value);return max;}
    private void updateDeviceStatus(){
        if(deviceStatusValue==null)return;
        Double cpuTemperature=readCpuTemperature();
        if(cpuTempValue!=null)cpuTempValue.setText(cpuTemperature==null?"อุณหภูมิ CPU  อ่านไม่ได้บนเครื่องนี้":"อุณหภูมิ CPU  "+String.format(java.util.Locale.ROOT,"%.1f°C",cpuTemperature));
        Intent battery=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if(battery==null){deviceStatusValue.setText("อ่านระดับแบตเตอรี่ไม่ได้");if(batteryTempValue!=null)batteryTempValue.setText("อุณหภูมิแบตเตอรี่  อ่านไม่ได้");return;}
        int level=battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=battery.getIntExtra(BatteryManager.EXTRA_SCALE,-1);
        int temperature=battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,-1);
        String percent=level>=0&&scale>0?Math.round(level*100f/scale)+"%":"—";
        String temp=temperature>=0?String.format(java.util.Locale.ROOT,"%.1f°C",temperature/10f):"อ่านไม่ได้";
        if(batteryTempValue!=null)batteryTempValue.setText("อุณหภูมิแบตเตอรี่  "+temp);
        deviceStatusValue.setText("ระดับแบตเตอรี่  "+percent);
    }
    private Double readCpuTemperature(){File rootThermal=new File("/sys/class/thermal");File[] zones=rootThermal.listFiles((dir,name)->name.startsWith("thermal_zone"));if(zones==null)return null;Double hottest=null;for(File zone:zones){try{File typeFile=new File(zone,"type"),tempFile=new File(zone,"temp");if(!typeFile.canRead()||!tempFile.canRead())continue;String type=new String(java.nio.file.Files.readAllBytes(typeFile.toPath()),StandardCharsets.UTF_8).trim().toLowerCase(java.util.Locale.ROOT);if(!(type.contains("cpu")||type.contains("ap_therm")||type.contains("soc_therm")||type.contains("exynos-therm")))continue;String raw=new String(java.nio.file.Files.readAllBytes(tempFile.toPath()),StandardCharsets.UTF_8).trim();double value=Double.parseDouble(raw);if(value>1000)value/=1000d;else if(value>200)value/=10d;if(value < -20 || value > 150)continue;if(hottest==null||value>hottest)hottest=value;}catch(Exception ignored){}}return hottest;}
    private void updateHomeDashboard(){if(uptimeValue==null)return;android.content.SharedPreferences prefs=getSharedPreferences("server",MODE_PRIVATE);long started=prefs.getLong("bds_started_at",0);if("RUNNING".equals(serverState)&&started>0)uptimeValue.setText("Uptime  "+formatDuration(Math.max(0,System.currentTimeMillis()-started)));else uptimeValue.setText("Uptime  — · เซิร์ฟเวอร์หยุดอยู่");int online="RUNNING".equals(serverState)?onlinePlayerCount():0;int maxPlayers=10;try{maxPlayers=Integer.parseInt(readServerProperty("max-players","10"));}catch(Exception ignored){}if(playerSummaryValue!=null)playerSummaryValue.setText("ผู้เล่น  "+online+" / "+maxPlayers);if(diskSpaceValue!=null){File target=bdsFilesDir.exists()?bdsFilesDir:serverDir;diskSpaceValue.setText("พื้นที่ว่าง  "+formatStorage(target.getUsableSpace()));}if(systemRamValue!=null){android.app.ActivityManager.MemoryInfo mi=new android.app.ActivityManager.MemoryInfo();((android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);systemRamValue.setText("RAM เครื่องว่าง  "+formatStorage(mi.availMem)+" / "+formatStorage(mi.totalMem)+(mi.lowMemory?" · Android แจ้งว่า RAM ต่ำ":""));}if(activeWorldValue!=null){try{activeWorldValue.setText("โลก  "+WorldManager.activeName(bdsFilesDir));}catch(Exception e){activeWorldValue.setText("โลก  —");}}String deviceIp=localAddress();if(deviceIpValue!=null)deviceIpValue.setText("IP เครื่อง  "+deviceIp);if(copyDeviceIpButton!=null)copyDeviceIpButton.setEnabled(!deviceIp.startsWith("ดู IP"));String tunnel=PlayitService.currentTunnelAddress();if(playitIpValue!=null)playitIpValue.setText(tunnel==null||tunnel.trim().isEmpty()?"Playit IP  — · ยังไม่เชื่อมต่อ":"Playit IP  "+tunnel.replace("\n","\n              "));if(copyPlayitIpButton!=null)copyPlayitIpButton.setEnabled(tunnel!=null&&!tunnel.trim().isEmpty());if(lastEventValue!=null){String event=prefs.getString("last_server_event","ยังไม่มีข้อมูล");long when=prefs.getLong("last_server_event_at",0);String time=when>0?new java.text.SimpleDateFormat("MM-dd HH:mm",java.util.Locale.getDefault()).format(new java.util.Date(when)):"";lastEventValue.setText("เหตุการณ์ล่าสุด  "+event+(time.isEmpty()?"":" · "+time));}}
    private String formatDuration(long millis){long seconds=millis/1000,days=seconds/86400,hours=(seconds%86400)/3600,minutes=(seconds%3600)/60;return days>0?days+" วัน "+hours+" ชม.":hours>0?hours+" ชม. "+minutes+" นาที":minutes+" นาที";}
    private String formatStorage(long bytes){if(bytes<0)return "อ่านไม่ได้";if(bytes>=1024L*1024*1024)return String.format(java.util.Locale.ROOT,"%.1f GB",bytes/(1024d*1024*1024));return String.format(java.util.Locale.ROOT,"%.0f MB",bytes/(1024d*1024));}
    private String readServerProperty(String key,String fallback)throws IOException{File props=new File(bdsFilesDir,"server.properties");if(!props.isFile())return fallback;for(String line:java.nio.file.Files.readAllLines(props.toPath(),StandardCharsets.UTF_8))if(line.startsWith(key+"="))return line.substring(key.length()+1).trim();return fallback;}
    private void recordServerEvent(String next,String message){android.content.SharedPreferences prefs=getSharedPreferences("server",MODE_PRIVATE);String previous=serverState;long now=System.currentTimeMillis();android.content.SharedPreferences.Editor edit=prefs.edit();if("RUNNING".equals(next)){if(!"RUNNING".equals(previous)){long started=prefs.getLong("bds_started_at",0);if(started==0)edit.putLong("bds_started_at",now);edit.putString("last_server_event","BDS ออนไลน์").putLong("last_server_event_at",now);}}else if("ERROR".equals(next)){edit.putLong("bds_started_at",0).putString("last_server_event","ผิดพลาด: "+(message==null?"เริ่มเซิร์ฟเวอร์ไม่สำเร็จ":message)).putLong("last_server_event_at",now);}else if(("STOPPED".equals(next)||"INSTALLED".equals(next))&&("RUNNING".equals(previous)||"STOPPING".equals(previous))){edit.putLong("bds_started_at",0).putString("last_server_event","BDS หยุดแล้ว").putLong("last_server_event_at",now);}edit.apply();}
    private int[] serverProcessTree(int rootPid){
        if(rootPid<=0)return new int[0];
        ArrayList<Integer> pids=new ArrayList<>();java.util.HashSet<Integer> seen=new java.util.HashSet<>();seen.add(rootPid);pids.add(rootPid);
        File[] procEntries=new File("/proc").listFiles();if(procEntries==null)return new int[]{rootPid};
        for(int pass=0;pass<16&&pids.size()<64;pass++){boolean added=false;for(File entry:procEntries){if(!entry.isDirectory()||!entry.getName().matches("\\d+"))continue;int pid;try{pid=Integer.parseInt(entry.getName());}catch(NumberFormatException ignored){continue;}if(seen.contains(pid))continue;int parent=readParentPid(pid);if(seen.contains(parent)){seen.add(pid);pids.add(pid);added=true;if(pids.size()>=64)break;}}if(!added)break;}
        int[] result=new int[pids.size()];for(int i=0;i<pids.size();i++)result[i]=pids.get(i);return result;
    }
    private int readParentPid(int pid){File stat=new File("/proc/"+pid+"/stat");try(BufferedReader reader=new BufferedReader(new FileReader(stat))){String line=reader.readLine();if(line==null)return -1;int end=line.lastIndexOf(')');if(end<0)return -1;String[] fields=line.substring(end+1).trim().split("\\s+");return fields.length>1?Integer.parseInt(fields[1]):-1;}catch(Exception ignored){return -1;}}
    private long readResidentKb(int pid){File status=new File("/proc/"+pid+"/status");try(BufferedReader reader=new BufferedReader(new FileReader(status))){String line;while((line=reader.readLine())!=null)if(line.startsWith("VmRSS:")){String[] values=line.trim().split("\\s+");return values.length>1?Long.parseLong(values[1]):0;}}catch(Exception ignored){}return 0;}
    private long readCpuTicks(int[] pids){long total=0;for(int pid:pids){File stat=new File("/proc/"+pid+"/stat");try(BufferedReader reader=new BufferedReader(new FileReader(stat))){String line=reader.readLine();if(line==null)continue;int end=line.lastIndexOf(')');if(end<0)continue;String[] fields=line.substring(end+1).trim().split("\\s+");if(fields.length>12)total+=Long.parseLong(fields[11])+Long.parseLong(fields[12]);}catch(Exception ignored){}}return total;}
    private <T> void push(ArrayDeque<T> q,T value){if(q.size()>=50)q.removeFirst();q.addLast(value);}
    private final class ChartView extends View{
        final ArrayDeque<? extends Number> values;final Paint paint=new Paint(3);final int color;final String label;
        ChartView(Context c,ArrayDeque<? extends Number> v,int color,String label){super(c);values=v;this.color=color;this.label=label;setContentDescription(label+" usage chart");}
        protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight(),left=dp(2),top=dp(8),bottom=h-dp(18),max=0f;for(Number value:values)max=Math.max(max,value.floatValue());float scale;
            if("CPU".equals(label))scale=Math.max(5f,(float)(Math.ceil(max/5f)*5f));else if("TPS".equals(label))scale=20f;else if("PLAYERS".equals(label))scale=Math.max(1f,(float)Math.ceil(max));else scale=Math.max(128f,(float)(Math.ceil(max/128f)*128f));
            paint.setColor(Color.rgb(51,65,85));paint.setStrokeWidth(dp(1));for(int i=0;i<3;i++){float y=top+(bottom-top)*i/2f;c.drawLine(left,y,w-left,y,paint);}
            paint.setColor(muted);paint.setTextSize(dp(10));String topLabel="CPU".equals(label)?String.format(java.util.Locale.ROOT,"%.0f%%",scale):"TPS".equals(label)?"20 TPS":"PLAYERS".equals(label)?String.format(java.util.Locale.ROOT,"%.0f คน",scale):String.format(java.util.Locale.ROOT,"%.0f MB",scale);c.drawText(topLabel,left,top+dp(10),paint);c.drawText("0",left,bottom,paint);
            if(values.size()<2)return;paint.setColor(color);paint.setStrokeWidth(dp(2));paint.setStyle(Paint.Style.STROKE);Path path=new Path();int i=0,n=values.size();for(Number sample:values){float x=left+(w-left*2)*i++/(n-1f),y=bottom-(bottom-top)*Math.max(0f,Math.min(scale,sample.floatValue()))/scale;if(i==1)path.moveTo(x,y);else path.lineTo(x,y);}c.drawPath(path,paint);paint.setStyle(Paint.Style.FILL);
        }
    }

    private final class NavItemView extends View{
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final int kind;private final String text;private boolean selected;
        NavItemView(Context context,int kind,String text){super(context);this.kind=kind;this.text=text;setContentDescription(text);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);}
        @Override public void setSelected(boolean value){super.setSelected(value);selected=value;invalidate();}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);int color=selected?accent:muted;paint.setColor(color);paint.setStrokeWidth(dp(2));paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStyle(Paint.Style.STROKE);float x=getWidth()/2f,y=dp(20);Path path=new Path();
            if(kind==0){path.moveTo(x-dp(10),y+dp(1));path.lineTo(x,y-dp(8));path.lineTo(x+dp(10),y+dp(1));path.moveTo(x-dp(7),y);path.lineTo(x-dp(7),y+dp(10));path.lineTo(x+dp(7),y+dp(10));path.lineTo(x+dp(7),y);canvas.drawPath(path,paint);canvas.drawRect(x-dp(2),y+dp(4),x+dp(2),y+dp(10),paint);}
            else if(kind==1){canvas.drawRoundRect(x-dp(10),y-dp(8),x+dp(10),y+dp(8),dp(2),dp(2),paint);path.moveTo(x-dp(5),y-dp(3));path.lineTo(x-dp(1),y);path.lineTo(x-dp(5),y+dp(3));canvas.drawPath(path,paint);canvas.drawLine(x+dp(2),y+dp(4),x+dp(6),y+dp(4),paint);}
            else if(kind==3){path.moveTo(x-dp(10),y-dp(5));path.lineTo(x-dp(2),y-dp(5));path.lineTo(x+dp(1),y-dp(1));path.lineTo(x+dp(10),y-dp(1));path.lineTo(x+dp(9),y+dp(9));path.lineTo(x-dp(10),y+dp(9));path.close();canvas.drawPath(path,paint);}
            else{canvas.drawCircle(x,y,dp(5),paint);for(int i=0;i<8;i++){double a=Math.PI*i/4;float x1=x+(float)Math.cos(a)*dp(7),y1=y+(float)Math.sin(a)*dp(7),x2=x+(float)Math.cos(a)*dp(10),y2=y+(float)Math.sin(a)*dp(10);canvas.drawLine(x1,y1,x2,y2,paint);}}
            paint.setStyle(Paint.Style.FILL);paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));paint.setTextSize(dp(10));paint.setTextAlign(Paint.Align.CENTER);canvas.drawText(text,x,getHeight()-dp(6),paint);
        }
    }

    private LinearLayout card(LinearLayout p){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(16),dp(16),dp(16),dp(14));c.setBackground(shape(panel,24,outline));p.addView(c,top(12));return c;}
    private void applyDynamicColors(){if(Build.VERSION.SDK_INT<31)return;bg=systemColor("system_neutral1_900",bg);panel=systemColor("system_neutral1_800",panel);panelVariant=systemColor("system_neutral2_700",panelVariant);fg=systemColor("system_neutral1_100",fg);muted=systemColor("system_neutral2_300",muted);outline=systemColor("system_neutral2_600",outline);}
    private int systemColor(String name,int fallback){try{int id=getResources().getIdentifier(name,"color","android");return id==0?fallback:getResources().getColor(id,getTheme());}catch(Exception ignored){return fallback;}}
    private GradientDrawable shape(int color,int radius,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(stroke!=0)d.setStroke(dp(1),stroke);return d;}
    private void applyMaterialStyles(View view){
        if(view instanceof Button){Button b=(Button)view;b.setAllCaps(false);b.setTextSize(14);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setTextColor(fg);GradientDrawable bgShape=shape(panelVariant,24,outline);b.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(38,255,255,255)),bgShape,null));b.setMinHeight(dp(48));b.setPadding(dp(18),dp(8),dp(18),dp(8));}
        else if(view instanceof EditText){EditText e=(EditText)view;e.setTextColor(fg);e.setHintTextColor(muted);e.setBackground(shape(panelVariant,12,outline));e.setPadding(dp(14),dp(8),dp(14),dp(8));e.setMinHeight(dp(52));}
        else if(view instanceof CheckBox){CheckBox c=(CheckBox)view;c.setTextColor(fg);c.setButtonTintList(ColorStateList.valueOf(accent));}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)applyMaterialStyles(group.getChildAt(i));}
    }
    private void stylePrimaryButton(Button button){if(button==null)return;button.setTextColor(onAccent);button.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(48,255,255,255)),shape(accent,28,0),null));button.setMinHeight(dp(58));}
    private TextView label(String s,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private LinearLayout.LayoutParams top(int n){LinearLayout.LayoutParams l=new LinearLayout.LayoutParams(-1,-2);l.topMargin=dp(n);return l;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private String localAddress(){InetAddress address=DashboardSecurity.primaryLanAddress();return address==null?"ดู IP ใน Wi-Fi":address.getHostAddress();}
}
