import android.content.*;import android.content.res.*;import android.os.*;import java.util.*;import java.nio.file.*;import org.json.*;
public class LocaleResourceCheck {
 public static void main(String[] args){try{
  Looper.prepareMainLooper();Class<?> at=Class.forName("android.app.ActivityThread");Object thread=at.getMethod("systemMain").invoke(null);Context root=(Context)at.getMethod("getSystemContext").invoke(thread);
  Context app=root.createPackageContext("cz.codex.rtl8153diagnostic",0);
  JSONObject expected=new JSONObject(new String(Files.readAllBytes(Paths.get(args[0])),"UTF-8"));
  String[][] cases={{"cs-CZ","cs"},{"en-US","en"},{"de-DE","de"},{"es-ES","es"},{"fr-FR","fr"},{"es-MX","es"},{"fr-CA","fr"},{"it-IT","en"},{"it-IT,fr-FR","fr"}};
  int total=0,failures=0;
  for(String[] c:cases){Configuration cfg=new Configuration(app.getResources().getConfiguration());cfg.setLocales(LocaleList.forLanguageTags(c[0]));Context localized=app.createConfigurationContext(cfg);Resources res=localized.getResources();JSONObject entries=expected.getJSONObject(c[1]);int count=0;
   for(Iterator<String> it=entries.keys();it.hasNext();){String key=it.next();int id=res.getIdentifier(key,"string","cz.codex.rtl8153diagnostic");String value=id==0?"<missing>":res.getString(id);count++;total++;
    if(!value.equals(entries.getString(key))){failures++;System.out.println("FAIL "+c[0]+" "+key+" expected=["+entries.getString(key)+"] actual=["+value+"]");}
   }
   System.out.println("CHECK "+c[0]+" -> "+c[1]+" resources="+count);
  }
  System.out.println("TOTAL checks="+total+" failures="+failures);
 }catch(Throwable e){e.printStackTrace(System.out);}}
}
