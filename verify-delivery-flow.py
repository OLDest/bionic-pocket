"""Run actual extracted Android delivery methods on JVM with deterministic I/O doubles."""
import os,pathlib,re,subprocess,sys
ROOT=pathlib.Path(__file__).resolve().parent
worker=pathlib.Path(sys.argv[1]).resolve() if len(sys.argv)>1 else ROOT
source=(worker/'android/java/ru/oldest/bionicpocket/MainActivity.java').read_text(encoding='utf-8-sig')
def declaration(marker):
    start=source.index(marker); pos=source.index('{',start); depth=0; quote=None; comment=None; escape=False; i=pos
    while i<len(source):
        c=source[i];n=source[i+1:i+2]
        if comment=='line':
            if c=='\n':comment=None
        elif comment=='block':
            if c=='*' and n=='/':comment=None;i+=1
        elif quote:
            if escape:escape=False
            elif c=='\\':escape=True
            elif c==quote:quote=None
        elif c=='/' and n=='/':comment='line';i+=1
        elif c=='/' and n=='*':comment='block';i+=1
        elif c in ['"',"'"]:quote=c
        elif c=='{':depth+=1
        elif c=='}':
            depth-=1
            if depth==0:return source[start:i+1]
        i+=1
    raise RuntimeError('Unclosed Java declaration '+marker)
