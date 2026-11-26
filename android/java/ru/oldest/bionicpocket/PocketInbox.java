package ru.oldest.bionicpocket;

import android.content.SharedPreferences;
import org.json.*;
import java.util.*;

/** Routes bridge inbox replies without opening, selecting or restoring archived chats. */
final class PocketInbox {
    /** List enabled source connections even when the phone has no conversation for them. */
    static ArrayList<AssistantRegistry.Connection> connections(SharedPreferences prefs){
        ArrayList<AssistantRegistry.Connection> out=new ArrayList<>();JSONArray profiles=AssistantRegistry.profiles(prefs);
        for(int i=0;i<profiles.length();i++){
            JSONObject profile=profiles.optJSONObject(i);if(profile==null||profile.optBoolean("disabled"))continue;
            try{AssistantRegistry.Connection connection=AssistantRegistry.current(prefs,profile.optString("id"));if(!connection.origin.isEmpty())out.add(connection);}catch(Exception ignored){}
        }
        return out;
    }

    /** Merge each version once; the caller must commit chats and receipts before sending acknowledgements. */
    static JSONArray merge(JSONArray chats,JSONObject receipts,String assistant,String connection,String endpoint,JSONArray replies)throws JSONException{
        JSONArray acknowledgements=new JSONArray();if(replies==null)return acknowledgements;
        for(int i=0;i<replies.length();i++){
            JSONObject reply=replies.optJSONObject(i);if(reply==null)continue;
            String source=reply.optString("chatId"),id=reply.optString("handoffId"),version=reply.optString("version");
            if(source.isEmpty()||id.isEmpty()||version.isEmpty())continue;
            String key=connection+"/"+id+"/"+version;
            if(!receipts.optBoolean(key)){
                JSONObject target=null;
                for(int j=0;j<chats.length();j++){
                    JSONObject chat=chats.optJSONObject(j);if(chat==null||!assistant.equals(chat.optString("assistant"))||!endpoint.equals(chat.optString("endpoint",endpoint)))continue;
                    if(source.equals(chat.optString("id"))||source.equals(chat.optString("inboxSourceChatId"))){target=chat;break;}
                }
                if(target==null){
                    target=new JSONObject().put("id",UUID.randomUUID().toString()).put("inboxSourceChatId",source)
                        .put("assistant",assistant).put("connectionId",connection).put("endpoint",endpoint)
                        .put("title","Incoming message").put("project","").put("messages",new JSONArray());
                    chats.put(target);
                }
                if(!target.optBoolean("archived"))MainActivity.applyChatReplies(target,new JSONArray().put(reply));
                receipts.put(key,true);
            }
            acknowledgements.put(new JSONObject().put("handoffId",id).put("version",version));
        }
        return acknowledgements;
    }
}
