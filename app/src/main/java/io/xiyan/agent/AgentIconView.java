// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Original line icons drawn locally; no downloaded or trademark artwork. */
final class AgentIconView extends View {
    enum Icon { HOME, CHAT, TASKS, SETTINGS, ORBIT, PHONE, GAME, MODEL }
    private final Icon icon;
    private final int color;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF bounds = new RectF();
    public AgentIconView(Context context) {
        this(context, Icon.ORBIT, Ui.ACCENT);
    }
    AgentIconView(Context context, Icon icon, int color) {
        super(context);
        this.icon = icon; this.color = color;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth(), getHeight()) / 32f;
        canvas.save();
        canvas.translate((getWidth() - scale * 32f) / 2, (getHeight() - scale * 32f) / 2);
        canvas.scale(scale, scale);
        paint.setColor(color); paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        path.reset();
        switch (icon) {
            case HOME:
                path.moveTo(5, 14); path.lineTo(16, 5); path.lineTo(27, 14);
                path.moveTo(8, 12); path.lineTo(8, 26); path.lineTo(24, 26); path.lineTo(24, 12);
                path.moveTo(13, 26); path.lineTo(13, 18); path.lineTo(19, 18); path.lineTo(19, 26);
                canvas.drawPath(path, paint); break;
            case CHAT:
                path.moveTo(8, 6); path.lineTo(24, 6); path.quadTo(27, 6, 27, 9);
                path.lineTo(27, 21); path.quadTo(27, 24, 24, 24); path.lineTo(13, 24);
                path.lineTo(6, 28); path.lineTo(6, 23); path.quadTo(4, 23, 4, 21);
                path.lineTo(4, 9); path.quadTo(4, 6, 8, 6); canvas.drawPath(path, paint);
                canvas.drawLine(10, 12, 21, 12, paint); canvas.drawLine(10, 17, 18, 17, paint); break;
            case TASKS:
                bounds.set(7, 5, 26, 27); canvas.drawRoundRect(bounds, 3, 3, paint);
                canvas.drawLine(14, 11, 21, 11, paint); canvas.drawLine(14, 17, 21, 17, paint);
                canvas.drawLine(14, 23, 21, 23, paint);
                canvas.drawCircle(10.5f, 11, .5f, paint); canvas.drawCircle(10.5f, 17, .5f, paint);
                canvas.drawCircle(10.5f, 23, .5f, paint); break;
            case SETTINGS:
                canvas.drawCircle(16, 16, 5, paint); canvas.drawCircle(16, 16, 11, paint);
                for (int i = 0; i < 8; i++) { canvas.save(); canvas.rotate(i * 45, 16, 16);
                    canvas.drawLine(16, 2, 16, 6, paint); canvas.restore(); } break;
            case PHONE:
                bounds.set(8, 3, 24, 29); canvas.drawRoundRect(bounds, 4, 4, paint);
                canvas.drawLine(13, 7, 19, 7, paint); canvas.drawLine(14, 25, 18, 25, paint); break;
            case GAME:
                path.moveTo(10, 9); path.lineTo(22, 9); path.cubicTo(29, 9, 31, 27, 25, 26);
                path.lineTo(20, 21); path.lineTo(12, 21); path.lineTo(7, 26);
                path.cubicTo(1, 27, 3, 9, 10, 9); canvas.drawPath(path, paint);
                canvas.drawLine(9, 13, 9, 19, paint); canvas.drawLine(6, 16, 12, 16, paint);
                canvas.drawCircle(22, 14, .8f, paint); canvas.drawCircle(25, 18, .8f, paint); break;
            case MODEL:
                bounds.set(8, 8, 24, 24); canvas.drawRoundRect(bounds, 4, 4, paint);
                bounds.set(13, 13, 19, 19); canvas.drawRoundRect(bounds, 1, 1, paint);
                for (int i = 11; i <= 21; i += 5) {
                    canvas.drawLine(i, 4, i, 8, paint); canvas.drawLine(i, 24, i, 28, paint);
                    canvas.drawLine(4, i, 8, i, paint); canvas.drawLine(24, i, 28, i, paint);
                } break;
            case ORBIT:
                canvas.drawCircle(16, 16, 10, paint);
                canvas.save(); canvas.rotate(-35, 16, 16);
                bounds.set(2, 11, 30, 21); canvas.drawOval(bounds, paint); canvas.restore();
                paint.setStyle(Paint.Style.FILL); canvas.drawCircle(16, 16, 3, paint);
                canvas.drawCircle(24, 9, 2, paint); break;
            default: break;
        }
        canvas.restore();
    }
}
