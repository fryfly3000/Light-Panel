package app.lightpanel.launcher;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import org.json.JSONObject;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.SslErrorHandler;
import android.net.http.SslError;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import java.net.HttpURLConnection;
import java.net.URL;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.graphics.Color;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private static final String TAG = "LightPanelTime";
    private static final String URL = "http://192.168.1.50:8089";
    private static final long TWELVE_HOURS_MS = 12L * 60 * 60 * 1000;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final DashboardOrigin dashboardOrigin=new DashboardOrigin(URL);
    private final LedController leds=new LedController();
    private ScreenController screen;
    private WebMessagePort ledPort;
    private int ledGeneration;
    private WebView web;
    private TextView message;
    private boolean pageFailed=false,pageReady=false,retryPending=false;
    private int pageAttempt;
    private volatile boolean sslFailed = false;
    private volatile boolean initialPageStarted = false;
    private volatile boolean destroyed = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        screen=new ScreenController(this);
        if(screen.available()&&!screen.isOn())setNativeScreen(true,ledGeneration,0);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        fullscreen();

        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setBuiltInZoomControls(false);
        web.getSettings().setDisplayZoomControls(false);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view,String url,Bitmap icon){
                closeLedPort();pageFailed=false;pageReady=false;sslFailed=false;final int attempt=++pageAttempt;
                mainHandler.postDelayed(()->{if(!destroyed&&attempt==pageAttempt&&!pageReady)pageUnavailable("Dashboard laden duurt te lang. Opnieuw proberen…");},20000);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return externalNavigation(url);}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return externalNavigation(request.getUrl().toString());}
            @Override public void onPageFinished(WebView view, String url) {
                if(sslFailed||pageFailed)return;
                final int attempt=pageAttempt;
                view.evaluateJavascript("Boolean(location.origin === "+JSONObject.quote(dashboardOrigin.value())+" && document.querySelector('main.frame'))",result->{
                    if(destroyed||attempt!=pageAttempt||pageFailed||sslFailed)return;
                    if(!"true".equals(result)){pageUnavailable("Dashboard nog niet geladen. Opnieuw proberen…");return;}
                    pageReady=true;web.setVisibility(View.VISIBLE);message.setVisibility(View.GONE);openLedPort(url);
                });
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    pageUnavailable("Dashboard niet bereikbaar. Wachten op wifi; ik probeer het opnieuw…");
                }
            }
            @Override public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse response){
                if(request.isForMainFrame())pageUnavailable("Dashboard geeft een fout. Opnieuw proberen…");
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                sslFailed = true;
                handler.cancel(); // Never ignore HTTPS certificate failures.
                pageUnavailable("HTTPS-fout: datum en verbinding worden opnieuw gecontroleerd.");
            }
        });
        root.addView(web);
        message = new TextView(this);
        message.setText("Tijd synchroniseren en dashboard starten...");
        message.setTextColor(Color.WHITE);
        message.setBackgroundColor(Color.DKGRAY);
        message.setPadding(20, 20, 20, 20);
        message.setOnClickListener(v->loadDashboard());
        root.addView(message);
        setContentView(root);

        // Don't block the launcher indefinitely if NTP or su is unavailable.
        startPageOnce();
        syncClockAsync();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(BootReceiver.EXTRA_FROM_BOOT, false)) {
            if (screen != null && screen.available()) setNativeScreen(true, ledGeneration, 0);
            loadDashboard(); // One reload after an actual Android boot; never on a data refresh.
        }
    }

    private void syncClockAsync() {
        worker.execute(new Runnable() {
            @Override public void run() {
                boolean success = false;
                try {
                    long now = getReferenceTime();
                    if (now < 1700000000000L || now > 4102444800000L)
                        throw new IllegalStateException("NTP timestamp out of range");
                    Log.i(TAG, "Reference time: " + new Date(now));
                    // Use UTC explicitly: Android Java and the vendor shell disagree on local timezone.
                    SimpleDateFormat format = new SimpleDateFormat("MMddHHmmyyyy.ss", Locale.US);
                    format.setTimeZone(TimeZone.getTimeZone("UTC"));
                    String dateArg = format.format(new Date(now));
                    String command = "date -u " + dateArg;
                    try(RootShell clockShell=new RootShell()){clockShell.execute(command);}
                    long difference = Math.abs(System.currentTimeMillis() - now);
                    if (difference > 120000L) throw new IllegalStateException("clock still incorrect by " + difference + "ms");
                    success = true;
                    Log.i(TAG, "Clock successfully synchronized");
                } catch (Exception e) {
                    Log.e(TAG, "Clock synchronization failed", e);
                }
                final boolean ok = success;
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        if (destroyed) return;
                        if (!initialPageStarted) startPageOnce();
                        else if (ok && sslFailed) {
                            sslFailed = false;
                            loadDashboard(); // Recover only a failed load; keep a healthy page in place.
                        }
                    }
                });
                // Clock only. No periodic WebView reload.
                if (!destroyed) mainHandler.postDelayed(new Runnable() {
                    @Override public void run() { if (!destroyed) syncClockAsync(); }
                }, success ? TWELVE_HOURS_MS : 60000L);
            }
        });
    }

    private long getReferenceTime() throws Exception {
        HttpURLConnection connection=null;
        try{
            connection=(HttpURLConnection)new URL(URL+"/api/time").openConnection();
            connection.setConnectTimeout(4000);connection.setReadTimeout(4000);connection.setUseCaches(false);
            if(connection.getResponseCode()!=200)throw new IllegalStateException("Time API unavailable");
            StringBuilder body=new StringBuilder();
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(connection.getInputStream()))){
                String line;while((line=reader.readLine())!=null&&body.length()<2048)body.append(line);
            }
            long value=new JSONObject(body.toString()).getLong("server_time_ms");
            if(value<1700000000000L||value>4102444800000L)throw new IllegalStateException("Invalid server time");
            Log.i(TAG,"Time source: dashboard API");return value;
        }catch(Exception error){Log.w(TAG,"Dashboard time unavailable; trying NTP");return getNtpTime();}
        finally{if(connection!=null)connection.disconnect();}
    }
    private void pageUnavailable(String reason){
        pageFailed=true;pageReady=false;closeLedPort();web.setVisibility(View.INVISIBLE);
        message.setText(reason+"\nTik hier om meteen opnieuw te laden.");message.setVisibility(View.VISIBLE);
        if(!retryPending&&!destroyed){retryPending=true;mainHandler.postDelayed(()->{retryPending=false;if(!destroyed&&!pageReady)loadDashboard();},10000);}
    }
    private void loadDashboard(){
        if(destroyed)return;
        message.setText("Dashboard laden…");message.setVisibility(View.VISIBLE);web.setVisibility(View.INVISIBLE);web.loadUrl(URL);
    }

    private static long getNtpTime() throws Exception {
        String[] servers = {"time.google.com", "pool.ntp.org", "time.cloudflare.com"};
        Exception last = null;
        for (String server : servers) {
            try {
                DatagramSocket socket = new DatagramSocket();
                try {
                    socket.setSoTimeout(2500);
                    byte[] data = new byte[48];
                    data[0] = 0x23; // NTP v4, client mode
                    DatagramPacket request = new DatagramPacket(data, data.length,
                            InetAddress.getByName(server), 123);
                    socket.send(request);
                    DatagramPacket response = new DatagramPacket(data, data.length);
                    socket.receive(response);
                    if (response.getLength() < 48) throw new IllegalStateException("Short NTP reply");
                    int mode = data[0] & 7;
                    int stratum = data[1] & 0xff;
                    if (mode != 4 || stratum == 0 || stratum > 15 || ((data[0] >>> 6) & 3) == 3)
                        throw new IllegalStateException("Invalid NTP response");
                    long seconds = readUnsigned32(data, 40);
                    long fraction = readUnsigned32(data, 44);
                    if (seconds == 0) throw new IllegalStateException("Missing NTP time");
                    return (seconds - 2208988800L) * 1000L + (fraction * 1000L >>> 32);
                } finally { socket.close(); }
            } catch (Exception e) {
                last = e;
                Log.w(TAG, "NTP server failed: " + server + ": " + e);
            }
        }
        throw new IllegalStateException("All NTP servers failed", last);
    }

    private static long readUnsigned32(byte[] b, int offset) {
        return ((long)(b[offset] & 0xff) << 24) |
                ((long)(b[offset + 1] & 0xff) << 16) |
                ((long)(b[offset + 2] & 0xff) << 8) |
                (long)(b[offset + 3] & 0xff);
    }

    private void startPageOnce() {
        if (initialPageStarted || destroyed) return;
        initialPageStarted = true;
        loadDashboard();
    }

    private boolean externalNavigation(String url){
        if(dashboardOrigin.allows(url))return false;
        closeLedPort();Uri uri=Uri.parse(url);
        if("http".equalsIgnoreCase(uri.getScheme())||"https".equalsIgnoreCase(uri.getScheme())){
            try{startActivity(new Intent(Intent.ACTION_VIEW,uri));}catch(Exception ignored){}
        }
        return true;
    }
    private void closeLedPort(){
        ledGeneration++;
        if(ledPort!=null){try{ledPort.close();}catch(Exception ignored){}ledPort=null;}
    }
    private void openLedPort(String url){
        closeLedPort();
        if(!dashboardOrigin.allows(url)||!dashboardOrigin.allows(web.getUrl()))return;
        try{
            WebMessagePort[] ports=web.createWebMessageChannel();ledPort=ports[0];final int generation=ledGeneration;
            ledPort.setWebMessageCallback(new WebMessagePort.WebMessageCallback(){
                @Override public void onMessage(WebMessagePort port,WebMessage message){
                    if(generation!=ledGeneration||!dashboardOrigin.allows(web.getUrl()))return;
                    handleLedMessage(message.getData(),generation);
                }
            });
            web.postWebMessage(new WebMessage("frameo-led-port:v1",new WebMessagePort[]{ports[1]}),Uri.parse(dashboardOrigin.value()));
            replyLed(generation,0,true,"ready","Lokale LED-koppeling beschikbaar");
        }catch(Exception error){closeLedPort();Log.w("LightPanelLED","WebView does not support LED channel",error);}
    }
    private static int integer(JSONObject value,String key) throws Exception {
        Object raw=value.get(key);if(!(raw instanceof Number))throw new IllegalArgumentException("LED values must be numbers");
        double number=((Number)raw).doubleValue();
        if(Double.isNaN(number)||Double.isInfinite(number)||number!=Math.rint(number)||number<0||number>255)throw new IllegalArgumentException("LED values must be whole numbers 0–255");
        return (int)number;
    }
    private void handleLedMessage(String raw,int generation){
        int request=0;
        try{
            if(raw==null||raw.length()>1024)throw new IllegalArgumentException("Invalid LED request");
            JSONObject data=new JSONObject(raw);request=data.optInt("id",0);
            if("capture".equals(data.optString("type"))){captureScreen(generation,request);return;}
            if("screen".equals(data.optString("type"))){
                Object wanted=data.get("on");if(!(wanted instanceof Boolean))throw new IllegalArgumentException("Screen state must be boolean");
                final int id=request;
                setNativeScreen((Boolean)wanted,generation,id);return;
            }
            if(!"set".equals(data.optString("type")))throw new IllegalArgumentException("Unknown LED request");
            String mode=data.getString("mode");int brightness=mode.equals("off")?0:integer(data,"brightness");
            int red=mode.equals("color")?integer(data,"red"):0,green=mode.equals("color")?integer(data,"green"):0,blue=mode.equals("color")?integer(data,"blue"):0;
            final int id=request;
            leds.set(mode,brightness,red,green,blue,(ok,active)->replyLed(generation,id,ok,active,ok?"LED-opdracht uitgevoerd":"Geen LED-toegang. Controleer rootrechten voor LightPanel Dashboard."));
        }catch(Exception error){replyLed(generation,request,false,"error","Ongeldige LED-opdracht");}
    }
    private void replyLed(int generation,int id,boolean ok,String mode,String message){
        mainHandler.post(()->{
            if(destroyed||generation!=ledGeneration||ledPort==null)return;
            try{
                JSONObject reply=new JSONObject();reply.put("type",mode.equals("ready")?"ready":"result");reply.put("id",id);reply.put("ok",ok);reply.put("mode",mode);reply.put("message",message);if(mode.equals("ready")){reply.put("capture",true);reply.put("screenControl",screen.available());reply.put("screenOn",screen.isOn());reply.put("appVersion",getPackageManager().getPackageInfo(getPackageName(),0).versionName);}ledPort.postMessage(new WebMessage(reply.toString()));
            }catch(Exception ignored){}
        });
    }

    private void setNativeScreen(boolean wanted,int generation,int id){
        screen.set(wanted,(ok,on)->{
            if(!ok){replyScreen(generation,id,false,on);return;}
            leds.setScreenEnabled(on,(ledOk,mode)->replyScreen(generation,id,ledOk,on));
        });
    }

    private void replyScreen(int generation,int id,boolean ok,boolean on){
        mainHandler.post(()->{
            if(destroyed||generation!=ledGeneration||ledPort==null)return;
            try{JSONObject reply=new JSONObject();reply.put("type","screen");reply.put("id",id);reply.put("ok",ok);reply.put("on",on);reply.put("message",ok?(on?"Scherm aan":"Scherm uit"):"Scherm kon niet worden ingesteld");ledPort.postMessage(new WebMessage(reply.toString()));}catch(Exception ignored){}
        });
    }

    private void captureScreen(int generation,int id){
        mainHandler.post(()->{
            if(destroyed||generation!=ledGeneration||ledPort==null||!dashboardOrigin.allows(web.getUrl()))return;
            Bitmap image=null;
            try{
                int originalWidth=web.getWidth(),originalHeight=web.getHeight();
                if(originalWidth<=0||originalHeight<=0)throw new IllegalStateException("No visible dashboard");
                float scale=Math.min(1f,Math.min(720f/originalWidth,1280f/originalHeight));
                image=Bitmap.createBitmap(Math.max(1,Math.round(originalWidth*scale)),Math.max(1,Math.round(originalHeight*scale)),Bitmap.Config.RGB_565);
                Canvas canvas=new Canvas(image);canvas.scale(scale,scale);web.draw(canvas);
                final Bitmap captured=image;
                worker.execute(()->{
                    try{
                        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                        if(!captured.compress(Bitmap.CompressFormat.JPEG,70,bytes))throw new IllegalStateException("JPEG failed");
                        String data="data:image/jpeg;base64,"+Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP);
                        replyCapture(generation,id,true,data,"Schermbeeld opgehaald");
                    }catch(Exception error){replyCapture(generation,id,false,"","Schermbeeld kon niet worden gemaakt");}
                    finally{captured.recycle();}
                });
            }catch(Exception error){if(image!=null)image.recycle();replyCapture(generation,id,false,"","Schermbeeld kon niet worden gemaakt");}
        });
    }
    private void replyCapture(int generation,int id,boolean ok,String image,String message){
        mainHandler.post(()->{
            if(destroyed||generation!=ledGeneration||ledPort==null)return;
            try{JSONObject reply=new JSONObject();reply.put("type","capture");reply.put("id",id);reply.put("ok",ok);reply.put("image",image);reply.put("message",message);ledPort.postMessage(new WebMessage(reply.toString()));}catch(Exception ignored){}
        });
    }

    private void fullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) fullscreen();
    }
    @Override public void onBackPressed() { loadDashboard(); }
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event){
        if(screen!=null&&!screen.isOn()){
            if(event.getAction()==android.view.MotionEvent.ACTION_DOWN){final int generation=ledGeneration;setNativeScreen(true,generation,0);}
            return true;
        }
        return super.dispatchTouchEvent(event);
    }
    @Override protected void onDestroy() {
        destroyed = true;
        closeLedPort();leds.close();if(screen!=null)screen.close();
        worker.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
