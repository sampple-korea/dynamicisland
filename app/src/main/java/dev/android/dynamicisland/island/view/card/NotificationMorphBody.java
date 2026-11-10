package dev.android.dynamicisland.island.view.card;

import android.graphics.Canvas;
import android.graphics.Color;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.island.view.IslandView;

/** Measured once per payload. The upper band is reserved for the camera aperture. */
public final class NotificationMorphBody extends CardBody {
    private final StaticLayout title, message, application;
    private final TextPaint heading = new TextPaint(3), text = new TextPaint(3);
    private final TextPaint caption = new TextPaint(3);
    private final float top, inset, icon;
    public NotificationMorphBody(IslandView view, IslandItem item, float width) {
        super(view,item,width);
        top=view.dp(42); inset=view.dp(20); icon=view.dp(34);
        heading.setColor(Color.WHITE); heading.setTextSize(view.dp(15));
        heading.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD));
        text.setColor(0xFFE6E6EB); text.setTextSize(view.dp(14));
        caption.setColor(0xFFB6BAC5);caption.setTextSize(view.dp(11));
        int available=Math.max(1,(int)(width-inset*3-icon));
        title=layout(item.title!=null?item.title:item.appName,heading,available,1);
        application=item.appName!=null && !android.text.TextUtils.equals(item.appName,item.title)
                ? layout(item.appName,caption,available,1) : null;
        message=layout(item.subtitle!=null?item.subtitle:item.description,text,available,3);
        float labelHeight=application==null ? 0 : application.getHeight()+view.dp(5);
        float bottom=top+Math.max(icon,labelHeight+title.getHeight()+message.getHeight()+view.dp(6));
        float actionsTop=bottom+view.dp(16);
        h=(item.actions.isEmpty() ? bottom : actionsTop+layoutTextButtons(actionsTop,inset))+inset;
    }
    private static StaticLayout layout(CharSequence value,TextPaint paint,int width,int lines) {
        return StaticLayout.Builder.obtain(value==null?"":value,0,value==null?0:value.length(),paint,width)
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build();
    }
    @Override public void draw(Canvas canvas,float x,float y,float alpha,long elapsed) {
        owner.drawLeading(canvas,item,x+inset,y+top+icon/2,icon,alpha);
        heading.setAlpha(Math.round(255*alpha));text.setAlpha(Math.round(255*alpha));
        caption.setAlpha(Math.round(255*alpha));
        int save=canvas.save();canvas.translate(x+inset*2+icon,y+top);
        if(application!=null) { application.draw(canvas);canvas.translate(0,application.getHeight()+owner.dp(5)); }
        title.draw(canvas);canvas.translate(0,title.getHeight()+owner.dp(6));message.draw(canvas);
        canvas.restoreToCount(save);
        drawButtons(canvas,x,y,alpha);
    }
}
