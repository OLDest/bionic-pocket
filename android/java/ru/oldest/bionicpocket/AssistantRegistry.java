package ru.oldest.bionicpocket;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import org.json.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Editable profiles plus append-only encrypted connections for already accepted requests. */
public final class AssistantRegistry {
    private AssistantRegistry() {}
    private static JSONArray list(SharedPreferences p){try{return new JSONArray(p.getString("assistantProfiles","[]"));}catch(JSONException e){throw new IllegalStateException("Повреждены настройки помощников",e);}}
    private static JSONObject connections(SharedPreferences p){try{return new JSONObject(p.getString("assistantConnections","{}"));}catch(JSONException e){throw new IllegalStateException("Повреждены настройки подключений",e);}}
    private static void put(JSONObject o,String k,Object v){try{o.put(k,v);}catch(JSONException e){throw new IllegalStateException(e);}}
    public static JSONArray profiles(SharedPreferences p){return list(p);}
    public static JSONObject profile(SharedPreferences p,String id){JSONArray a=list(p);for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null&&id.equals(o.optString("id")))return o;}return null;}
    public static boolean active(SharedPreferences p,String id){JSONObject o=profile(p,id);return o!=null&&!o.optBoolean("disabled");}
    public static String first(SharedPreferences p){JSONArray a=list(p);for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(!o.optBoolean("disabled"))return o.optString("id");}return "";}
    public static String name(SharedPreferences p,String id){JSONObject o=profile(p,id);return o==null?(id.isEmpty()?"Помощник":id):o.optString("name",id);}
    public static String origin(SharedPreferences p,String id){JSONObject o=profile(p,id);if(o==null)return "";JSONObject c=connections(p).optJSONObject(o.optString("connectionId"));return c==null?"":c.optString("origin");}

    private static SecretKey key() throws Exception {KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);if(store.containsAlias("bionic-token"))return (SecretKey)store.getKey("bionic-token",null);KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");gen.init(new KeyGenParameterSpec.Builder("bionic-token",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());return gen.generateKey();}
    public static String encrypt(String value)throws Exception{if(value.isEmpty())return "";Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());return android.util.Base64.encodeToString(c.getIV(),2)+":"+android.util.Base64.encodeToString(c.doFinal(value.getBytes(StandardCharsets.UTF_8)),2);}
    public static String decrypt(String value)throws Exception{if(value.isEmpty())return "";String[] a=value.split(":",2);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,android.util.Base64.decode(a[0],2)));return new String(c.doFinal(android.util.Base64.decode(a[1],2)),StandardCharsets.UTF_8);}

    /** A connection's identity, endpoint and credential never change after creation. */
    public static final class Connection {
        public final String id,profileId,origin,helperId;
        public final boolean privateLan;
        private final String encryptedToken;
        private Connection(String id,JSONObject c){this.id=id;profileId=c.optString("profileId");origin=c.optString("origin");helperId=c.optString("helperId");privateLan=c.optBoolean("privateLan");encryptedToken=c.optString("token");}
        public String token()throws Exception{return decrypt(encryptedToken);}
        public String validate()throws IOException{String value=AssistantEndpoint.validate(profileId,origin,privateLan);if(privateLan)AssistantEndpoint.requirePrivateAddress(value);return value;}
    }
    public static Connection current(SharedPreferences p,String id)throws IOException{JSONObject o=profile(p,id);if(o==null)throw new IOException("Выберите настроенного помощника");return resolve(p,id,origin(p,id),o.optString("connectionId"));}
    public static Connection resolve(SharedPreferences p,String id,String origin,String pinned)throws IOException{
        JSONObject all=connections(p),c=null;String key=pinned;
        if(!key.isEmpty())c=all.optJSONObject(key);
        else{JSONObject o=profile(p,id);if(o!=null){key=o.optString("connectionId");c=all.optJSONObject(key);}if(c==null||!origin.equals(c.optString("origin"))){c=null;Iterator<String> keys=all.keys();while(keys.hasNext()){String k=keys.next();JSONObject candidate=all.optJSONObject(k);if(candidate!=null&&id.equals(candidate.optString("profileId"))&&origin.equals(candidate.optString("origin"))){key=k;c=candidate;break;}}}}
        if(c==null||!id.equals(c.optString("profileId"))||!origin.equals(c.optString("origin")))throw new IOException("Исходное подключение запроса недоступно; другой помощник не выбран автоматически");return new Connection(key,c);
    }
    public static void pin(SharedPreferences p,JSONObject message,String id,String origin)throws IOException{Connection c=resolve(p,id,origin,message.optString("connectionId"));put(message,"connectionId",c.id);}

    /** Legacy Gonets/Domovoy profiles are migration inputs, never a fixed supported list. */
    public static synchronized void migrate(SharedPreferences p,String defaultUrl){
        if(p.contains("assistantProfiles"))return;
        JSONArray profiles=new JSONArray();JSONObject all=new JSONObject();String encrypted=p.getString("token","");JSONArray chats;try{chats=new JSONArray(p.getString("chats","[]"));}catch(JSONException e){chats=new JSONArray();}
        if(p.contains("server")||!encrypted.isEmpty()||chats.length()>0)addLegacy(profiles,all,"gonets","Гонец",p.getString("server",defaultUrl),false,encrypted);
        boolean officeHistory=false;for(int i=0;i<chats.length();i++){JSONObject chat=chats.optJSONObject(i);if(chat!=null&&"domovoy".equals(chat.optString("assistant",chat.optString("assistantId"))))officeHistory=true;}
        if(!p.getString("officeServer","").isEmpty()||officeHistory)addLegacy(profiles,all,"domovoy","Домовой",p.getString("officeServer",""),true,encrypted);
        p.edit().putString("assistantProfiles",profiles.toString()).putString("assistantConnections",all.toString()).commit();
    }
    private static void addLegacy(JSONArray profiles,JSONObject all,String id,String name,String origin,boolean lan,String token){String key=UUID.randomUUID().toString();JSONObject c=new JSONObject();put(c,"profileId",id);put(c,"origin",origin);put(c,"privateLan",lan);put(c,"token",token);put(c,"helperId",id);put(all,key,c);JSONObject o=new JSONObject();put(o,"id",id);put(o,"name",name);put(o,"connectionId",key);profiles.put(o);}

    /** Changes create a new encrypted connection; old pending requests retain the old one. */
    public static synchronized String save(SharedPreferences p,String id,String name,String origin,boolean lan,String replacement)throws Exception{
        if(name.trim().isEmpty()||name.length()>80)throw new IOException("Введите имя помощника (до 80 символов)");
        JSONArray profiles=list(p);JSONObject all=connections(p),o=id.isEmpty()?null:profile(p,id);if(o==null){if(profiles.length()>=64)throw new IOException("Достигнут предел сохранённых профилей");id="helper-"+UUID.randomUUID();o=new JSONObject();put(o,"id",id);profiles.put(o);}
        // profile() returns another parsed list: update the entry in the list being saved.
        else for(int i=0;i<profiles.length();i++)if(id.equals(profiles.optJSONObject(i).optString("id"))){o=profiles.optJSONObject(i);break;}
        origin=AssistantEndpoint.validate(id,origin,lan);JSONObject previous=all.optJSONObject(o.optString("connectionId"));String secret=replacement.trim();if(secret.isEmpty()&&previous!=null)secret=decrypt(previous.optString("token"));if(secret.length()<32)throw new IOException("Введите полный токен доступа");
        String key=o.optString("connectionId");boolean same=previous!=null&&origin.equals(previous.optString("origin"))&&lan==previous.optBoolean("privateLan")&&secret.equals(decrypt(previous.optString("token")));
        if(!same){key=UUID.randomUUID().toString();JSONObject c=new JSONObject();put(c,"profileId",id);put(c,"origin",origin);put(c,"privateLan",lan);put(c,"token",encrypt(secret));put(c,"helperId","");put(all,key,c);}
        put(o,"name",name.trim());put(o,"connectionId",key);put(o,"disabled",false);p.edit().putString("assistantProfiles",profiles.toString()).putString("assistantConnections",all.toString()).commit();return id;
    }
    public static synchronized void remove(SharedPreferences p,String id){JSONArray a=list(p);for(int i=0;i<a.length();i++)if(id.equals(a.optJSONObject(i).optString("id")))put(a.optJSONObject(i),"disabled",true);p.edit().putString("assistantProfiles",a.toString()).commit();}
    public static synchronized void identity(SharedPreferences p,String id,String connectionId,String actual)throws IOException{JSONObject all=connections(p),c=all.optJSONObject(connectionId);if(c==null||!id.equals(c.optString("profileId"))||actual.isEmpty())throw new IOException("Мост не сообщил свою идентичность");String expected=c.optString("helperId");if(!expected.isEmpty()&&!expected.equals(actual))throw new IOException("Адрес отвечает другим помощником: "+actual);put(c,"helperId",actual);p.edit().putString("assistantConnections",all.toString()).commit();}
    public static String modelName(SharedPreferences p,String id,String model,String fallback){JSONObject o=profile(p,id),labels=o==null?null:o.optJSONObject("modelLabels");return labels==null?fallback:labels.optString(model,fallback);}
    public static String selectedModel(SharedPreferences p,String id){JSONObject o=profile(p,id);return o==null?"":o.optString("selectedModel");}
    public static synchronized void selectModel(SharedPreferences p,String id,String model){JSONArray a=list(p);for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(id.equals(o.optString("id")))put(o,"selectedModel",model);}p.edit().putString("assistantProfiles",a.toString()).commit();}
    public static synchronized void setModelName(SharedPreferences p,String id,String model,String label){JSONArray a=list(p);for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(!id.equals(o.optString("id")))continue;JSONObject labels=o.optJSONObject("modelLabels");if(labels==null){labels=new JSONObject();put(o,"modelLabels",labels);}if(label.trim().isEmpty())labels.remove(model);else put(labels,model,label.trim());}p.edit().putString("assistantProfiles",a.toString()).commit();}
}
