package cz.codex.rtl8153diagnostic;
import android.content.*;
import android.util.Log;
public final class RecoveryReceiver extends BroadcastReceiver {
    static boolean isEnabled(Context c){return c.getSharedPreferences("ethernet",Context.MODE_PRIVATE).getBoolean("connection_enabled",false);}
    static void setEnabled(Context c,boolean enabled){
        if(!c.getSharedPreferences("ethernet",Context.MODE_PRIVATE).edit().putBoolean("connection_enabled",enabled).commit())
            Log.e("EthernetRecovery","Could not persist connection state");
    }
    static void resume(Context c){
        if(!isEnabled(c))return;
        try{c.startForegroundService(new Intent(c,EthernetVpnService.class).setAction("RESUME"));}
        catch(RuntimeException e){Log.w("EthernetRecovery","Automatic resume deferred; open app",e);}
    }
    @Override public void onReceive(Context c,Intent i){
        String a=i.getAction();
        if(Intent.ACTION_BOOT_COMPLETED.equals(a)||Intent.ACTION_MY_PACKAGE_REPLACED.equals(a))resume(c);
    }
}
