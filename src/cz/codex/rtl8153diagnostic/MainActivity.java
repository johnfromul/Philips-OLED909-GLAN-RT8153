package cz.codex.rtl8153diagnostic;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.usb.*;
import android.net.VpnService;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final String ACTION_PERMISSION = "cz.codex.rtl8153diagnostic.USB_PERMISSION";
    private static final int FG = Color.rgb(230, 239, 244);
    private UsbManager usb;
    private Spinner devicesView;
    private TextView logView, verdictView;
    private final List<UsbDevice> devices = new ArrayList<>();
    private final List<Button> controls = new ArrayList<>();
    private final StringBuilder log = new StringBuilder();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean destroyed;
    private boolean busy, receiverRegistered;
    private String pendingDevice;
    private JSONObject report;
    private boolean networkAction, networkScreen;
    private TextView networkStatus;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refreshNetwork = new Runnable() { public void run() {
        if (destroyed) return;
        networkStatus.setText(EthernetVpnService.statusText());
        if (networkScreen) logView.setText(EthernetVpnService.logText());
        if (!busy && controls.size() >= 8) {
            controls.get(4).setEnabled(!EthernetVpnService.active||EthernetVpnService.needsUserAction);
            controls.get(5).setEnabled(EthernetVpnService.active||RecoveryReceiver.isEnabled(MainActivity.this));
            controls.get(6).setEnabled(!EthernetVpnService.active);
            controls.get(7).setEnabled(EthernetVpnService.active);
        }
        ui.postDelayed(this,1000);
    }};

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (ACTION_PERMISSION.equals(intent.getAction())) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (pendingDevice == null || device == null || !pendingDevice.equals(device.getDeviceName())) return;
                pendingDevice = null;
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && usb.hasPermission(device)) {
                    line(L.t("USB oprávnění uděleno."));
                    if (networkAction) prepareNetwork(device); else startTest(device);
                } else {
                    line(L.t("USB oprávnění nebylo uděleno. Test otevření neproběhl."));
                    verdictView.setText(L.t("Bez USB oprávnění nelze provést test."));
                    setBusy(false);
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                line(L.t("USB zařízení bylo odpojeno. Probíhající test může skončit chybou."));
                if (pendingDevice != null) { pendingDevice = null; setBusy(false); }
                if (!busy) scan();
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        usb = (UsbManager)getSystemService(USB_SERVICE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(28), dp(18), dp(28), dp(18)); page.setBackgroundColor(Color.rgb(13, 24, 34));
        TextView title = text("RTL8153 · USB Ethernet", 25); title.setTypeface(null, Typeface.BOLD); page.addView(title);
        TextView intro = text(L.t("Internet přes USB adaptér RTL8153 bez rootu. Připojení přes DHCP nebo ručně zadanou IPv4 adresu."), 15);
        page.addView(intro);
        devicesView = new Spinner(this); page.addView(devicesView, new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout buttons = new LinearLayout(this);
        addButton(buttons, L.t("1  Obnovit USB"), () -> scan());
        addButton(buttons, L.t("2  Testovat adaptér"), () -> requestTest());
        addButton(buttons, L.t("3  Uložit protokol"), () -> saveReport());
        addButton(buttons, L.t("Sdílet text"), () -> shareReport());
        page.addView(buttons);
        LinearLayout netButtons = new LinearLayout(this);
        addButton(netButtons, L.t("Připojit Ethernet"), () -> requestNetwork());
        addButton(netButtons, L.t("Odpojit"), () -> { networkScreen=true; startService(new Intent(this,EthernetVpnService.class).setAction("STOP")); });
        addButton(netButtons, L.t("IP nastavení"), () -> settings());
        addButton(netButtons, L.t("Ověřit internet"), () -> { networkScreen=true; startService(new Intent(this,EthernetVpnService.class).setAction("CHECK")); });
        page.addView(netButtons);
        networkStatus=text(EthernetVpnService.statusText(),15);page.addView(networkStatus);
        verdictView = text(L.t("Připoj adaptér a spusť test. Po udělení USB oprávnění test pokračuje automaticky."), 17);
        verdictView.setTextColor(Color.rgb(82, 216, 189)); verdictView.setPadding(0, dp(8), 0, dp(8)); page.addView(verdictView);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        logView = text("", 14); logView.setTypeface(Typeface.MONOSPACE);
        logView.setFocusable(false); logView.setTextIsSelectable(false);
        logView.setPadding(dp(12), dp(8), dp(12), dp(8)); logView.setBackgroundColor(Color.rgb(7, 15, 23));
        scroll.addView(logView); page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        scroll.setOnKeyListener((v, key, event) -> {
            if (key == KeyEvent.KEYCODE_DPAD_UP && event.getAction() == KeyEvent.ACTION_DOWN && scroll.getScrollY() == 0 && !busy) {
                controls.get(2).requestFocus(); return true;
            }
            return false;
        });
        setContentView(page);
        IntentFilter filter = new IntentFilter(ACTION_PERMISSION); filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
        receiverRegistered = true;
        newReport(); scan();
        RecoveryReceiver.resume(this);
        handleUsbIntent(getIntent());
        networkScreen=EthernetVpnService.active;ui.post(refreshNetwork);
        controls.get(1).requestFocus();
    }

    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);scan();handleUsbIntent(intent);}
    private void handleUsbIntent(Intent intent){
        if(intent==null||!UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction()))return;
        UsbDevice d=intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if(d!=null&&DescriptorUtil.isTarget(d.getVendorId(),d.getProductId())&&RecoveryReceiver.isEnabled(this)){
            networkScreen=true;RecoveryReceiver.resume(this);
            // A default USB handler receives a fresh grant on every enumeration.
            // Do not enable a connection that the user previously disconnected.
        }
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String s, int size) { TextView v = new TextView(this); v.setText(s); v.setTextSize(size); v.setTextColor(FG); return v; }
    private void addButton(LinearLayout row, String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setTextSize(14); b.setAllCaps(false); b.setFocusable(true);
        b.setMaxLines(2); b.setAutoSizeTextTypeUniformWithConfiguration(11,14,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        b.setId(View.generateViewId());
        b.setOnClickListener(v -> { if (!busy) action.run(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(54), 1); lp.setMargins(dp(3), 0, dp(3), 0);
        row.addView(b, lp); controls.add(b);
    }
    private void setBusy(boolean value) {
        busy = value; devicesView.setEnabled(!value);
        for (Button b : controls) b.setEnabled(!value);
        if (!value) controls.get(2).requestFocus();
    }
    private void line(String s) { log.append(s).append('\n'); logView.setText(log.toString()); }
    private void newReport() {
        report = new JSONObject(); log.setLength(0);
        put(report, "app_version", "0.6.1"); put(report, "time", new Date().toString());
        put(report, "android_release", Build.VERSION.RELEASE); put(report, "sdk", Build.VERSION.SDK_INT);
        put(report, "model", Build.MODEL); put(report, "build_display", Build.DISPLAY);
        put(report, "build_fingerprint", Build.FINGERPRINT); put(report, "supported_abis", Arrays.toString(Build.SUPPORTED_ABIS));
        put(report, "usb_host_feature", getPackageManager().hasSystemFeature("android.hardware.usb.host"));
        put(report, "ethernet_feature", getPackageManager().hasSystemFeature("android.hardware.ethernet"));
        line(L.t("RTL8153 Diagnostika 0.6.1")); line("Android " + Build.VERSION.RELEASE + " / SDK " + Build.VERSION.SDK_INT + " / " + Build.MODEL);
        line(L.t("Sestavení: ") + Build.DISPLAY);
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/version"))) {
            String kernel = reader.readLine(); put(report, "kernel_version", kernel); line("Kernel: " + kernel);
        } catch (IOException e) { put(report, "kernel_version", "not_readable"); }
        try {
            Intent consent = VpnService.prepare(this);
            put(report, "vpn_prepare", consent == null ? "already_authorized" : "consent_intent_returned");
            line(L.t("VPN API: prepare() dostupné; ") + (consent == null ? L.t("souhlas již existuje.") : L.t("souhlas by byl potřeba (nevyžádán).")));
        } catch (Exception e) { put(report, "vpn_prepare_error", e.toString()); line("VPN API: " + e); }
        line(L.t("Tento výsledek VPN API ještě nepotvrzuje, že lze vytvořit funkční tunel."));
        File[] net = new File("/sys/class/net").listFiles();
        if (net != null) { List<String> names = new ArrayList<>(); for (File f : net) names.add(f.getName());
            Collections.sort(names); put(report, "network_interface_names", new JSONArray(names)); line(L.t("Síťová rozhraní: ") + names); }
        else { put(report, "network_interface_names", "not_readable"); line(L.t("Seznam síťových rozhraní není aplikaci dostupný.")); }
    }
    private void scan() {
        devices.clear(); List<String> names = new ArrayList<>(); int selected = 0;
        if (usb != null) {
            devices.addAll(usb.getDeviceList().values());
            Collections.sort(devices, Comparator.comparing(UsbDevice::getDeviceName));
            for (int i=0; i<devices.size(); i++) {
                UsbDevice d=devices.get(i); boolean target=DescriptorUtil.isTarget(d.getVendorId(), d.getProductId());
                names.add(String.format(Locale.ROOT, L.t("%s%04x:%04x · %d rozhraní · %s"),
                    target ? "RTL8153  " : "USB  ", d.getVendorId(), d.getProductId(), d.getInterfaceCount(), d.getDeviceName()));
                if (target) selected=i;
            }
        }
        if (names.isEmpty()) names.add(L.t("Žádné USB zařízení není viditelné aplikaci."));
        ArrayAdapter<String> a=new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); devicesView.setAdapter(a); devicesView.setSelection(selected);
        line(L.t("USB seznam obnoven: ") + devices.size() + L.t(" zařízení. Test je povolen pouze pro 0bda:8153."));
    }
    private void requestTest() {
        if (EthernetVpnService.active) { line(L.t("Nejprve odpoj Ethernet; diagnostika nemůže převzít používané rozhraní.")); return; }
        networkAction=false;networkScreen=false;
        int index=devicesView.getSelectedItemPosition();
        if (index<0 || index>=devices.size()) { line(L.t("Připoj adaptér a obnov seznam USB.")); return; }
        UsbDevice d=devices.get(index);
        if (!DescriptorUtil.isTarget(d.getVendorId(),d.getProductId())) { line(L.t("Vybrané zařízení není 0bda:8153. Pro bezpečnost se netestuje.")); return; }
        newReport(); setBusy(true);
        if (usb.hasPermission(d)) { startTest(d); return; }
        pendingDevice=d.getDeviceName();
        line(L.t("Čekám na potvrzení USB oprávnění v systémovém dialogu. Při zamítnutí lze test opakovat."));
        verdictView.setText(L.t("Potvrď systémový dialog s přístupem k USB adaptéru."));
        try {
            Intent reply=new Intent(ACTION_PERMISSION).setPackage(getPackageName());
            PendingIntent pi=PendingIntent.getBroadcast(this,0,reply,PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            usb.requestPermission(d,pi);
        } catch (Exception e) { pendingDevice=null; line(L.t("Žádost o oprávnění selhala: ") + e); setBusy(false); }
    }
    private void startTest(UsbDevice device) {
        setBusy(true); verdictView.setText(L.t("Probíhá ECM test, nejvýše přibližně 20 sekund…"));
        final long started = SystemClock.elapsedRealtime();
        worker.execute(() -> {
            StringBuilder detail=new StringBuilder(); JSONObject result=new JSONObject();
            String verdict;
            try { verdict=performTest(device,result,detail); }
            catch (Exception e) { put(result,"unexpected_error",e.toString()); detail.append(L.t("Chyba: ")).append(e).append('\n'); verdict=L.t("Test skončil chybou; podrobnosti jsou v protokolu."); }
            final String message=verdict;
            runOnUiThread(() -> {
                if (destroyed) return;
                put(report,"usb_test",result); line(detail.toString());
                long elapsed = SystemClock.elapsedRealtime() - started;
                put(report,"test_duration_ms",elapsed); put(report,"test_complete",true);
                line(L.t("TEST DOKONČEN za ") + elapsed + L.t(" ms. Ovládání je znovu dostupné."));
                verdictView.setText(L.t("Test dokončen. ") + message); put(report,"verdict",message);
                saveReport(false); setBusy(false);
            });
        });
    }
    private String performTest(UsbDevice d, JSONObject result, StringBuilder text) throws Exception {
        put(result,"vendor_id",d.getVendorId()); put(result,"product_id",d.getProductId());
        put(result,"device_path",d.getDeviceName()); put(result,"device_class",d.getDeviceClass());
        put(result,"permission",usb.hasPermission(d));
        JSONArray configs=new JSONArray();
        for(int c=0;c<d.getConfigurationCount();c++) {
            UsbConfiguration config=d.getConfiguration(c); JSONObject cj=new JSONObject();
            put(cj,"id",config.getId()); put(cj,"interface_count",config.getInterfaceCount());
            JSONArray interfaces=new JSONArray();
            for(int j=0;j<config.getInterfaceCount();j++) interfaces.put(describe(config.getInterface(j)));
            put(cj,"interfaces",interfaces); configs.put(cj);
        }
        put(result,"configurations",configs);
        UsbDeviceConnection connection=usb.openDevice(d);
        put(result,"open_device",connection!=null);
        if(connection==null) return L.t("USB oprávnění existuje, ale openDevice() neotevřelo adaptér.");
        text.append("openDevice(): OK\n");
        try {
            byte[] raw=connection.getRawDescriptors(); put(result,"raw_descriptors_hex",DescriptorUtil.hex(raw));
            List<String> desc=DescriptorUtil.summarize(raw); put(result,"descriptor_summary",new JSONArray(desc));
            for(String s:desc) text.append(s).append('\n');
            byte[] configValue=new byte[1];
            int configRead=connection.controlTransfer(0x80,8,0,0,configValue,1,1500);
            int current=configRead==1 ? configValue[0]&255 : -1;
            put(result,"get_configuration_result",configRead); put(result,"current_configuration",current);
            text.append("GET_CONFIGURATION: ").append(current).append(L.t(" (návrat ")).append(configRead).append(")\n");
            byte[] status=new byte[2]; int statusRead=connection.controlTransfer(0x80,0,0,0,status,2,1500);
            put(result,"get_status_result",statusRead); if(statusRead==2) put(result,"device_status_hex",DescriptorUtil.hex(status));
            JSONArray claims=new JSONArray(); int candidates=0,successes=0;
            for(int c=0;c<d.getConfigurationCount();c++) {
                UsbConfiguration config=d.getConfiguration(c);
                // Do not change configurations or guess which configuration is active.
                if(config.getId()!=current) continue;
                for(int j=0;j<config.getInterfaceCount();j++) {
                    UsbInterface face=config.getInterface(j); boolean input=false,output=false;
                    for(int e=0;e<face.getEndpointCount();e++) {
                        UsbEndpoint ep=face.getEndpoint(e);
                        if(ep.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            if(ep.getDirection()==UsbConstants.USB_DIR_IN) input=true; else output=true;
                        }
                    }
                    if(!input || !output) continue;
                    candidates++; JSONObject claim=describe(face); boolean claimed=false;
                    try { claimed=connection.claimInterface(face,false); put(claim,"claim_without_force",claimed);
                        text.append(L.t("Rozhraní ")).append(face.getId()).append(" alt ").append(face.getAlternateSetting())
                            .append(" bulk IN+OUT: claim(force=false) = ").append(claimed).append('\n');
                        if(claimed) successes++;
                    } catch(Exception e) { put(claim,"claim_error",e.toString()); text.append("claimInterface: ").append(e).append('\n'); }
                    finally { if(claimed) { boolean released=connection.releaseInterface(face); put(claim,"release",released); text.append(L.t("Uvolnění rozhraní: ")).append(released).append('\n'); } }
                    claims.put(claim);
                }
            }
            put(result,"interface_claims",claims); put(result,"bulk_candidates",candidates); put(result,"successful_claims",successes);
            if(successes>0) { JSONObject ecm=new JSONObject(); put(result,"ecm_probe",ecm); return EcmProbe.run(d,connection,current,ecm,text); }
            if(current<0) return L.t("Adaptér lze otevřít, ale aktivní konfiguraci se nepodařilo přečíst. Převzetí rozhraní nebylo zkoušeno.");
            if(candidates==0) return L.t("Adaptér lze otevřít, ale aktivní konfigurace nenabízí rozhraní s bulk IN i OUT. Potřebujeme protokol.");
            return L.t("Adaptér lze otevřít, ale převzetí bulk rozhraní bez vynucení selhalo. Potřebujeme protokol.");
        } finally { connection.close(); text.append(L.t("USB spojení uzavřeno.\n")); }
    }
    private JSONObject describe(UsbInterface face) throws JSONException {
        JSONObject o=new JSONObject(); o.put("id",face.getId()); o.put("alternate_setting",face.getAlternateSetting());
        o.put("class",face.getInterfaceClass()); o.put("subclass",face.getInterfaceSubclass()); o.put("protocol",face.getInterfaceProtocol());
        JSONArray eps=new JSONArray();
        for(int e=0;e<face.getEndpointCount();e++) { UsbEndpoint ep=face.getEndpoint(e); JSONObject x=new JSONObject();
            x.put("address",ep.getAddress()); x.put("direction",ep.getDirection()==UsbConstants.USB_DIR_IN?"IN":"OUT");
            x.put("type",ep.getType()); x.put("max_packet_size",ep.getMaxPacketSize()); x.put("interval",ep.getInterval()); eps.put(x); }
        o.put("endpoints",eps); return o;
    }
    private static void put(JSONObject object,String key,Object value) { try { object.put(key,value); } catch(JSONException ignored) {} }
    private String reportText() {
        if(networkScreen) return EthernetVpnService.statusText()+"\n"+EthernetVpnService.logText();
        String json; try { json=report.toString(2); } catch(JSONException e) { json=report.toString(); }
        return log.toString()+L.t("\n--- STROJOVÁ DATA JSON ---\n")+json+"\n";
    }
    private void saveReport() {
        saveReport(true);
    }
    private void saveReport(boolean showDialog) {
        try {
            File directory=getExternalFilesDir(null); if(directory==null) throw new IOException(L.t("Externí úložiště aplikace není dostupné."));
            String stamp=new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(new Date());
            File target=new File(directory,"rtl8153-report-"+stamp+".txt");
            try(FileOutputStream f=new FileOutputStream(target)) { f.write(reportText().getBytes(StandardCharsets.UTF_8)); }
            line(L.t("Protokol uložen: ")+target.getAbsolutePath());
            if (showDialog) new AlertDialog.Builder(this).setTitle(L.t("Protokol uložen")).setMessage(target.getAbsolutePath()+L.t("\n\nLze jej stáhnout přes ADB nebo použít tlačítko Sdílet text."))
                .setPositiveButton("OK",null).show();
        } catch(Exception e) { line(L.t("Uložení selhalo: ")+e); }
    }
    private void shareReport() {
        try { Intent send=new Intent(Intent.ACTION_SEND); send.setType("text/plain"); send.putExtra(Intent.EXTRA_SUBJECT,L.t("RTL8153 diagnostika"));
            send.putExtra(Intent.EXTRA_TEXT,reportText()); startActivity(Intent.createChooser(send,L.t("Sdílet diagnostický protokol")));
        } catch(Exception e) { line(L.t("Sdílení není dostupné: ")+e+L.t(". Použij Uložit protokol.")); }
    }
    @Override protected void onDestroy() {
        destroyed=true; ui.removeCallbacks(refreshNetwork); if(receiverRegistered) unregisterReceiver(receiver); worker.shutdown(); super.onDestroy();
    }

    private String networkDevice;
    private void requestNetwork() {
        if(EthernetVpnService.active&&EthernetVpnService.connected)return;
        int index=devicesView.getSelectedItemPosition();
        if(index<0||index>=devices.size()){line(L.t("Připoj adaptér a obnov USB seznam."));return;}
        UsbDevice d=devices.get(index);
        if(!DescriptorUtil.isTarget(d.getVendorId(),d.getProductId())){line(L.t("Vyber adaptér RTL8153 0bda:8153."));return;}
        networkAction=true;networkScreen=true;networkDevice=d.getDeviceName();
        if(usb.hasPermission(d)){prepareNetwork(d);return;}
        pendingDevice=d.getDeviceName();setBusy(true);verdictView.setText(L.t("Potvrď přístup k USB adaptéru."));
        try{usb.requestPermission(d,PendingIntent.getBroadcast(this,0,new Intent(ACTION_PERMISSION).setPackage(getPackageName()),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE));}
        catch(Exception e){pendingDevice=null;setBusy(false);line(L.t("Žádost USB selhala: ")+e);}
    }
    private void prepareNetwork(UsbDevice d) {
        setBusy(false);networkDevice=d.getDeviceName();
        Intent consent=VpnService.prepare(this);
        if(consent!=null){verdictView.setText(L.t("Potvrď systémový souhlas s VPN pro USB Ethernet."));startActivityForResult(consent,8153);}
        else launchNetwork();
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==8153){if(result==RESULT_OK)launchNetwork();else verdictView.setText(L.t("VPN souhlas nebyl udělen. Ethernet zůstal odpojen."));}
    }
    private void launchNetwork(){
        Intent i=new Intent(this,EthernetVpnService.class).putExtra("device",networkDevice);
        startForegroundService(i);verdictView.setText(L.t("USB Ethernet: stav a protokol jsou uvedeny níže."));controls.get(5).requestFocus();
    }
    private EditText settingField(LinearLayout box,String label,String value){
        box.addView(text(label,14));EditText e=new EditText(this);e.setText(value);e.setSingleLine(true);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setSelectAllOnFocus(true);
        box.addView(e,new LinearLayout.LayoutParams(-1,dp(46)));return e;
    }
    private void settings(){
        if(EthernetVpnService.active)return;
        SharedPreferences p=getSharedPreferences("ethernet",MODE_PRIVATE);
        ScrollView scroll=new ScrollView(this);LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(dp(18),dp(8),dp(18),dp(8));scroll.addView(box);
        Switch automatic=new Switch(this);automatic.setText(L.t("Automaticky z DHCP"));automatic.setChecked(p.getBoolean("dhcp",true));box.addView(automatic);
        Switch fast=new Switch(this);fast.setText(L.t("Nativní rychlý přenos (asynchronní USB)"));fast.setChecked(p.getBoolean("native_fast",true));box.addView(fast);
        box.addView(text(L.t("Ručně zadej volnou adresu ve své síti, ideálně mimo DHCP rozsah nebo rezervovanou v routeru."),14));
        EditText ip=settingField(box,L.t("IP adresa"),p.getString("ip",""));
        EditText prefix=settingField(box,L.t("Prefix nebo maska (24 nebo 255.255.255.0)"),p.getString("prefix","24"));
        EditText gateway=settingField(box,L.t("Výchozí brána"),p.getString("gateway",""));
        EditText dns=settingField(box,L.t("DNS servery (oddělené čárkou)"),p.getString("dns",""));
        TextView error=text("",14);error.setTextColor(Color.rgb(255,150,120));box.addView(error);
        EditText[]fields={ip,prefix,gateway,dns};for(EditText f:fields)f.setEnabled(!automatic.isChecked());
        automatic.setOnCheckedChangeListener((v,on)->{for(EditText f:fields)f.setEnabled(!on);});
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(L.t("Nastavení USB Ethernetu · IPv4")).setView(scroll).setNegativeButton(L.t("Zrušit"),null).setPositiveButton(L.t("Uložit"),null).create();
        dialog.setOnKeyListener((d,key,event)->{
            if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
            View focused=dialog.getCurrentFocus();
            for(int k=0;k<fields.length;k++)if(focused==fields[k]){
                if(key==KeyEvent.KEYCODE_DPAD_DOWN){if(k+1<fields.length)fields[k+1].requestFocus();else dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();return true;}
                if(key==KeyEvent.KEYCODE_DPAD_UP){if(k>0)fields[k-1].requestFocus();else automatic.requestFocus();return true;}
            }
            return false;
        });
        for(int k=0;k<fields.length;k++) {
            final int index=k;
            fields[k].setOnKeyListener((v,key,event)->{
                if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
                if(key==KeyEvent.KEYCODE_DPAD_DOWN){if(index+1<fields.length)fields[index+1].requestFocus();else dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();return true;}
                if(key==KeyEvent.KEYCODE_DPAD_UP){if(index>0)fields[index-1].requestFocus();else automatic.requestFocus();return true;}
                return false;
            });
        }
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{
            try{if(!automatic.isChecked())IpConfig.manual(ip.getText().toString(),prefix.getText().toString(),gateway.getText().toString(),dns.getText().toString());
                p.edit().putBoolean("dhcp",automatic.isChecked()).putBoolean("native_fast",fast.isChecked()).putString("ip",ip.getText().toString().trim()).putString("prefix",prefix.getText().toString().trim())
                    .putString("gateway",gateway.getText().toString().trim()).putString("dns",dns.getText().toString().trim()).apply();
                verdictView.setText(automatic.isChecked()?L.t("Uloženo: automatická konfigurace DHCP."):L.t("Uloženo: ruční IPv4 konfigurace."));dialog.dismiss();
            }catch(IllegalArgumentException e){error.setText(e.getMessage());}
        }));dialog.show();
    }
}
