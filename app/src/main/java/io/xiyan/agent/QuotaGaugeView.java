// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** A compact observed-percentage meter; scaled labels live in regular TextViews. */
final class QuotaGaugeView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds=new RectF();
    private Double percentage;
    private int track,color;
    private DashPathEffect missingPattern;
    public QuotaGaugeView(Context context){super(context);}
    void value(Double value,int track,int color,int text,String description) {
        percentage=value!=null&&Double.isFinite(value)&&value>=0&&value<=100?value:null;
        this.track=track;this.color=color;setContentDescription(description);invalidate();
    }
    @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight) {
        super.onSizeChanged(width,height,oldWidth,oldHeight);
        float dash=Math.max(1,height-getPaddingTop()-getPaddingBottom());
        missingPattern=new DashPathEffect(new float[]{dash,dash},0);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width=getWidth()-getPaddingLeft()-getPaddingRight(),height=getHeight()-getPaddingTop()-getPaddingBottom();
        if(width<=0||height<=0)return;
        canvas.save();canvas.translate(getPaddingLeft(),getPaddingTop());paint.setColor(track);
        if(percentage==null) {
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(height/2);paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setPathEffect(missingPattern);canvas.drawLine(height/2,height/2,width-height/2,height/2,paint);paint.setPathEffect(null);
        } else {
            paint.setStyle(Paint.Style.FILL);bounds.set(0,0,width,height);canvas.drawRoundRect(bounds,height/2,height/2,paint);
            if(percentage>0){paint.setColor(color);bounds.right=(float)(width*percentage/100);canvas.drawRoundRect(bounds,height/2,height/2,paint);}
        }
        canvas.restore();
    }
}
