package dev.android.dynamicisland.island.material;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import dev.android.dynamicisland.island.view.media.MediaFx;
import dev.android.dynamicisland.settings.ModuleSettings;
import dev.android.dynamicisland.xposed.Diagnostics;
import dev.android.dynamicisland.xposed.XLog;

/** One stable GPU material per card; capture updates never select another renderer. */
public final class FrostedSurface {
    private static final long CAPTURE_BLEND_MS=90;
    private static final long MISSING_FRAME_GRACE_MS=200;
    /** The response field is a blurred low-frequency map: 10 Hz is plenty while a card morphs. */
    private static final long FIELD_REFRESH_MS=100;
    private static final class EmptyBackdrop {
        static final Bitmap PIXEL=Bitmap.createBitmap(new int[]{0xff000000},1,1,Bitmap.Config.ARGB_8888);
    }
    private static boolean unavailable;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private RuntimeShader shader;
    private Input previous,current;
    private boolean blending;
    private long blendStartedAt,missingSince=-1,lastTime,lastInputReport;
    private final RectF lastBounds=new RectF();
    private float lastCorner=-1,phase;
    private boolean primed;
    private String lastMaterialState;
    /** The tone uniforms are already zero, so a silent card need not rewrite them every frame. */
    private boolean tonesCleared;
    private final float[] common=new float[12],edge=new float[16],shadow=new float[16];
    /** Written by fadeInto so configureLight allocates nothing per frame. */
    private float fadeA,fadeB;

