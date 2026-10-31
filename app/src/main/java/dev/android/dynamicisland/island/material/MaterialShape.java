package dev.android.dynamicisland.island.material;

import android.graphics.Path;
import android.graphics.RectF;

/** Content clipping for the same lp/G2 perimeter evaluated analytically by frosted.agsl. */
public final class MaterialShape {
    private static final int STEPS=48;
    private static final float[] SINE=new float[STEPS+1],COSINE=new float[STEPS+1];
    static {
        for(int i=0;i<=STEPS;i++) {
            double a=Math.PI*.5*i/STEPS;
            SINE[i]=(float)Math.pow(Math.sin(a),2/2.4);
            COSINE[i]=(float)Math.pow(Math.cos(a),2/2.4);
        }
        SINE[0]=0;COSINE[STEPS]=0;
    }
    private MaterialShape() { }
    public static void path(Path path,RectF bounds,float radius) {
        path.reset();
        float r=Math.max(0,Math.min(radius,Math.min(bounds.width(),bounds.height())/2));
        if(r==0){path.addRect(bounds,Path.Direction.CW);return;}
        path.moveTo(bounds.left+r,bounds.top);
        for(int q=0;q<4;q++) {
            float cx=q<2 ? bounds.right-r : bounds.left+r;
            float cy=q==0||q==3 ? bounds.top+r : bounds.bottom-r;
            if(q==0)path.lineTo(cx,bounds.top);
            else if(q==1)path.lineTo(bounds.right,cy);
            else if(q==2)path.lineTo(cx,bounds.bottom);
            else path.lineTo(bounds.left,cy);
            for(int i=1;i<=STEPS;i++) {
                float x=q==0 ? SINE[i] : q==1 ? COSINE[i] : q==2 ? -SINE[i] : -COSINE[i];
                float y=q==0 ? -COSINE[i] : q==1 ? SINE[i] : q==2 ? COSINE[i] : -SINE[i];
                path.lineTo(cx+r*x,cy+r*y);
            }
        }
        path.close();
    }
}
