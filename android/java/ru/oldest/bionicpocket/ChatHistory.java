package ru.oldest.bionicpocket;

import org.json.*;
import java.util.*;

/** Local deletion markers survive restarts and suppress late/replayed server replies. */
final class ChatHistory {
    private static void put(JSONObject object,String key,Object value){try{object.put(key,value);}catch(JSONException error){throw new IllegalArgumentException(error);}}
    private static JSONObject hidden(JSONObject chat){JSONObject hidden=chat.optJSONObject("hiddenMessages");if(hidden==null){hidden=new JSONObject();put(chat,"hiddenMessages",hidden);}return hidden;}
    private static void hide(JSONObject hidden,JSONObject message){for(String key:new String[]{"jobId","handoffId","codexReplyId"}){String id=message.optString(key);if(!id.isEmpty())put(hidden,id,true);}}
    static boolean suppressed(JSONObject chat,JSONObject reply){
        JSONObject hidden=chat.optJSONObject("hiddenMessages");
        for(String key:new String[]{"jobId","requestId","handoffId","codexReplyId"}){String id=reply.optString(key);if(!id.isEmpty()&&hidden!=null&&hidden.optBoolean(id))return true;}
        long cleared=chat.optLong("historyClearedAt",0);
        String time=reply.optString("createdAt");if(time.isEmpty())time=reply.optString("updatedAt");
        if(cleared>0&&!time.isEmpty())try{if(java.time.Instant.parse(time).toEpochMilli()<=cleared)return true;}catch(Exception ignored){}
        return false;
    }
    static JSONArray visibleReplies(JSONObject chat,JSONArray replies){
        JSONArray keep=new JSONArray();if(replies==null)return keep;
        for(int i=0;i<replies.length();i++){JSONObject reply=replies.optJSONObject(i);if(reply!=null&&!suppressed(chat,reply))keep.put(reply);}
        return keep;
    }
    static int delete(JSONObject chat,Set<JSONObject> selected){
        JSONArray messages=chat.optJSONArray("messages");if(messages==null)return 0;
        JSONObject hidden=hidden(chat);Set<String> removedIds=new HashSet<>();
        for(int i=0;i<messages.length();i++){JSONObject m=messages.optJSONObject(i);if(m!=null&&selected.contains(m)&&"assistant".equals(m.optString("role"))){hide(hidden,m);for(String key:new String[]{"jobId","handoffId","codexReplyId"}){String id=m.optString(key);if(!id.isEmpty())removedIds.add(id);}}}
        int count=0;
        for(int i=messages.length()-1;i>=0;i--){JSONObject m=messages.optJSONObject(i);if(m==null)continue;boolean duplicate=false;if("assistant".equals(m.optString("role")))for(String key:new String[]{"jobId","handoffId","codexReplyId"})if(removedIds.contains(m.optString(key)))duplicate=true;if(selected.contains(m)||duplicate){messages.remove(i);count++;}}
        return count;
    }
    static int clear(JSONObject chat,long now){
        JSONArray messages=chat.optJSONArray("messages");if(messages==null)return 0;
        Set<JSONObject> all=Collections.newSetFromMap(new IdentityHashMap<JSONObject,Boolean>());for(int i=0;i<messages.length();i++)if(messages.optJSONObject(i)!=null)all.add(messages.optJSONObject(i));
        int count=delete(chat,all);put(chat,"historyClearedAt",now);return count;
    }
}