    public static boolean requested() {
        return !unavailable&&ModuleSettings.frostedMaterial()&&!ModuleSettings.lowPerformance();
    }
    public static boolean canUse(MaterialFrame frame) {
        return requested()&&frame!=null&&!frame.bitmap.isRecycled();
    }
    public static boolean canUse(MaterialFrame frame,RectF bounds) {
        return canUse(frame)&&bounds.right>frame.screenBounds.left&&bounds.left<frame.screenBounds.right
                &&bounds.bottom>frame.screenBounds.top&&bounds.top<frame.screenBounds.bottom;
    }
    public boolean draw(Canvas canvas,MaterialFrame next,RectF bounds,float radius,float amount,MediaFx music,long now) {
        return draw(canvas,next,bounds,bounds,radius,amount,music,now);
    }
    public boolean draw(Canvas canvas,MaterialFrame next,RectF bounds,RectF screenBounds,float radius,
            float amount,MediaFx music,long now) {
        if(!requested()){clear();return false;}
        if(!canvas.isHardwareAccelerated()||bounds.width()<1||bounds.height()<1)return false;
        // The shader's output is mix(black, lit, materialAmount), so at an amount of 0 it draws nothing at
        // all. Returning true there told StackPainter the card was handled and it skipped the classic fill,
        // which left the card invisible while its content kept drawing - the "floating text" seen when a
        // card collapses back into the pill. Hand the frame back so the solid fill is drawn instead.
        if(amount<=0.001f)return false;
        if(!canUse(next,screenBounds))next=null;
        try {
            boolean bind=false;
            if(shader==null) {
                shader=new RuntimeShader(FrostedShaderSource.CODE);paint.setShader(shader);
                // A compact-mode prewarm is already a valid background. Use it
                // on the first morph frame instead of fading from artificial black.
                previous=current=new Input(next);bind=true;
            }
            if(blending&&now-blendStartedAt>=CAPTURE_BLEND_MS) {
                previous=current;blending=false;bind=true;
            }
            if(next!=null)missingSince=-1;
            else if(current.frame!=null) {
                if(missingSince<0)missingSince=now;
                if(now-missingSince<MISSING_FRAME_GRACE_MS)next=current.frame;
            }
            // Finish each blend before adopting the latest delivered frame.
            // An early next capture stays in Scene; it cannot reset a blend halfway.
            if(!blending&&next!=current.frame) {
                previous=current;current=new Input(next);blendStartedAt=now;blending=true;bind=true;
            }
            boolean beforeChanged=previous.update(bounds,screenBounds);
            boolean afterChanged=current==previous ? beforeChanged : current.update(bounds,screenBounds);
            if(bind||beforeChanged)previous.bind(shader,true);
            if(bind||afterChanged)current.bind(shader,false);
            float scale=bounds.width()/1072f,w=bounds.width()/scale,h=bounds.height()/scale,corner=radius/scale;
            if(!lastBounds.equals(bounds)||lastCorner!=radius) {
                shader.setFloatUniform("designScale",scale);shader.setFloatUniform("cornerRadius",corner);
                configureLight(w,h,corner);lastBounds.set(bounds);lastCorner=radius;
            }
            if(current.frame!=null&&(lastInputReport==0||now-lastInputReport>=5000)) {
                Diagnostics.fact("card material inputs",String.format(java.util.Locale.US,
                        "screen=%s ref=%.0fx%.0f radius=%.1f texture=%dx%d response=%.3f [%.3f,%.3f]",
                        screenBounds.toShortString(),w,h,corner,current.frame.bitmap.getWidth(),current.frame.bitmap.getHeight(),
                        current.responseMean,current.responseMin,current.responseMax));lastInputReport=now;
            }
            float dt=lastTime==0 ? 0 : Math.max(0,Math.min(.05f,(now-lastTime)/1000f));lastTime=now;
            float live=music==null ? 0 : music.liveness(now);
            float motion=Math.max(0,Math.min(2,ModuleSettings.glowMotion()/50f));
            if(!primed){phase=(now%10000)/10000f*(float)(Math.PI*2);primed=true;}
            phase=(phase+dt*(float)(Math.PI*2)/10*live)%(float)(Math.PI*2);
            shader.setFloatUniform("phase",phase);shader.setFloatUniform("motionAmount",motion*live);
            shader.setFloatUniform("paletteAmount",music==null ? 0 : .18f+.82f*live);
            if(music!=null) {
             tonesCleared=false;
                if(ModuleSettings.glowAlbum()) {
                    colour("tone0",music.waveformColorAt(0,now));colour("tone1",music.waveformColorAt(.5f,now));colour("tone2",music.waveformColorAt(1,now));
                }else{int color=ModuleSettings.glowColour();colour("tone0",color);colour("tone1",color);colour("tone2",color);}
            }else if(!tonesCleared){colour("tone0",0);colour("tone1",0);colour("tone2",0);tonesCleared=true;}
            float blend=blending ? Math.max(0,Math.min(1,(now-blendStartedAt)/(float)CAPTURE_BLEND_MS)) : 1;
            blend=blend*blend*(3-2*blend);
            shader.setFloatUniform("captureAmount",blend);
            shader.setFloatUniform("materialAmount",Math.max(0,Math.min(1,amount)));
            int save=canvas.save();canvas.translate(bounds.left,bounds.top);
            long drawStart=System.nanoTime();
            canvas.drawRect(0,0,bounds.width(),bounds.height(),paint);
            canvas.restoreToCount(save);
            recordFrameCost(drawStart,bounds,now);
            final String state=current.frame==null ? "frosted GPU; stable local backdrop" : "frosted GPU; interpolated captured backdrop; G2 curved lighting";
            if(!state.equals(lastMaterialState)){lastMaterialState=state;Diagnostics.fact("card material",state);}
            return true;
        }catch(Throwable t) {
            unavailable=true;clear();XLog.once("frosted material unavailable",t);
            Diagnostics.fact("card material","GPU unavailable; classic fallback");return false;
        }
    }
    private long costTotalNanos,costFrames,costReportAt;

