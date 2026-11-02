// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Original circular meter. A missing percentage is drawn as a dash. */
final class QuotaGaugeView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc=new RectF();
    private final android.graphics.Typeface face=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL);
    private Double percentage;
    private int track=0xffe0e5f0,color=0xff5266ed,text=0xff18233d;
    private String label="—";
    public QuotaGaugeView(Context context){super(context);}
    void value(Double percentage,int track,int color,int text,String description) {
        this.percentage=percentage;this.track=track;this.color=color;this.text=text;
        label=percentage==null?"—":Ui.percent(percentage)+"%";setContentDescription(description);invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);float size=Math.min(getWidth(),getHeight()),stroke=size*.075f;
        float left=(getWidth()-size)/2f+stroke,top=(getHeight()-size)/2f+stroke;
        arc.set(left,top,left+size-stroke*2,top+size-stroke*2);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(stroke);paint.setStrokeCap(Paint.Cap.ROUND);paint.setColor(track);
        canvas.drawArc(arc,-90,360,false,paint);
        if(percentage!=null){paint.setColor(color);canvas.drawArc(arc,-90,(float)(360*percentage/100),false,paint);}
        paint.setStyle(Paint.Style.FILL);paint.setColor(text);paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(face);
        paint.setTextSize(size*(label.length()>5?.21f:.25f));
        canvas.drawText(label,getWidth()/2f,getHeight()/2f-(paint.ascent()+paint.descent())/2,paint);
    }
}
