package dev.codex.bedrockpocket;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/** Optional password-protected, HTTPS-only dashboard restricted to private LAN clients. */
public final class LanDashboardService extends Service {
    static final String ACTION_START = "dev.codex.bedrockpocket.LAN_DASHBOARD_START";
    static final String ACTION_STOP = "dev.codex.bedrockpocket.LAN_DASHBOARD_STOP";
    static final String ACTION_RECONFIGURE = "dev.codex.bedrockpocket.LAN_DASHBOARD_RECONFIGURE";
    static final int PORT = 8765;
    private static final int NOTIFICATION_ID = 2403;
    private static final int MAX_HEADER_BYTES = 8192;
    private static final int MAX_BODY_BYTES = 16384;
    private static final long SESSION_IDLE_MS = 30L * 60L * 1000L;
    private static final long LOGIN_LOCK_MS = 15L * 60L * 1000L;
    private static final int MAX_LOGIN_FAILURES = 5;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, Failures> failures = Collections.synchronizedMap(new HashMap<>());
    private volatile SSLServerSocket listener;
    private volatile Thread acceptThread;
    private volatile ThreadPoolExecutor workers;
    private volatile boolean startupFailed;

    private static final class Session {
        final String id, csrf, address;
        volatile long lastSeen;
        Session(String id, String csrf, String address) { this.id=id;this.csrf=csrf;this.address=address;this.lastSeen=System.currentTimeMillis(); }
    }
    private static final class Failures { int count; long blockedUntil; }
    private static final class Request { String method, path, body; Map<String,String> headers=new HashMap<>(); }

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        String action=intent==null?ACTION_START:intent.getAction();
        if(ACTION_STOP.equals(action)){getSharedPreferences(DashboardSecurity.PREFS,MODE_PRIVATE).edit().putBoolean("enabled",false).apply();stopDashboard();stopSelf();return START_NOT_STICKY;}
        if(ACTION_RECONFIGURE.equals(action)){stopDashboard();if(getSharedPreferences(DashboardSecurity.PREFS,MODE_PRIVATE).getBoolean("enabled",false)&&DashboardSecurity.hasPassword(this)){startForeground(NOTIFICATION_ID,notification("กำลังเปิด Web Dashboard แบบ HTTPS"));startDashboard();}return START_STICKY;}
        if(!getSharedPreferences(DashboardSecurity.PREFS,MODE_PRIVATE).getBoolean("enabled",false)||!DashboardSecurity.hasPassword(this)){stopSelf(startId);return START_NOT_STICKY;}
        if(listener==null){startForeground(NOTIFICATION_ID,notification("กำลังเปิด Web Dashboard แบบ HTTPS"));startDashboard();}
        return START_STICKY;
    }

    private void startDashboard() {
        acceptThread=new Thread(()->{
            SSLServerSocket server=null;
            try {
                DashboardSecurity.TlsMaterial material=DashboardSecurity.createTlsMaterial();
                SSLContext tls=material.context;
                javax.net.ssl.SSLServerSocketFactory factory=tls.getServerSocketFactory();
                server=(SSLServerSocket)factory.createServerSocket();
                server.setReuseAddress(false);server.setEnabledProtocols(new String[]{"TLSv1.2"});
                listener=server;
                InetAddress bindAddress=DashboardSecurity.primaryLanAddress();
                if(bindAddress==null)throw new java.io.IOException("เชื่อมต่อ Wi-Fi หรือ LAN ก่อนเปิด Dashboard");
                server.bind(new InetSocketAddress(bindAddress,PORT),8);
                String fingerprint=fingerprint(material.certificate.getEncoded());
                String urls=buildLanUrls();
                SharedPreferences prefs=getSharedPreferences(DashboardSecurity.PREFS,MODE_PRIVATE);
                prefs.edit().putString("fingerprint",fingerprint).putString("urls",urls).putString("runtime","พร้อมใช้งาน").apply();
                workers=new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(12));
                while(!server.isClosed()) {
                    Socket socket=server.accept();
                    if(!DashboardSecurity.isPrivateAddress(socket.getInetAddress())){socket.close();continue;}
                    try{workers.execute(()->handleSocket((SSLSocket)socket));}catch(Exception e){socket.close();}
                }
            } catch(Exception e) {
                if(server!=null&&server.isClosed())return;
                startupFailed=true;
                closeNetwork();
                getSharedPreferences(DashboardSecurity.PREFS,MODE_PRIVATE).edit().putString("runtime","เปิดไม่ได้: "+safeError(e)).apply();
                stopForeground(true);stopSelf();
            }
        },"BedrockPocket-LAN-HTTPS");
        acceptThread.start();
    }

    private void handleSocket(SSLSocket socket) {
        try(SSLSocket s=socket){s.setSoTimeout(8000);s.startHandshake();Request request=readRequest(s.getInputStream());if(request==null)return;route(s,request);}
        catch(Exception ignored){try{socket.close();}catch(Exception ignoredAgain){}}
    }

    private Request readRequest(InputStream in) throws Exception {
        String first=readLine(in,MAX_HEADER_BYTES);if(first==null||first.isEmpty())return null;
        String[] parts=first.split(" ");if(parts.length<2)return null;Request r=new Request();r.method=parts[0].toUpperCase(Locale.ROOT);r.path=parts[1].split("\\?",2)[0];
        int total=first.length();String line;while((line=readLine(in,MAX_HEADER_BYTES-total))!=null&&!line.isEmpty()){total+=line.length()+2;if(total>MAX_HEADER_BYTES)throw new java.io.IOException("headers too large");int colon=line.indexOf(':');if(colon>0)r.headers.put(line.substring(0,colon).trim().toLowerCase(Locale.ROOT),line.substring(colon+1).trim());}
        int length=0;try{length=Integer.parseInt(r.headers.getOrDefault("content-length","0"));}catch(Exception e){throw new java.io.IOException("invalid content length");}
        if(length<0||length>MAX_BODY_BYTES)throw new java.io.IOException("request too large");byte[] body=new byte[length];int at=0;while(at<length){int n=in.read(body,at,length-at);if(n<0)throw new java.io.IOException("incomplete body");at+=n;}r.body=new String(body,StandardCharsets.UTF_8);return r;
    }
    private String readLine(InputStream in,int max) throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();while(out.size()<=max){int b=in.read();if(b<0)return out.size()==0?null:out.toString("UTF-8");if(b=='\n')break;if(b!='\r')out.write(b);}if(out.size()>max)throw new java.io.IOException("line too long");return out.toString("UTF-8");}

    private void route(SSLSocket socket,Request r) throws Exception {
        String remote=socket.getInetAddress().getHostAddress();
        if("GET".equals(r.method)&&"/".equals(r.path)){Session session=findSession(r,remote);send(socket,200,"text/html; charset=utf-8",session==null?loginPage(""):dashboardPage(session),null);return;}
        if("POST".equals(r.method)&&"/login".equals(r.path)){login(socket,r,remote);return;}
        Session session=findSession(r,remote);if(session==null){send(socket,401,"application/json; charset=utf-8","{\"error\":\"unauthorized\"}",null);return;}
        if("POST".equals(r.method)&&"/logout".equals(r.path)){if(!csrfMatches(r,session)){send(socket,403,"text/plain; charset=utf-8","คำขอไม่ถูกต้อง",null);return;}sessions.remove(session.id);send(socket,303,"text/plain; charset=utf-8","ออกจากระบบแล้ว",map("Location","/","Set-Cookie","BP_SESSION=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Strict"));return;}
        if("GET".equals(r.method)&&"/api/status".equals(r.path)){send(socket,200,"application/json; charset=utf-8",statusJson(),null);return;}
        if("GET".equals(r.method)&&"/api/logs".equals(r.path)){send(socket,200,"application/json; charset=utf-8","{\"logs\":"+json(lastLogs())+"}",null);return;}
        if("POST".equals(r.method)&&"/api/power".equals(r.path)){if(!csrfMatches(r,session)){send(socket,403,"application/json; charset=utf-8","{\"error\":\"csrf\"}",null);return;}String action=form(r.body).get("action");if(!"start".equals(action)&&!"stop".equals(action)){send(socket,400,"application/json; charset=utf-8","{\"error\":\"invalid action\"}",null);return;}control(action,null);send(socket,202,"application/json; charset=utf-8","{\"ok\":true}",null);return;}
        if("POST".equals(r.method)&&"/api/command".equals(r.path)){if(!csrfMatches(r,session)){send(socket,403,"application/json; charset=utf-8","{\"error\":\"csrf\"}",null);return;}String command=form(r.body).get("command");if(command==null||(command=command.trim()).isEmpty()||command.length()>300||hasControls(command)){send(socket,400,"application/json; charset=utf-8","{\"error\":\"invalid command\"}",null);return;}control("command",command);send(socket,202,"application/json; charset=utf-8","{\"ok\":true}",null);return;}
        send(socket,404,"text/plain; charset=utf-8","ไม่พบหน้านี้",null);
    }

    private void login(SSLSocket socket,Request r,String remote) throws Exception {
        String password=form(r.body).get("password");if(password==null||password.length()>128){send(socket,400,"text/html; charset=utf-8",loginPage("รหัสผ่านไม่ถูกต้อง"),null);return;}
        synchronized(failures){if(failures.size()>=256&&!failures.containsKey(remote)){long now=System.currentTimeMillis();failures.entrySet().removeIf(entry->entry.getValue().blockedUntil>0&&entry.getValue().blockedUntil<=now);if(failures.size()>=256){send(socket,429,"text/html; charset=utf-8",loginPage("ลองใหม่ภายหลัง"),null);return;}}}
        Failures state=failures.computeIfAbsent(remote,k->new Failures());long now=System.currentTimeMillis();
        synchronized(state){if(state.blockedUntil>now){send(socket,429,"text/html; charset=utf-8",loginPage("ลองใหม่ภายหลัง"),null);return;}if(state.blockedUntil!=0&&state.blockedUntil<=now){state.count=0;state.blockedUntil=0;}}
        boolean ok=DashboardSecurity.verifyPassword(this,password.toCharArray());
        if(!ok){synchronized(state){state.count++;if(state.count>=MAX_LOGIN_FAILURES){state.blockedUntil=now+LOGIN_LOCK_MS;state.count=0;}}send(socket,401,"text/html; charset=utf-8",loginPage("รหัสผ่านไม่ถูกต้อง"),null);return;}
        failures.remove(remote);byte[] a=new byte[32],b=new byte[32];random.nextBytes(a);random.nextBytes(b);String id=Base64.getUrlEncoder().withoutPadding().encodeToString(a),csrf=Base64.getUrlEncoder().withoutPadding().encodeToString(b);Session session=new Session(id,csrf,remote);
        synchronized(sessions){if(sessions.size()>=8){String oldest=null;long age=Long.MAX_VALUE;for(Session item:sessions.values())if(item.lastSeen<age){age=item.lastSeen;oldest=item.id;}if(oldest!=null)sessions.remove(oldest);}sessions.put(id,session);}
        send(socket,303,"text/plain; charset=utf-8","เข้าสู่ระบบแล้ว",map("Location","/","Set-Cookie","BP_SESSION="+id+"; Path=/; Max-Age=1800; Secure; HttpOnly; SameSite=Strict"));
    }

    private Session findSession(Request r,String remote){String cookie=r.headers.get("cookie");if(cookie==null)return null;for(String part:cookie.split(";")){String[] pair=part.trim().split("=",2);if(pair.length!=2||!"BP_SESSION".equals(pair[0]))continue;Session s=sessions.get(pair[1]);if(s==null||!s.address.equals(remote)||System.currentTimeMillis()-s.lastSeen>SESSION_IDLE_MS){if(s!=null)sessions.remove(s.id);return null;}s.lastSeen=System.currentTimeMillis();return s;}return null;}
    private boolean csrfMatches(Request r,Session s){String value=r.headers.get("x-csrf-token");return value!=null&&java.security.MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8),s.csrf.getBytes(StandardCharsets.UTF_8));}

    private void control(String action,String command){Intent intent=new Intent(this,ServerService.class);if("start".equals(action)){intent.setAction(ServerService.ACTION_START);if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);}else if("stop".equals(action)){intent.setAction(ServerService.ACTION_STOP);startService(intent);}else{intent.setAction(ServerService.ACTION_COMMAND).putExtra(ServerService.EXTRA_MESSAGE,command);startService(intent);}}

    private String statusJson(){SharedPreferences p=getSharedPreferences("server",MODE_PRIVATE);String state=p.getString("server_state","STOPPED"),message=p.getString("server_message","");String world="—";int port=19132;try{File active=ServerProfileManager.activeServerDir(this);String id=ServerProfileManager.activeId(this);world=ServerProfileManager.name(this,id)+" · "+WorldManager.activeName(active);java.io.File props=new java.io.File(active,"server.properties");for(String line:java.nio.file.Files.readAllLines(props.toPath()))if(line.startsWith("server-port=")){port=Integer.parseInt(line.substring(12).trim());break;}}catch(Exception ignored){}return "{\"state\":"+json(state)+",\"message\":"+json(message)+",\"world\":"+json(world)+",\"uptime\":"+Math.max(0,p.getLong("bds_started_at",0))+",\"port\":"+port+"}";}
    private String lastLogs(){File file=new File(getFilesDir(),"app.log");if(!file.isFile())return "ยังไม่มี log";try(java.io.RandomAccessFile f=new java.io.RandomAccessFile(file,"r")){long length=f.length();f.seek(Math.max(0,length-32768));byte[] bytes=new byte[(int)Math.min(length,32768)];f.readFully(bytes);String text=new String(bytes,StandardCharsets.UTF_8);String[] lines=text.split("\\r?\\n");StringBuilder out=new StringBuilder();for(int i=Math.max(0,lines.length-150);i<lines.length;i++)out.append(lines[i]).append('\n');return out.toString();}catch(Exception e){return "อ่าน log ไม่สำเร็จ";}}
    private String dashboardPage(Session session){
        String page = """
                <!doctype html><html lang="th"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>BedrockPocket</title><meta name="referrer" content="no-referrer">
                <style>body{margin:0;background:#111311;color:#f4f6f4;font:16px system-ui,sans-serif}main{max-width:820px;margin:24px auto;padding:16px}.card{background:#1b1d1c;border:1px solid #353a37;border-radius:22px;padding:20px;margin:14px 0}button{background:#32cf75;color:#07180e;border:0;border-radius:14px;padding:13px 18px;font-weight:700;font-size:16px;margin:4px;cursor:pointer}button.secondary{background:#292c2a;color:#f4f6f4;border:1px solid #454a46}input{box-sizing:border-box;background:#111311;color:#f4f6f4;border:1px solid #59625c;border-radius:12px;padding:13px;font-size:16px;width:100%}pre{white-space:pre-wrap;max-height:42vh;overflow:auto;background:#101210;padding:12px;border-radius:12px;color:#c2d2c8}.muted{color:#aeb5b0}h1{color:#32cf75}</style></head><body><main>
                <h1>BedrockPocket</h1><p class="muted">แผงควบคุมในวง LAN · HTTPS</p><section class="card"><h2>สถานะ: <span id="state">กำลังอ่าน…</span></h2><div id="details" class="muted"></div><button onclick="power('start')">เริ่มเซิร์ฟเวอร์</button><button class="secondary" onclick="power('stop')">หยุดเซิร์ฟเวอร์</button></section>
                <section class="card"><h2>Console</h2><form id="cmd"><input id="command" maxlength="300" autocomplete="off" placeholder="คำสั่ง BDS เช่น list หรือ say สวัสดี"><button>ส่งคำสั่ง</button></form></section>
                <section class="card"><h2>Log ล่าสุด</h2><button class="secondary" onclick="loadLogs()">รีเฟรช log</button><pre id="logs">กำลังอ่าน…</pre></section><form method="post" action="/logout"><button class="secondary">ออกจากระบบ</button></form></main>
                <script>const csrf='__CSRF__';async function api(url,opts={}){opts.headers=Object.assign({'X-CSRF-Token':csrf},opts.headers||{});const r=await fetch(url,opts);if(r.status===401||r.status===403){location.reload();throw Error('session expired')}return r}async function refresh(){try{const s=await(await fetch('/api/status')).json();document.getElementById('state').textContent=s.state;document.getElementById('details').textContent=s.message+' · โลก: '+s.world+' · พอร์ต Bedrock: '+s.port;if(s.uptime>0)document.getElementById('details').textContent+=' · เริ่ม '+new Date(s.uptime).toLocaleString()}catch(e){document.getElementById('state').textContent='เชื่อมต่อไม่ได้'}}async function loadLogs(){try{const x=await(await fetch('/api/logs')).json();document.getElementById('logs').textContent=x.logs}catch(e){document.getElementById('logs').textContent='อ่าน log ไม่ได้'}}async function power(action){await api('/api/power',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({action})});setTimeout(refresh,700)}document.getElementById('cmd').addEventListener('submit',async e=>{e.preventDefault();const field=document.getElementById('command');await api('/api/command',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({command:field.value})});field.value='';loadLogs()});document.querySelector('form[action="/logout"]').addEventListener('submit',async e=>{e.preventDefault();await api('/logout',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({csrf})});location.href='/' });refresh();loadLogs();setInterval(refresh,4000);</script></body></html>
                """;
        return page.replace("__CSRF__", session.csrf);
    }
    private String loginPage(String error){return "<!doctype html><html lang=th><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>BedrockPocket Login</title><style>body{margin:0;background:#111311;color:#f4f6f4;font:16px system-ui,sans-serif}main{max-width:420px;margin:12vh auto;padding:18px}.card{background:#1b1d1c;border:1px solid #353a37;border-radius:22px;padding:24px}h1{color:#32cf75}input,button{box-sizing:border-box;width:100%;padding:14px;margin-top:12px;border-radius:12px;font-size:16px}input{background:#111311;color:white;border:1px solid #59625c}button{background:#32cf75;color:#07180e;border:0;font-weight:700}.err{color:#ff9a90}</style></head><body><main><section class=card><h1>BedrockPocket</h1><p>ลงชื่อเข้าใช้เพื่อควบคุมเซิร์ฟเวอร์นี้</p>"+(error.isEmpty()?"":"<p class=err>"+escapeHtml(error)+"</p>")+"<form method=post action=/login><input type=password name=password required maxlength=128 autocomplete=current-password placeholder='รหัสผ่าน Dashboard'><button>เข้าสู่ระบบ</button></form><p>เชื่อมต่อผ่าน HTTPS เท่านั้น</p></section></main></body></html>";}

    private void send(SSLSocket socket,int code,String type,String body,Map<String,String> extra)throws Exception{byte[] bytes=body.getBytes(StandardCharsets.UTF_8);OutputStream out=socket.getOutputStream();String reason=code==200?"OK":code==202?"Accepted":code==303?"See Other":code==400?"Bad Request":code==401?"Unauthorized":code==403?"Forbidden":code==404?"Not Found":code==429?"Too Many Requests":"Error";StringBuilder headers=new StringBuilder("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\nContent-Type: ").append(type).append("\r\nContent-Length: ").append(bytes.length).append("\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'\r\n");if(extra!=null)for(Map.Entry<String,String> e:extra.entrySet())headers.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");headers.append("\r\n");out.write(headers.toString().getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();}
    private Map<String,String> form(String body){HashMap<String,String> result=new HashMap<>();if(body==null)return result;for(String item:body.split("&")){String[] pair=item.split("=",2);try{String k=java.net.URLDecoder.decode(pair[0],"UTF-8"),v=pair.length>1?java.net.URLDecoder.decode(pair[1],"UTF-8"):"";result.put(k,v);}catch(Exception ignored){}}return result;}
    private String buildLanUrls(){InetAddress a=DashboardSecurity.primaryLanAddress();if(a==null)return "เชื่อม Wi-Fi วง LAN เพื่อดูที่อยู่";String host=a.getHostAddress();if(a instanceof java.net.Inet6Address)host="["+host.split("%",2)[0]+"]";return "https://"+host+":"+PORT;}
    private String fingerprint(byte[] cert)throws Exception{byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(cert);StringBuilder b=new StringBuilder();for(byte x:hash){if(b.length()>0)b.append(':');b.append(String.format(Locale.ROOT,"%02X",x&255));}return b.toString();}
    private String json(String value){StringBuilder b=new StringBuilder("\"");for(int i=0;i<value.length();i++){char c=value.charAt(i);switch(c){case '"':b.append("\\\"");break;case '\\':b.append("\\\\");break;case '\n':b.append("\\n");break;case '\r':b.append("\\r");break;case '\t':b.append("\\t");break;default:if(c<32)b.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else b.append(c);}}return b.append('"').toString();}
    private String escapeHtml(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
    private boolean hasControls(String s){for(int i=0;i<s.length();i++)if(s.charAt(i)<32||s.charAt(i)==127)return true;return false;}
    private Map<String,String> map(String... values){HashMap<String,String> m=new HashMap<>();for(int i=0;i+1<values.length;i+=2)m.put(values[i],values[i+1]);return m;}
    private String safeError(Exception e){String s=e.getMessage();return s==null?e.getClass().getSimpleName():escapeHtml(s);}

    private Notification notification(String text){String channel="bedrockpocket_lan_dashboard";NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(new NotificationChannel(channel,"Web Dashboard",NotificationManager.IMPORTANCE_LOW));Intent stop=new Intent(this,LanDashboardService.class).setAction(ACTION_STOP);PendingIntent pending=PendingIntent.getService(this,2404,stop,(Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0)|PendingIntent.FLAG_UPDATE_CURRENT);Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,channel):new Notification.Builder(this);Notification.Action action=new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this,android.R.drawable.ic_media_pause),"ปิด Dashboard",pending).build();return b.setContentTitle("BedrockPocket Web Dashboard").setContentText(text).setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentIntent(PendingIntent.getActivity(this,2405,new Intent(this,MainActivity.class),(Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0)|PendingIntent.FLAG_UPDATE_CURRENT)).setOngoing(true).addAction(action).build();}
    private void closeNetwork(){SSLServerSocket s=listener;listener=null;if(s!=null)try{s.close();}catch(Exception ignored){}ThreadPoolExecutor pool=workers;workers=null;if(pool!=null)pool.shutdownNow();sessions.clear();failures.clear();}
    private void stopDashboard(){closeNetwork();getSharedPreferences(DashboardSecurity.PREFS,MODE_PRIVATE).edit().putString("runtime","ปิดอยู่").putString("fingerprint","").putString("urls","").apply();stopForeground(true);}
    @Override public void onDestroy(){if(startupFailed)closeNetwork();else stopDashboard();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