    /**
     * Averages the time spent inside the material draw and reports it every few seconds.
     *
     * <p>The canvas call only records work: on a hardware canvas the fragment shader runs later, on the
     * render thread. A small number here with visible jitter therefore means the cost is on the GPU, and a
     * large one means it is on this thread - which is what decides where to optimise next.
     */
    private void recordFrameCost(long startedAt,RectF bounds,long now) {
        costTotalNanos+=System.nanoTime()-startedAt;
        costFrames++;
        if(now-costReportAt<5000||costFrames<30)return;
        costReportAt=now;
        float perFrame=costTotalNanos/(float)costFrames/1000f;
        Diagnostics.fact("card material cost",String.format(java.util.Locale.US,
                "%.0f us/frame recorded over %d frames, %.0fx%.0f px",perFrame,costFrames,bounds.width(),bounds.height()));
        costTotalNanos=0;costFrames=0;
    }

    /** Texture, response map and coordinates travel together through a capture blend. */
    private static final class Input {
        final MaterialFrame frame;
        final BitmapShader backdrop;
        BitmapShader field;
        boolean bound;
        BitmapShader boundField;
        Bitmap fieldBitmap;
        long lastFieldAt;
        final RectF lastScreen=new RectF();
        float localW=-1,localH=-1,offsetX,offsetY,scaleX,scaleY;
        int fw=1,fh=1;
        float responseMean,responseMin,responseMax;
        Input(MaterialFrame frame) {
            this.frame=frame;
            backdrop=new BitmapShader(frame==null ? EmptyBackdrop.PIXEL : frame.bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
            backdrop.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            if(frame==null)field=new BitmapShader(EmptyBackdrop.PIXEL,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
        }
        boolean update(RectF local,RectF screen) {
            if(localW==local.width()&&localH==local.height()&&lastScreen.equals(screen))return false;
            localW=local.width();localH=local.height();lastScreen.set(screen);
            if(frame==null)return true;
            float sx=frame.bitmap.getWidth()/(float)frame.screenBounds.width(),sy=frame.bitmap.getHeight()/(float)frame.screenBounds.height();
            offsetX=(screen.left-frame.screenBounds.left)*sx;offsetY=(screen.top-frame.screenBounds.top)*sy;
            scaleX=sx*screen.width()/localW;scaleY=sy*screen.height()/localH;
            // The offsets and scale above must follow the card on every morph frame. The response field is a
            // blurred, low-frequency map of the same backdrop and barely changes, so rebuilding it - a fresh
            // int[], two gaussian passes, a new Bitmap and a new GPU texture - once per frame was the stutter.
            final int wantH=Math.max(8,Math.round(localH*40/localW));
            final long now=android.os.SystemClock.uptimeMillis();
            if(fieldBitmap!=null&&fw==40&&fh==wantH&&now-lastFieldAt<FIELD_REFRESH_MS)return true;
            fw=40;fh=wantH;
            int[] response=frame.pixels.response(offsetX,offsetY,screen.width()*sx,screen.height()*sy,fw,fh);
            float total=0;responseMin=1;responseMax=0;
            for(int encoded:response) {
                float value=(((encoded>>16)&255)*256+(encoded>>8&255))/65535f;
                total+=value;responseMin=Math.min(responseMin,value);responseMax=Math.max(responseMax,value);
            }
            responseMean=total/response.length;
            if(fieldBitmap==null||fieldBitmap.getWidth()!=fw||fieldBitmap.getHeight()!=fh) {
                fieldBitmap=Bitmap.createBitmap(fw,fh,Bitmap.Config.ARGB_8888);
                field=new BitmapShader(fieldBitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
                field.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            }
            fieldBitmap.setPixels(response,0,fw,0,0,fw,fh);
            lastFieldAt=now;
            return true;
        }
        /**
         * Rebinding runs on every frame of a morph. The input shaders and the response buffer are the same
         * objects unless the field was rebuilt, so they are only set when they actually change - the
         * offsets, scale and size always are, because those follow the card.
         */
        void bind(RuntimeShader shader,boolean old) {
            if(!bound||boundField!=field) {
                shader.setInputShader(old ? "previousBackground" : "background",backdrop);
                shader.setInputBuffer(old ? "previousResponseField" : "responseField",field);
                bound=true;boundField=field;
            }
            shader.setFloatUniform(old ? "previousSourceOffset" : "sourceOffset",offsetX,offsetY);
            shader.setFloatUniform(old ? "previousSourceScale" : "sourceScale",scaleX,scaleY);
            shader.setFloatUniform(old ? "previousFieldSize" : "fieldSize",fw,fh);
        }
    }
    private void colour(String name,int color) {
        shader.setFloatUniform(name,((color>>16)&255)/255f,((color>>8)&255)/255f,(color&255)/255f);
    }
    /** Approved COUI style-2 binding in reference units. */
     /**
      * Writes the three light arrays in place. This runs on every frame of a card morph and used to build
      * five fresh float[] each time (two from fade() plus the three literals), which showed up as GC jitter
      * during exactly the animation that has to feel fluid. The numbers are unchanged.
      */
     private void configureLight(float w,float h,float corner) {
         float density=3*.34f,mw=w/density,mh=h/density;
         fadeInto(.18f,.35f,600,200,mw,mh);
         float ab0=fadeA,ab1=fadeB;
         fadeInto(.25f,.25f,600,200,mw,mh);
         float cd0=fadeA,cd1=fadeB;
         edge[0]=1;edge[1]=1;edge[2]=1;edge[3]=2;edge[4]=4;edge[5]=2;edge[6]=4;
         edge[7]=.7f*.6f;edge[8]=.6f*.6f;edge[9]=ab0;edge[10]=ab0+ab1;
         edge[11]=cd0;edge[12]=cd0+cd1;edge[13]=0;edge[14]=4;edge[15]=.8f;
         shadow[0]=1;shadow[1]=1;shadow[2]=1;shadow[3]=.6f*.3f;shadow[4]=0;shadow[5]=0;
         shadow[6]=86*density;shadow[7]=0;shadow[8]=10*density;
         shadow[9]=1+.1f*600/mw;shadow[10]=1+.07f*600/mh;shadow[11]=4;shadow[12]=.7f;
         shadow[13]=density;shadow[14]=1;shadow[15]=0;
         common[0]=w;common[1]=h;common[2]=0;common[3]=0;common[4]=w;common[5]=h;
         common[6]=corner;common[7]=corner;common[8]=corner;common[9]=corner;common[10]=2.4f;common[11]=1;
         shader.setFloatUniform("u_commonArray",common);shader.setFloatUniform("u_edgeArray",edge);shader.setFloatUniform("u_shadowArray",shadow);
         shader.setFloatUniform("u_edge",1);shader.setFloatUniform("u_shadow",1);
     }

     /** Same arithmetic as fade(), writing into fields so nothing is allocated per frame. */
     private void fadeInto(float solid,float fade,float bw,float bh,float w,float h) {
         float bp=2*(bw+bh),np=2*(w+h),total=solid+fade;
         if(total*bp>bw) {
             fadeA=((total*bp-bw+w)-fade*bp)/np;fadeB=fade*bp/np;return;
         }
         if(total<=0) { fadeA=solid;fadeB=fade;return; }
         float ratio=(w-(bw-total*bp))/np;fadeA=solid*ratio/total;fadeB=fade*ratio/total;
     }
    private static float[] fade(float solid,float fade,float bw,float bh,float w,float h) {
        float bp=2*(bw+bh),np=2*(w+h),total=solid+fade;
        if(total*bp>bw)return new float[]{((total*bp-bw+w)-fade*bp)/np,fade*bp/np};
        if(total<=0)return new float[]{solid,fade};
        float ratio=(w-(bw-total*bp))/np;return new float[]{solid*ratio/total,fade*ratio/total};
    }
    public boolean animating(long now) {
        return requested()&&(blending||(missingSince>=0&&now-missingSince<MISSING_FRAME_GRACE_MS));
    }
    public void clear() {
        previous=current=null;shader=null;paint.setShader(null);blending=false;missingSince=-1;tonesCleared=false;
        lastBounds.setEmpty();lastCorner=-1;lastTime=lastInputReport=0;primed=false;
    }
}
