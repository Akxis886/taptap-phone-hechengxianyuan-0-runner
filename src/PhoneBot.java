import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

/** Runs with Android app_process as shell/root. No APK, Python, server or model in the loop. */
public class PhoneBot {
    static final File HOME = new File(System.getProperty("bot.home", "/data/local/tmp/taptap-phone"));
    static final String GAME = "com.taptap";
    static final List<Template> bank = new ArrayList<>();
    static int W=1440,H=3200,steps=100000,seconds=7200;
    static double scale=1, hintY=2594, left=0, boardW=1440, topY=0, cellH=332;
    static boolean verbose=false;
    static boolean ads=true,check=false,readOnly=false,adsOnly=false,yieldForAds=false;
    static int resultCode=0;
    static long end;
    static int adReloads=0;
    static class Template { int value; float[] pixels; Template(int v,float[] p){value=v;pixels=p;} }
    static class Node {
        String id,text,pkg,desc; int x,y,x0,y0,x1,y1; boolean enabled,clickable;
        Node(Element n) {
            id=n.getAttribute("resource-id");text=n.getAttribute("text");pkg=n.getAttribute("package");desc=n.getAttribute("content-desc");
            enabled="true".equals(n.getAttribute("enabled")); clickable="true".equals(n.getAttribute("clickable"));
            Matcher m=Pattern.compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").matcher(n.getAttribute("bounds"));
            if(m.matches()){x0=Integer.parseInt(m.group(1));y0=Integer.parseInt(m.group(2));x1=Integer.parseInt(m.group(3));y1=Integer.parseInt(m.group(4));x=(x0+x1)/2;y=(y0+y1)/2;}
        }
    }
    static byte[] run(String... cmd) throws Exception {
        Process p=new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> timeout=timer.schedule(()->p.destroy(),35,TimeUnit.SECONDS);
        try {
            ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] chunk=new byte[65536];int n;
            while((n=p.getInputStream().read(chunk))!=-1)b.write(chunk,0,n);
            if(p.waitFor()!=0)throw new IOException("Command failed: "+cmd[0]);
            return b.toByteArray();
        } finally { timeout.cancel(false);timer.shutdownNow();p.destroy(); }
    }
    static String sh(String s)throws Exception{return new String(run("/system/bin/sh","-c",s),"UTF-8");}

    static class Res { final int rc; final byte[] out; Res(int r, byte[] o){rc=r;out=o;} String text(){return new String(out, java.nio.charset.StandardCharsets.UTF_8);} }
    static Res runQuiet(String... cmd) {
        Process p = null;
        final ByteArrayOutputStream b = new ByteArrayOutputStream();
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            final Process pp = p;
            Thread t = new Thread(new Runnable(){ public void run(){ try { byte[] c=new byte[65536]; int n; while((n=pp.getInputStream().read(c))!=-1) b.write(c,0,n);} catch(IOException ignored){} } });
            t.setDaemon(true); t.start();
            if (!p.waitFor(35, TimeUnit.SECONDS)) { p.destroyForcibly(); return new Res(-1, b.toByteArray()); }
            return new Res(p.exitValue(), b.toByteArray());
        } catch (Exception e) { if (p != null) p.destroyForcibly(); return new Res(-1, b.toByteArray()); }
    }
    static void tapXY(int x,int y)throws Exception{run("/system/bin/input","tap",""+x,""+y);}
    static void screenshot(String name){ runQuiet("/system/bin/screencap","-p",new File(HOME,name).toString()); }
    static final String[] AD_MARK={"rewardactivity","portraitad","stub_standard","rewardvideo","adactivity"};
    static boolean isAd(String f){ if(f==null||!f.startsWith("com.taptap/"))return false;String l=f.toLowerCase();for(String k:AD_MARK)if(l.contains(k))return true;return false; }
    static boolean isTapAd(String f){ String l=f==null?"":f.toLowerCase();return l.contains("tapad"); }
    static String pkgOf(String f){ int i=f==null?-1:f.indexOf('/');return i>0?f.substring(0,i):null; }
    static List<Node> uiSafe() {
        File f=new File(HOME,"ui.xml");
        if(f.exists()&&!f.delete())return null;
        runQuiet("/system/bin/uiautomator","dump","--compressed",f.toString());
        if(!f.exists()||f.length()<200)return null;
        try {
            DocumentBuilderFactory fac=DocumentBuilderFactory.newInstance();fac.setExpandEntityReferences(false);
            Document d=fac.newDocumentBuilder().parse(f);List<Node> ns=new ArrayList<>();
            NodeList list=d.getElementsByTagName("node");
            for(int i=0;i<list.getLength();i++)ns.add(new Node((Element)list.item(i)));
            return ns.isEmpty()?null:ns;
        } catch(Exception e){ return null; }
    }
    static Node pick(List<Node> ns,int ymin,String[] exclude,String... words){
        if(ns==null)return null;Node best=null;int bestScore=Integer.MIN_VALUE;
        for(int wi=0;wi<words.length;wi++)for(Node n:ns){
            if(!n.enabled||n.x1<=n.x0||n.y1<=n.y0||n.y<ymin)continue;
            String s=n.text+" "+n.desc;
            boolean ex=false;if(exclude!=null)for(String e:exclude)if(s.contains(e)){ex=true;break;}
            if(ex||!s.contains(words[wi]))continue;
            int score=100-wi*10;if(score>bestScore){bestScore=score;best=n;}
        }
        return best;
    }
    static void log(String s){System.out.println("["+System.currentTimeMillis()+"] "+s);}
    static void pause(long ms)throws Exception{Thread.sleep(ms);}
    static List<Node> ui()throws Exception {
        List<Node> ns=uiSafe();
        if(ns==null)throw new IOException("UI dump unavailable");
        return ns;
    }
    static Node id(List<Node> ns,String id){for(Node n:ns)if(n.id.equals(id)&&n.enabled&&n.x1>n.x0&&n.y1>n.y0)return n;return null;}
    static Node anyId(List<Node> ns,String id){for(Node n:ns)if(n.id.equals(id)&&n.x1>n.x0&&n.y1>n.y0)return n;return null;}
    static Node word(List<Node> ns,String... words){for(String w:words)for(Node n:ns)if(n.enabled&&n.x1>n.x0&&n.y1>n.y0&&(n.text.equals(w)||n.desc.equals(w)))return n;return null;}
    static void tap(Node n)throws Exception{if(n==null)throw new IOException("Required button missing");run("/system/bin/input","tap",""+n.x,""+n.y);}
    // Focus of the top window; rejects a transient null focus instead of treating it as an ad.
    static String focus(){
        Matcher m=Pattern.compile("u0\\s+([\\w.]+)/([\\w.$]+)").matcher(runQuiet("/system/bin/sh","-c","dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -4").text());
        return m.find()?m.group(1)+"/"+m.group(2):"";
    }
    static boolean inGame()throws Exception{return focus().contains("H5GameActivity");}
    static void loadBank()throws Exception {
        try(DataInputStream in=new DataInputStream(new FileInputStream(new File(HOME,"labels.bin")))){
            if(in.readInt()!=0x44534831||in.readInt()!=96||in.readInt()!=24)throw new IOException("Invalid template bank");
            int n=in.readInt();if(n<1||n>1000)throw new IOException("Invalid template count");
            for(int i=0;i<n;i++){int v=in.readInt();float[] p=new float[2304];for(int j=0;j<p.length;j++)p[j]=in.readUnsignedByte()/255f;bank.add(new Template(v,p));}
        }
    }
    static class Frame {
        byte[] raw; int offset,bytes,format,w,h;
        Frame(byte[] d)throws Exception {
            if(d.length<16)throw new IOException("Capture unavailable");raw=d;
            ByteBuffer b=ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);w=b.getInt();h=b.getInt();format=b.getInt();
            if(w<200||h<200||w>8000||h>8000)throw new IOException("Invalid capture dimensions");
            bytes=(format==3?3:4);long pixels=(long)w*h*bytes;offset=(int)(d.length-pixels);
            if((offset!=12&&offset!=16)||(format!=1&&format!=2&&format!=3&&format!=5))throw new IOException("Unsupported capture format/header "+format+"/"+offset);
            W=w;H=h;
        }
        int color(int x,int y){int k=offset+(y*w+x)*bytes;int r=raw[k]&255,g=raw[k+1]&255,b=raw[k+2]&255;if(format==5){int t=r;r=b;b=t;}return(r<<16)|(g<<8)|b;}
    }
    static Frame capture()throws Exception{return new Frame(run("/system/bin/screencap"));}
    // Teal "exit and claim" pill drawn on the ad SurfaceView (0,227,204); uiautomator cannot see it.
    static int[] findCyan(Frame f){
        int step=4,best=0,bx=0,by=0;
        int y0=(int)(f.h*0.04),y1=(int)(f.h*0.36);
        for(int y=y0;y<y1;y+=step)for(int x=0;x<f.w;x+=step){
            int c=f.color(x,y),r=(c>>16)&255,g=(c>>8)&255,b=c&255;
            if(r<70&&g>190&&b>170&&b<235){
                int n=0;
                for(int dy=-8;dy<=8;dy+=4)for(int dx=-24;dx<=24;dx+=4){
                    int xx=x+dx,yy=y+dy;if(xx<0||yy<0||xx>=f.w||yy>=f.h)continue;
                    int c2=f.color(xx,yy),r2=(c2>>16)&255,g2=(c2>>8)&255,b2=c2&255;
                    if(r2<70&&g2>190&&b2>170&&b2<235)n++;
                }
                if(n>best){best=n;bx=x;by=y;}
            }
        }
        return best>=30?new int[]{bx,by}:null;
    }
    static boolean dark(int c){int r=c>>16,g=(c>>8)&255,b=c&255;return Math.max(r,Math.max(g,b))<170&&Math.min(r,Math.min(g,b))<100;}
    static boolean bright(int c){return Math.max(c>>16,Math.max((c>>8)&255,c&255))>185;}
    static String lastPatch="";
    static float[] patch(Frame f,double cx,double cy)throws Exception {
        int half=(int)((cellH>0?cellH:332)/2-14);
        int x0=Math.max(0,(int)(cx-150*scale)),y0=Math.max(0,(int)(cy-half));
        int w=Math.min(f.w,(int)(cx+150*scale))-x0,h=Math.min(f.h,(int)(cy+half))-y0;
        if(w<10||h<10)throw new IOException("Grid outside capture");
        boolean[] mask=new boolean[w*h];for(int y=0;y<h;y++)for(int x=0;x<w;x++)mask[y*w+x]=dark(f.color(x0+x,y0+y));
        int[] queue=new int[w*h];int bx=0,by=0,bw=0,bh=0;double best=-1e9;
        for(int start=0;start<mask.length;start++)if(mask[start]){
            int head=0,tail=1,minx=w,miny=h,maxx=0,maxy=0;queue[0]=start;mask[start]=false;
            while(head<tail){int v=queue[head++],x=v%w,y=v/w;minx=Math.min(minx,x);maxx=Math.max(maxx,x);miny=Math.min(miny,y);maxy=Math.max(maxy,y);
                for(int dir=0;dir<4;dir++){int nx=x+(dir==0?1:dir==1?-1:0),ny=y+(dir==2?1:dir==3?-1:0);if(nx>=0&&nx<w&&ny>=0&&ny<h&&mask[ny*w+nx]){mask[ny*w+nx]=false;queue[tail++]=ny*w+nx;}}
            }
            int cw=maxx-minx+1,ch=maxy-miny+1;
            if(miny>0&&maxy<h-1&&cw>75*scale&&cw<290*scale&&ch>24*scale&&ch<68*scale&&tail>cw*ch*.45){
                double dist=-(miny+ch/2.0);
                if(dist>best){best=dist;bx=minx;by=miny;bw=cw;bh=ch;lastPatch="w="+cw+" h="+ch+" fill="+String.format("%.2f",tail/(double)(cw*ch));}
            }
        }
        if(bw==0)return null;
        float[] out=new float[2304];int pad=Math.max(1,(int)(3*scale));
        for(int y=0;y<24;y++)for(int x=0;x<96;x++){
            // Area-like supersampling prevents small fonts from losing strokes.
            int hits=0;for(int sy=0;sy<3;sy++)for(int sx=0;sx<3;sx++){
                int px=(int)((x+(sx+.5)/3)*bw/96),py=(int)((y+(sy+.5)/3)*bh/24);
                if(px>=pad&&py>=pad&&px<bw-pad&&py<bh-pad&&bright(f.color(x0+bx+px,y0+by+py)))hits++;
            }out[y*96+x]=hits/9f;
        }return out;
    }
    static int classify(float[] p)throws Exception {
        if(p==null)return 0;double best=1e9,second=1e9;int value=-1;
        Map<Integer,Double> distances=new HashMap<>();
        for(Template t:bank){double err=0;for(int i=0;i<p.length;i++){double d=p[i]-t.pixels[i];err+=d*d;}err/=p.length;Double old=distances.get(t.value);if(old==null||err<old)distances.put(t.value,err);}
        for(Map.Entry<Integer,Double> e:distances.entrySet()){double d=e.getValue();if(d<best){second=best;best=d;value=e.getKey();}else second=Math.min(second,d);}
        if((best>.075&&(best>.105||second-best<.025))||second-best<.006)throw new IOException("Ambiguous label best="+best+" margin="+(second-best));
        return value;
    }
    static int[] board(Frame f)throws Exception {
        int[] b=new int[16];int n=0;for(int r=0;r<4;r++)for(int c=0;c<4;c++){
            int px=(int)(left+(224+c*332)*scale);
            int py=(int)(topY>0?topY+(r+1)*cellH-0.09*cellH:hintY-(194+(3-r)*332)*scale);
            lastPatch="";int v=0;
            try{float[] pp=patch(f,px,py);lastPatch=lastPatch;v=classify(pp);}
            catch(Exception ex){lastPatch="ERR:"+ex.getMessage()+" "+lastPatch;}
            b[r*4+c]=v;if(verbose)log("cell r="+r+" c="+c+" v="+v+" @("+px+","+py+") "+lastPatch);
            if(v>0)n++;
        }if(n==0)throw new IOException("No board labels found");return b;
    }
    static void calibrate(List<Node> ns)throws Exception {
        Node game=id(ns,"game");if(game==null)throw new IOException("Open 合成仙缘 inside TapTap first");
        left=game.x0;boardW=game.x1-game.x0;scale=boardW/1440.;
        boolean found=false;for(Node n:ns)if(n.text.contains("滑动合并")){hintY=n.y;found=true;break;}
        if(!found||id(ns,"screen-play")==null)throw new IOException("Start endless mode and dismiss tutorial first");
        if(hintY-1390*scale<game.y0||hintY>game.y1)throw new IOException("Unsupported game layout");
        log("layout width="+boardW+" scale="+scale+" hint="+hintY);
        measure();
    }
    static void measure()throws Exception{
        Frame f=capture();int lo=-1,hi=-1;
        for(int y=(int)(hintY-60);y>900;y-=2){
            int cnt=0,tot=0;
            for(int x=80;x<1360;x+=40){tot++;int c=f.color(x,y);int r=(c>>16)&255,g=(c>>8)&255,b=c&255;
                if(b>200&&b>r+18&&g>190&&Math.abs(g-b)<25)cnt++;}
            if(cnt*1.0/tot>0.30){if(hi<0)hi=y;lo=y;}
        }
        if(lo<0||hi-lo<1000){log("measure: board not found, keep hint-based mapping");return;}
        topY=lo;cellH=(hi-lo)/4.0;
        log("measure: board y="+lo+".."+hi+" cell="+cellH+" row0="+(int)(topY+.5*cellH)+" row3="+(int)(topY+3.5*cellH));
    }
    static int[] move(int[] b,int d){
        int[] out=new int[16];boolean changed=false;
        for(int line=0;line<4;line++){
            int[] ix=new int[4],v=new int[4];int n=0;
            for(int k=0;k<4;k++){int i=d==0?line*4+k:d==1?line*4+3-k:d==2?k*4+line:(3-k)*4+line;ix[k]=i;if(b[i]!=0)v[n++]=b[i];}
            int at=0;for(int k=0;k<n;k++){int val=v[k];if(k+1<n&&v[k+1]==val){val*=2;k++;}out[ix[at++]]=val;}
        }return Arrays.equals(b,out)?null:out;
    }
    static double eval(int[] b){
        double[] lg=new double[16];double empty=0,smooth=0,mono=0;int max=0;
        for(int i=0;i<16;i++){if(b[i]==0)empty++;else lg[i]=Math.log(b[i])/Math.log(2);max=Math.max(max,b[i]);}
        for(int r=0;r<4;r++)for(int c=0;c<4;c++){int i=r*4+c;if(b[i]>0){if(c<3&&b[i+1]>0)smooth-=Math.abs(lg[i]-lg[i+1]);if(r<3&&b[i+4]>0)smooth-=Math.abs(lg[i]-lg[i+4]);}}
        for(int line=0;line<4;line++){double a=0,z=0,aa=0,zz=0;for(int k=0;k<3;k++){double d=lg[line*4+k]-lg[line*4+k+1];a-=Math.max(d,0);z-=Math.max(-d,0);double e=lg[k*4+line]-lg[(k+1)*4+line];aa-=Math.max(e,0);zz-=Math.max(-e,0);}mono+=Math.max(a,z)+Math.max(aa,zz);}
        double corner=(b[0]==max||b[3]==max||b[12]==max||b[15]==max)?60:0;
        return 270*empty+11*smooth+47*mono+max+corner;
    }
    static double search(int[] b,int depth,boolean player){
        if(depth==0)return eval(b);
        if(player){double best=-1e9;for(int d=0;d<4;d++){int[] nb=move(b,d);if(nb!=null)best=Math.max(best,search(nb,depth-1,false));}return best;}
        List<Integer> empty=new ArrayList<>();for(int i=0;i<16;i++)if(b[i]==0)empty.add(i);if(empty.isEmpty())return eval(b);
        int n=Math.min(6,empty.size());double total=0;
        for(int k=0;k<n;k++){int i=empty.get(k*empty.size()/n);b[i]=2;total+=.9*search(b,depth-1,true)/n;b[i]=4;total+=.1*search(b,depth-1,true)/n;b[i]=0;}return total;
    }
    static int choose(int[] b){int empty=0;for(int v:b)if(v==0)empty++;int depth=empty>=10?3:empty>=6?4:empty>=3?5:6;double best=-1e18;int dir=-1;for(int d=0;d<4;d++){int[] nb=move(b,d);if(nb!=null){double s=search(nb,depth-1,false);if(s>best){best=s;dir=d;}}}return dir;}
    static void swipe(int dir)throws Exception {
        int cx=(int)(left+boardW/2),cy=(int)(hintY-700*scale),dx=(int)(400*scale),dy=(int)(400*scale);
        int x0=cx,y0=cy,x1=cx,y1=cy;
        if(dir==0){x0+=dx;x1-=dx;}if(dir==1){x0-=dx;x1+=dx;}if(dir==2){y0+=dy;y1-=dy;}if(dir==3){y0-=dy;y1+=dy;}
        run("/system/bin/input","swipe",""+x0,""+y0,""+x1,""+y1,"70");
    }
    static int seats(List<Node> ns){
        if(ns==null)return 0;int n=0;
        for(Node node:ns){
            String s=node.text+" "+node.desc;
            Matcher m=Pattern.compile("([1-9])\\s*席已开启").matcher(s);
            if(m.find())n=Math.max(n,Integer.parseInt(m.group(1)));
            Matcher d=Pattern.compile("(\\d+)\\s*位施法").matcher(s);
            if(d.find())n=Math.max(n,Integer.parseInt(d.group(1)));
        }
        return n;
    }
    static boolean stopped(){return new File(HOME,"STOP").exists()||System.currentTimeMillis()>end;}
    static void launchGame()throws Exception {
        run("/system/bin/monkey","-p",GAME,"-c","android.intent.category.LAUNCHER","1");pause(1000);
        for(int attempt=0;attempt<5&&!stopped();attempt++){
            if(inGame())return;
            List<Node> ns=ui();Node title=null;
            for(Node n:ns)if(n.id.endsWith("/tv_app_tag_title")&&n.text.startsWith("合成仙缘")){title=n;break;}
            if(title!=null){
                Node launch=null;for(Node n:ns)if(n.enabled&&n.desc.equals("启动")&&n.y>title.y&&n.y<title.y+(title.y1-title.y0)*4){if(launch==null||n.y<launch.y)launch=n;}
                if(launch==null)throw new IOException("Game is visible but its launch control was not found");
                tap(launch);pause(2000);continue;
            }
            Node library=null;for(Node n:ns)if(n.text.equals("我的游戏")&&n.y>2000*W/1440.){library=n;break;}
            if(library==null)throw new IOException("Open 合成仙缘 once in TapTap so it is in My Games");
            tap(library);pause(800);
        }
        if(!inGame())throw new IOException("Could not open saved game");
    }
    static List<Node> prepare(List<Node> ns)throws Exception {
        for(int loading=0;loading<8&&id(ns,"screen-play")==null&&id(ns,"btn-endless")==null&&id(ns,"btn-resume")==null;loading++){
            pause(1000);ns=ui();
        }
        if(id(ns,"screen-play")==null){
            Node resume=id(ns,"btn-resume");
            if(resume!=null)tap(resume);
            else {Node endless=id(ns,"btn-endless");if(endless==null)throw new IOException("Open the game first");tap(endless);pause(200);tap(id(ui(),"btn-start"));}
            pause(1000);ns=ui();
        }
        Node tutorial=word(ns,"知道了");if(tutorial!=null){tap(tutorial);pause(400);ns=ui();}
        Node mode=id(ns,"hud-mode");
        if(mode!=null&&!mode.text.contains("无尽")){
            log("prepare: current mode=\""+mode.text+"\", switching to endless");
            Node menu=id(ns,"btn-menu");
            if(menu!=null){tap(menu);pause(900);ns=uiSafe();}
            if(ns!=null){
                Node endless=id(ns,"btn-endless");
                for(Node n:ns)if(endless==null&&n.text.equals("无尽"))endless=n;
                if(endless!=null){tap(endless);pause(400);ns=uiSafe();}
            }
            if(ns!=null){
                Node start=id(ns,"btn-start");
                if(start==null)start=id(ns,"btn-resume");
                if(start!=null){tap(start);pause(1500);ns=uiSafe();}
            }
            mode=ns==null?null:id(ns,"hud-mode");
            if(mode!=null)log("prepare: mode now=\""+mode.text+"\"");
        }
        if(mode==null||!mode.text.contains("无尽"))throw new IOException("Requires endless mode; refusing to overwrite a different saved game");
        return ns;
    }
    static boolean recoverAd(List<Node> before)throws Exception {
        if(adReloads>=2||stopped()||before==null)return false;
        Node score=id(before,"hud-score");if(score==null)return false;String savedScore=score.text;
        Node menu=id(before,"com.taptap.app.middle:id/iv_menu");if(menu==null)return false;
        tap(menu);List<Node> ns=uiSafe();Node restart=ns==null?null:word(ns,"重新启动");
        if(restart==null){run("/system/bin/input","keyevent","4");return false;}
        tap(restart);adReloads++;
        Node after=null;
        for(int poll=0;poll<10&&!stopped();poll++){pause(1200);ns=uiSafe();after=ns==null?null:id(ns,"hud-score");if(after!=null)break;}
        if(after==null||!after.text.equals(savedScore))throw new IOException("Reload could not verify preserved score; stopped");
        calibrate(ns);log("ad recovery: reloaded with preserved score="+savedScore+" reloads="+adReloads);return true;
    }
    // Close any ad layer: force-stop third-party landing pages, tap explicit exit controls, BACK only as fallback.
    static boolean dismissAd()throws Exception {
        long t0=System.currentTimeMillis();
        for(int round=1;round<=14&&!stopped()&&System.currentTimeMillis()-t0<120000;round++){
            String f=focus();
            if(f.contains("H5GameActivity"))return true;
            String p=pkgOf(f);
            if(p!=null&&!p.equals("com.taptap")){log("dismiss force-stop "+p);sh("am force-stop "+p);pause(1600);continue;}
            List<Node> ns=uiSafe();
            Node hit=ns==null?null:pick(ns,0,new String[]{"去体验","立即领","立即下载","继续试玩"},"坚持退出","残忍离开","确认退出","退出并领奖","奖励已领取","跳过","关闭");
            if(hit!=null){log("dismiss"+round+" "+hit.text+" @"+hit.x+","+hit.y);tap(hit);pause(1400);}
            else {log("dismiss"+round+" BACK (ui="+(ns==null?"unavailable":"no button")+")");run("/system/bin/input","keyevent","4");pause(1400);}
        }
        log("dismiss failed focus="+focus());
        return focus().contains("H5GameActivity");
    }
    static boolean ad(List<Node> before)throws Exception {
        if(before==null||adReloads>=3)return false;
        Node button=anyId(before,"btn-caster-ad");
        if(button==null){log("ad button absent");return false;}
        int count=seats(before);
        if(button.text.contains("进行中")){log("ad still marked in progress");recoverAd(before);return false;}
        if(count>=3)return false;
        if(!button.text.contains("视频")){log("ad locked: "+button.text);return false;}
        log("ad request seats="+count+" button="+button.text);
        tap(button);pause(700);
        List<Node> ns=uiSafe();
        Node confirm=ns==null?null:id(ns,"ad-confirm-yes");
        if(confirm==null&&ns!=null)confirm=pick(ns,0,null,"看视频","观看视频");
        if(confirm==null){log("ad confirmation missing");screenshot("ad-confirm-missing.png");return false;}
        log("ad confirm @"+confirm.x+","+confirm.y);
        tap(confirm);
        long t0=System.currentTimeMillis();
        String adf=null;
        for(int i=0;i<16&&!stopped();i++){
            pause(600);
            String f=focus();
            if(isAd(f)){adf=f;break;}
        }
        if(adf==null){log("ad not shown: no fill or loading failed");screenshot("ad-not-shown.png");return false;}
        log("ad appeared "+adf+" after "+((System.currentTimeMillis()-t0)/1000)+"s");
        if(isTapAd(adf)){log("TapAD placeholder ad: dismiss and retry");dismissAd();return false;}
        boolean ctaDone=false;long browseT=0;int closeRound=0;String lastXY=null;
        List<String> seen=new ArrayList<>();
        while(!stopped()&&System.currentTimeMillis()-t0<180000){
            String f=focus();
            if(f.contains("H5GameActivity")){
                List<Node> g=uiSafe();
                if(g==null){pause(700);continue;}
                int after=seats(g);
                log("ad returned after "+((System.currentTimeMillis()-t0)/1000)+"s seats "+count+" -> "+after);
                if(after<=count){pause(1500);List<Node> again=uiSafe();if(again!=null)after=Math.max(after,seats(again));}
                return after>count;
            }
            String p=pkgOf(f);
            if(p!=null&&!p.equals("com.taptap")&&!seen.contains(p)){seen.add(p);log("ad landing page "+p);}
            long el=System.currentTimeMillis()-t0;
            if(!ctaDone&&el>1500){
                List<Node> au=uiSafe();
                Node cta=au==null?null:pick(au,(int)(H*0.32),new String[]{"奖励已领取","可立即领","可提前领"},"我要加速","继续试玩","点击屏幕","立即下载");
                if(cta!=null){log("ad CTA "+cta.text+" @"+cta.x+","+cta.y);tap(cta);browseT=System.currentTimeMillis();pause(1000);}
                else log("ad no CTA yet; letting the video play");
                ctaDone=true;continue;
            }
            if(browseT>0&&System.currentTimeMillis()-browseT<15000){pause(900);continue;}
            if(browseT>0){
                browseT=0;
                for(String bg:seen){log("ad force-stop "+bg);sh("am force-stop "+bg);}
                seen.clear();pause(1600);continue;
            }
            if(el<35000){pause(1500);continue;}
            // After the clip has played, leave via its "exit and claim" control.
            // Prefer real text nodes; most templates draw the pill on the Surface, so fall back to pixels.
            closeRound++;
            if(closeRound%3==1){
                List<Node> cu=uiSafe();
                Node close=cu==null?null:pick(cu,0,new String[]{"去体验","继续试玩","立即领"},"奖励已领取","领取奖励并关闭","退出并领奖","领取奖励");
                if(close!=null){
                    log("ad exit "+close.text+" @"+close.x+","+close.y+" at "+el/1000+"s");
                    tap(close);pause(2500);continue;
                }
            }
            Frame fr=null;try{fr=capture();}catch(Exception e){log("ad capture failed: "+e.getMessage());}
            int[] cb=fr==null?null:findCyan(fr);
            if(cb!=null){
                log("ad exit button @"+cb[0]+","+cb[1]+" at "+el/1000+"s");
                tapXY(cb[0],cb[1]);pause(2800);continue;
            }
            log("ad wait at "+el/1000+"s (no exit control)");
            pause(1500);
        }
        log("ad timed out after "+((System.currentTimeMillis()-t0)/1000)+"s focus="+focus());
        dismissAd();
        return false;
    }

    static void selftest()throws Exception {
        int[] b={2,2,2,2,0,0,0,0,0,0,0,0,0,0,0,0};int[] m=move(b,0);
        if(m[0]!=4||m[1]!=4||m[2]!=0)throw new AssertionError("merge once");
        int[] dead={2,4,2,4,4,2,4,2,2,4,2,4,4,2,4,2};if(choose(dead)!=-1)throw new AssertionError("dead board");
        if(choose(b)<0)throw new AssertionError("legal move");log("SELFTEST PASS");
    }
    public static void main(String[] args) {
        try { execute(args); if(resultCode!=0)System.exit(resultCode); }
        catch(Throwable error){System.err.println("STOPPED: "+error);error.printStackTrace(System.err);System.exit(2);}
    }
    static void execute(String[] args)throws Exception {
        if(args.length==3&&args[0].equals("--fixture")){
            loadBank();ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=new FileInputStream(args[1])){byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);}
            Frame f=new Frame(out.toByteArray());boardW=f.w;scale=f.w/1440.;hintY=Double.parseDouble(args[2]);log("fixture="+Arrays.toString(board(f)));return;
        }
        for(int i=0;i<args.length;i++){
            switch(args[i]){case "--selftest":selftest();return;case "--check":check=true;verbose=true;break;case "--read":readOnly=true;verbose=true;break;case "--ads-only":adsOnly=true;break;case "--yield-for-ads":yieldForAds=true;ads=false;break;case "--skip-ads":ads=false;break;case "--steps":steps=Integer.parseInt(args[++i]);break;case "--seconds":seconds=Integer.parseInt(args[++i]);break;default:throw new IllegalArgumentException(args[i]);}
        }
        HOME.mkdirs();
        try(RandomAccessFile lock=new RandomAccessFile(new File(HOME,"run.lock"),"rw");FileChannel channel=lock.getChannel()){
            FileLock held=channel.tryLock();if(held==null)throw new IOException("Already running");
            try{
                String uid=sh("id -u").trim();if(!uid.equals("2000")&&!uid.equals("0"))throw new IOException("Requires Android shell uid 2000/root; ordinary DSH Linux shell cannot inject input");
                loadBank();end=System.currentTimeMillis()+seconds*1000L;capture();
                if(!inGame()){
                    if(check||readOnly)throw new IOException("Open TapTap 合成仙缘 endless game first");
                    launchGame();
                }
                List<Node> ns=uiSafe();
                if(ns==null)throw new IOException("Could not read game UI");
                if(!check&&!readOnly)ns=prepare(ns);
                calibrate(ns);
                int[] previous=null;int unchanged=0,errors=0;
                if(adsOnly){
                    for(int attempt=0;attempt<6&&!stopped();attempt++){
                        List<Node> cur=uiSafe();
                        if(cur==null){log("ad loop: ui unavailable; retry");pause(1200);continue;}
                        int s=seats(cur);
                        if(s>=3){log("ADS PASS: three seats verified");return;}
                        log("ad loop attempt="+attempt+" seats="+s);
                        if(ad(cur)){List<Node> r=uiSafe();if(r!=null)calibrate(r);}
                        pause(1500);
                    }
                    throw new IOException("ADS incomplete: target seats not verified");
                }
                long begin=System.currentTimeMillis();
                for(int step=0;step<steps&&!stopped();){
                    long t=System.currentTimeMillis();int[] b;
                    try{b=board(capture());errors=0;}catch(IOException e){log("read retry: "+e.getMessage());if(++errors>=3)throw e;pause(350);continue;}
                    log("board="+Arrays.toString(b));
                    if(check||readOnly){log("CHECK PASS: capture/layout/labels; no touch injected");return;}
                    if(Arrays.equals(b,previous)){if(++unchanged>=4)throw new IOException("Board unchanged after swipes; check input permission/focus");pause(300);continue;}unchanged=0;
                    if(step%20==0){
                        if(!inGame())throw new IOException("Foreground changed; stopped");
                        ns=uiSafe();
                        if(ns==null){log("ui unavailable; retry later");pause(400);continue;}
                        if(ads&&ad(ns)){List<Node> r=uiSafe();if(r!=null)calibrate(r);previous=null;continue;}
                        if(yieldForAds){Node button=id(ns,"btn-caster-ad");if(button!=null&&button.text.contains("看视频")&&seats(ns)<3){log("ADS_READY seats="+seats(ns));resultCode=20;return;}}
                        if(!inGame())throw new IOException("Ad did not return to game; stopped");
                    }
                    int dir=choose(b);if(dir<0){pause(350);int[] verify=board(capture());if(!Arrays.equals(b,verify))continue;log("DEAD BOARD: stopped without restart");break;}
                    if(!inGame())throw new IOException("Foreground changed before gesture");
                    swipe(dir);previous=b;pause(210);step++;
                    log("step="+step+" direction="+dir+" ms="+(System.currentTimeMillis()-t)+" mean_ms="+((System.currentTimeMillis()-begin)/step));
                }
            }finally{held.release();}
        }
    }
}
