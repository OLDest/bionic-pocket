package ru.oldest.bionicpocket;

import org.json.*;
import java.util.*;

/** Shared background eligibility: completed history must never keep the radio busy. */
final class SyncPolicy {
    static final long BURST_MS=2*60*1000L;
    static final long BURST_INTERVAL_SECONDS=30;
    static final long JOB_INTERVAL_MS=15*60*1000L;
    static boolean burstExpired(long started,long now){return now-started>=BURST_MS;}
    static boolean pending(JSONArray messages){
        if(messages==null)return false;
        Set<String> finished=new HashSet<>();
        for(int i=0;i<messages.length();i++){
            JSONObject m=messages.optJSONObject(i);if(m==null)continue;
            String state=m.optString("codexReplyStatus");
            if(m.optBoolean("cancelledByOwner")||"completed".equals(state)||"blocked".equals(state)){
                for(String key:new String[]{"jobId","handoffId","codexReplyId"}){String id=m.optString(key);if(!id.isEmpty())finished.add(id);}
            }
        }
        for(int i=0;i<messages.length();i++){
            JSONObject m=messages.optJSONObject(i);if(m==null||!"assistant".equals(m.optString("role"))||m.optBoolean("cancelledByOwner"))continue;
            String job=m.optString("jobId"),handoff=m.optString("handoffId"),reply=m.optString("codexReplyId"),state=m.optString("codexReplyStatus");
            if("pending".equals(m.optString("state"))&&!finished.contains(job))return true;
            if(!handoff.isEmpty()&&!finished.contains(handoff))return true;
            if(!reply.isEmpty()&&("received".equals(state)||"in_progress".equals(state))&&!finished.contains(reply))return true;
        }
        return false;
    }
}
