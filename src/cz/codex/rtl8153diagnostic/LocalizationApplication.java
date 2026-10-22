package cz.codex.rtl8153diagnostic;
import android.app.Application;
public final class LocalizationApplication extends Application {
    @Override public void onCreate(){super.onCreate();
        L.initialize(source->{
            String key=LKeys.key(source);if(key==null)return source;
            int id=getResources().getIdentifier(key,"string",getPackageName());
            return id==0?source:getString(id);
        });
    }
}
