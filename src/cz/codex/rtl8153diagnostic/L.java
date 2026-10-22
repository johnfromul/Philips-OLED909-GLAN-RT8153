package cz.codex.rtl8153diagnostic;
/** Pure Java interface; Android supplies resource selection using the current configuration. */
public final class L {
    public interface Translator {String translate(String source);}
    private static volatile Translator translator;
    private L(){}
    public static void initialize(Translator t){translator=t;}
    public static String t(String source){Translator t=translator;return t==null?source:t.translate(source);}
    public static String error(String message){
        if(message==null)return "";
        String translated=t(message);if(!translated.equals(message))return translated;
        int colon=message.indexOf(": ");
        if(colon>0)return t(message.substring(0,colon))+message.substring(colon);
        return message;
    }
}
