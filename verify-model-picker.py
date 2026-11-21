"""Exercise actual production request pinning using the existing JVM flow doubles."""
import pathlib,runpy,subprocess,os
root=pathlib.Path(__file__).resolve().parent
base=runpy.run_path(str(root/'verify-delivery-flow.py'))
method=base['declaration']('    static void pinSelectedModel(')+'\n'+base['declaration']('    static void restoreRoute(')
out=root/'build/delivery-verification'
probe=(out/'MainFlowProbe.java').read_text(encoding='utf-8').replace('MainFlowProbe','ModelPickerProbe')
start=probe.index('  public static void main(String[] args){');end=probe.index('    private void submit(',start)
probe=probe[:start]+'''  public static void main(String[] args){
 JSONArray gonets=new JSONArray().put(new JSONObject().put("id","nine")).put(new JSONObject().put("id","large"));
 JSONArray domovoy=new JSONArray().put(new JSONObject().put("id","office"));
 JSONObject chat=new JSONObject().put("model","large"),request=new JSONObject().put("requestId","fixed-id");
 pinSelectedModel(request,chat,gonets);check("large".equals(request.optString("model")),"selected model absent from request");
 chat.put("model","nine");check("large".equals(request.optString("model")),"pending request model changed with UI");
 boolean rejected=false;try{pinSelectedModel(new JSONObject(),chat,domovoy);}catch(IllegalStateException expected){rejected=true;}check(rejected,"model leaked to another assistant");
 rejected=false;try{pinSelectedModel(new JSONObject(),chat,new JSONArray());}catch(IllegalStateException expected){rejected=true;}check(rejected,"unavailable model silently accepted");
 JSONObject legacy=new JSONObject(),defaultRequest=new JSONObject();pinSelectedModel(defaultRequest,legacy,new JSONArray());check(!defaultRequest.has("model"),"legacy server forced a model");
 chat.put("model","office");JSONObject office=new JSONObject();pinSelectedModel(office,chat,domovoy);check("office".equals(office.optString("model")),"office selection lost");
 JSONObject legacyOffice=new JSONObject();restoreRoute(legacyOffice,"domovoy","http://192.168.1.120:1236");check("domovoy".equals(legacyOffice.optString("assistantId"))&&legacyOffice.optString("endpoint").contains("192.168.1.120"),"legacy office request routed to Gonets");
 restoreRoute(legacyOffice,"gonets","https://other.example");check("domovoy".equals(legacyOffice.optString("assistantId")),"pinned route overwritten");
 JSONObject unconfigured=new JSONObject();restoreRoute(unconfigured,"domovoy","");check(unconfigured.optString("endpoint").isEmpty(),"unconfigured office silently uses Gonets");
 System.out.println("PASS: production model pinning, immutable assistant origins and correct legacy office route");
 }'''+probe[end:]
probe=probe.rsplit('}',1)[0]+method+'\n}\n';(out/'ModelPickerProbe.java').write_text(probe,encoding='utf-8')
jdk=pathlib.Path(os.environ['JAVA_HOME']);rtk=base['rtk']
for args in [[jdk/'bin/javac.exe','-encoding','UTF-8','-cp',out,'-d',out,out/'ModelPickerProbe.java'],[jdk/'bin/java.exe','-cp',out,'ModelPickerProbe']]:subprocess.run([rtk,'proxy',*[str(a) for a in args]],cwd=root,check=True)