exception_match=re.search(r'(?m)^\s*(?:(?:private|public|protected|static|final)\s+)*class ApiException\b',source)
if not exception_match:raise RuntimeError('Production ApiException is missing')
exception=declaration(exception_match.group(0))
imports='import java.io.*; import java.net.*; import java.util.*; import java.util.concurrent.*; import javax.net.ssl.*; import java.security.cert.Certificate; import java.nio.charset.StandardCharsets; import ru.oldest.bionicpocket.DeliveryPolicy;\n'
json_double=r'''
static class JSONObject {
  final Map<String,Object> data=new LinkedHashMap<>();
  JSONObject(){}
  JSONObject(String body){
    if(!body.trim().startsWith("{")||!body.trim().endsWith("}"))throw new IllegalArgumentException("not JSON");
    java.util.regex.Matcher m=java.util.regex.Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
    while(m.find())data.put(m.group(1),m.group(2));
  }
  JSONObject put(String k,Object v){data.put(k,v);return this;}
  String optString(String k){return optString(k,"");}
  String optString(String k,String fallback){Object v=data.get(k);return v==null?fallback:String.valueOf(v);}
  String getString(String k){if(!data.containsKey(k))throw new IllegalArgumentException(k);return optString(k);}
  long optLong(String k,long fallback){Object v=data.get(k);return v instanceof Number?((Number)v).longValue():fallback;}
  boolean optBoolean(String k){return Boolean.TRUE.equals(data.get(k));}
  JSONArray optJSONArray(String k){return (JSONArray)data.get(k);}
  JSONObject optJSONObject(String k){return (JSONObject)data.get(k);}
  public String toString(){return data.toString();}
}
static class JSONArray {
  final List<JSONObject> data=new ArrayList<>();
  JSONArray put(JSONObject v){data.add(v);return this;}
  int length(){return data.size();}
  JSONObject optJSONObject(int i){return data.get(i);}
}
static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
'''
flow=r'''
  static class MessageNotifications {static boolean busy;}
  void notifyReceived(){}
  static long serverTime(String value){try{return java.time.Instant.parse(value).toEpochMilli();}catch(Exception e){return 0;}}
  boolean networkBusy; String token="fixture",pollError="";
  final Executor io=Runnable::run;
  static class Handler {void post(Runnable r){r.run();}}
  final Handler main=new Handler();
  final JSONArray messages=new JSONArray();
  final JSONArray chats=new JSONArray().put(new JSONObject().put("messages",messages));
  final Deque<Object> responses=new ArrayDeque<>();
  static class Call {String route;JSONObject payload;Call(String r,JSONObject p){route=r;payload=p;}}
  final List<Call> calls=new ArrayList<>();
  int saves,renders;
  JSONArray entries(){return messages;} void render(){renders++;} void save(){saves++;}
  static void put(JSONObject j,String k,Object v){j.put(k,v);}
  JSONObject api(String route,JSONObject payload)throws Exception {
    calls.add(new Call(route,payload));Object next=responses.removeFirst();
    if(next instanceof Exception)throw (Exception)next;return (JSONObject)next;
  }
  static JSONObject done(){return new JSONObject().put("state","completed").put("answer","delivered");}
  static MainFlowProbe fixture(){MainFlowProbe f=new MainFlowProbe();JSONObject request=new JSONObject().put("requestId","fixed-id").put("text","hello");f.messages.put(new JSONObject().put("state","pending").put("jobId","fixed-id").put("request",request));return f;}
  JSONObject answer(){return messages.optJSONObject(0);}
  static void expectState(MainFlowProbe f,String expected){check(expected.equals(f.answer().optString("state")),"Expected "+expected+", got "+f.answer().optString("state"));check(!f.networkBusy,"Network busy flag must reset");}
  public static void main(String[] args){
    for(int status:new int[]{0,408,429,500,503,599})check(DeliveryPolicy.isRetryable(status),"retry "+status);
    for(int status:new int[]{200,400,401,403,404,409})check(!DeliveryPolicy.isRetryable(status),"permanent "+status);
    check(DeliveryPolicy.isGetNotFound("GET",404),"GET404 decision");check(!DeliveryPolicy.isGetNotFound("POST",404),"POST404 must not recover as GET");
    MainFlowProbe f=fixture();f.responses.add(new IOException("offline"));f.submit(f.answer());expectState(f,"pending");
    check("fixed-id".equals(f.answer().optString("jobId")),"offline changes ID");
    f.responses.add(done());f.poll();expectState(f,"completed");check("delivered".equals(f.answer().optString("content")),"reply lost");
    check(f.calls.size()==2&&f.calls.get(1).payload==null,"lost acknowledgement must GET before repost");
    f=fixture();JSONObject original=f.answer().optJSONObject("request");f.responses.add(new ApiException("missing",404));f.responses.add(done());f.poll();expectState(f,"completed");
    check(f.calls.size()==2,"GET404 did not repost");check(f.calls.get(0).route.equals("/mobile/jobs/fixed-id"),"wrong GET route");
    check(f.calls.get(1).route.equals("/mobile/messages")&&f.calls.get(1).payload==original,"repost must use exact saved request");
    check("fixed-id".equals(original.optString("requestId")),"automatic recovery generated another ID");
    for(int status:new int[]{408,429,500,503}){f=fixture();f.responses.add(new ApiException("temporary",status));f.submit(f.answer());expectState(f,"pending");}
    for(int status:new int[]{400,401,403,404,409}){f=fixture();f.responses.add(new ApiException("permanent",status));f.submit(f.answer());expectState(f,"error");}
    for(int status:new int[]{400,401,403,409}){f=fixture();f.responses.add(new ApiException("permanent",status));f.poll();expectState(f,"error");check(f.calls.size()==1,"permanent GET unexpectedly reposted");}
    f=fixture();f.responses.add(new IOException("offline"));f.poll();expectState(f,"pending");check(!f.pollError.contains("Ответ заберу"),"false guaranteed-answer promise remains");
    f=fixture();f.responses.add(new JSONObject().put("state","failed").put("error","model stopped"));f.poll();expectState(f,"error");
    check(f.answer().optBoolean("retryNew"),"server failure must permit explicit new attempt");
    f.responses.add(done());f.retry(f.answer());expectState(f,"completed");check(!"fixed-id".equals(f.answer().optString("jobId")),"manual failed-job retry must have fresh ID");
    f=fixture();f.apply(f.answer(),new JSONObject().put("state","running").put("answer","Queued, not yet confirmed"));expectState(f,"pending");check("Queued, not yet confirmed".equals(f.answer().optString("content")),"pending receipt text lost");
    f.apply(f.answer(),new JSONObject().put("state","completed").put("answer","Receipt confirmed"));expectState(f,"completed");check("Receipt confirmed".equals(f.answer().optString("content")),"confirmed receipt lost");
    f=fixture();JSONArray updates=new JSONArray();JSONObject reply=new JSONObject().put("handoffId","h").put("version","one").put("status","received").put("message","Receipt from actual Codex");updates.put(reply);
    check(f.applyCodexReplies(f.messages,updates),"receipt not applied");check(f.messages.length()==2,"receipt missing");check(f.messages.optJSONObject(1).optString("content").contains("Receipt from actual Codex"),"receipt text lost");
    check(!f.applyCodexReplies(f.messages,updates)&&f.messages.length()==2,"duplicate reply created another message");reply.put("version","two").put("status","completed").put("message","Actual result with checks");
    check(f.applyCodexReplies(f.messages,updates)&&f.messages.length()==2,"result must update same card");check(f.messages.optJSONObject(1).optString("content").contains("Actual result with checks"),"final result lost");
    f=fixture();f.apply(f.answer(),new JSONObject().put("state","completed").put("answer","dated").put("createdAt","2026-11-20T12:00:00Z").put("completedAt","2026-11-20T12:01:00Z"));
    long received=f.answer().optLong("receivedAt",0);check(received>0,"missing receive time");check(f.answer().optLong("sentAt",0)==java.time.Instant.parse("2026-11-20T12:01:00Z").toEpochMilli(),"wrong server send time");
    f.apply(f.answer(),new JSONObject().put("state","completed").put("answer","dated"));check(f.answer().optLong("receivedAt",0)==received,"duplicate changed receive time");
    check(f.answer().optLong("requestAcceptedAt",0)>0,"missing server acceptance time");
    System.out.println("PASS: actual submit/poll/retry/apply, offline/404/429/5xx/auth and request-ID recovery");
  }
'''
api=r'''
  String token="fixture",server="https://fixture.invalid";
  static int status;static String body;static boolean offline;
  static class FakeConnection extends HttpsURLConnection {
    FakeConnection(URL url){super(url);} public void connect(){} public void disconnect(){} public boolean usingProxy(){return false;}
    public String getCipherSuite(){return "fixture";}public Certificate[] getLocalCertificates(){return null;}public Certificate[] getServerCertificates(){return null;}
    public int getResponseCode()throws IOException{if(offline)throw new IOException("offline");return status;}
    public InputStream getInputStream(){return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));}
    public InputStream getErrorStream(){return getInputStream();}public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
  }
  public static void main(String[] args)throws Exception {
    URL.setURLStreamHandlerFactory(protocol->"https".equals(protocol)?new URLStreamHandler(){protected URLConnection openConnection(URL url){return new FakeConnection(url);}}:null);
    MainApiProbe p=new MainApiProbe();
    for(int code:new int[]{400,401,403,404,409,429,500,503})for(String data:new String[]{"{}","not JSON"}){
      status=code;body=data;try{p.api("/mobile/jobs/fixed-id",null);throw new AssertionError("HTTP error accepted");}catch(ApiException error){check(error.status==code,"HTTP status lost: "+code);}
    }
    status=200;body="{}";check(p.api("/mobile/messages",new JSONObject())!=null,"success rejected");
    offline=true;try{p.api("/mobile/jobs/fixed-id",null);throw new AssertionError("offline accepted");}catch(IOException expected){}
    System.out.println("PASS: actual api preserves HTTP status for JSON/non-JSON errors and transport failures");
  }
'''
out=worker/'build/delivery-verification';out.mkdir(parents=True,exist_ok=True)
(out/'MainFlowProbe.java').write_text(imports+'public class MainFlowProbe {\n'+json_double+'\n'+exception+'\n'+flow+'\n'+'\n'.join(declaration(('    static boolean ' if name=='applyCodexReplies' else '    static void ' if name=='apply' else '    private void ')+name+'(') for name in ['submit','poll','retry','apply','applyCodexReplies'])+'\n}\n',encoding='utf-8')
(out/'MainApiProbe.java').write_text(imports+'public class MainApiProbe {\n'+json_double+'\n'+exception+'\n'+api+'\n'+declaration('    private JSONObject api(')+'\n'+declaration('    private static String read(')+'\n}\n',encoding='utf-8')
tools=pathlib.Path('C:/Users/OLDest/Documents/Codex/2026-11-15/go-https-pikabu-ru-story-yandeks/yago/.tools')
jdk=pathlib.Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else next((tools/'jdk').glob('jdk-*'))
rtk='C:/Users/OLDest/.local/bin/rtk.exe'
def run(*args):subprocess.run([rtk,'proxy',*[str(a) for a in args]],check=True,cwd=worker)
run(jdk/'bin/javac.exe','-encoding','UTF-8','-d',out,out/'MainFlowProbe.java',out/'MainApiProbe.java',worker/'android/java/ru/oldest/bionicpocket/DeliveryPolicy.java')
run(jdk/'bin/java.exe','-cp',out,'MainApiProbe')
run(jdk/'bin/java.exe','-cp',out,'MainFlowProbe')
print('Production delivery method checks passed; Android UI/device behavior is outside this JVM harness.')
