// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Lucide's 24-unit functional icons; Xiyan's own crossing-ribbon brand mark. */
final class AgentIconView extends View {
    enum Icon { HOME, CHAT, TASKS, SETTINGS, ORBIT, PHONE, GAME, MODEL, MENU, PLUS, SEND, STOP,
        USER, CHART, CHEVRON, BACK, CHECK, REFRESH, CLOSE, SUN, MOON, SAVE, CLOCK, INFO, DOWN,
        MORE, COPY, SHIELD, LOGOUT, LINK, BOLT, ERROR, SUCCESS, SEARCH }
    private Icon icon;
    private int color;
    private Drawable drawable;
    public AgentIconView(Context context){this(context,Icon.ORBIT,Ui.ACCENT);}
    AgentIconView(Context context,Icon icon,int color){
        super(context);this.icon=icon;this.color=color;reload();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    void appearance(Icon nextIcon,int nextColor){
        if(icon==nextIcon&&color==nextColor)return;
        boolean changed=icon!=nextIcon;icon=nextIcon;color=nextColor;
        if(changed)reload();else drawable.setTint(color);invalidate();
    }
    void tint(int nextColor){appearance(icon,nextColor);}
    private void reload(){drawable=getContext().getDrawable(resource(icon)).mutate();drawable.setTint(color);}
    private static int resource(Icon icon){
        switch(icon){
            case HOME:return R.drawable.ic_lucide_house;
            case CHAT:return R.drawable.ic_lucide_message_square;
            case TASKS:return R.drawable.ic_lucide_list_todo;
            case SETTINGS:return R.drawable.ic_lucide_settings_2;
            case PHONE:return R.drawable.ic_lucide_smartphone;
            case GAME:return R.drawable.ic_lucide_gamepad_2;
            case MODEL:return R.drawable.ic_lucide_cpu;
            case MENU:return R.drawable.ic_lucide_menu;
            case PLUS:return R.drawable.ic_lucide_plus;
            case SEND:return R.drawable.ic_lucide_arrow_up;
            case STOP:return R.drawable.ic_lucide_square;
            case USER:return R.drawable.ic_lucide_user_round;
            case CHART:return R.drawable.ic_lucide_chart_no_axes_column;
            case CHEVRON:return R.drawable.ic_lucide_chevron_right;
            case BACK:return R.drawable.ic_lucide_arrow_left;
            case CHECK:return R.drawable.ic_lucide_check;
            case REFRESH:return R.drawable.ic_lucide_refresh_cw;
            case CLOSE:return R.drawable.ic_lucide_x;
            case SUN:return R.drawable.ic_lucide_sun;
            case MOON:return R.drawable.ic_lucide_moon;
            case SAVE:return R.drawable.ic_lucide_save;
            case CLOCK:return R.drawable.ic_lucide_clock;
            case INFO:return R.drawable.ic_lucide_info;
            case DOWN:return R.drawable.ic_lucide_chevron_down;
            case MORE:return R.drawable.ic_lucide_ellipsis;
            case COPY:return R.drawable.ic_lucide_copy;
            case SHIELD:return R.drawable.ic_lucide_shield_check;
            case LOGOUT:return R.drawable.ic_lucide_log_out;
            case LINK:return R.drawable.ic_lucide_external_link;
            case BOLT:return R.drawable.ic_lucide_zap;
            case ERROR:return R.drawable.ic_lucide_circle_alert;
            case SUCCESS:return R.drawable.ic_lucide_circle_check;
            case SEARCH:return R.drawable.ic_lucide_search;
            case ORBIT:default:return R.drawable.ic_brand;
        }
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);int size=Math.min(getWidth(),getHeight());
        int left=(getWidth()-size)/2,top=(getHeight()-size)/2;
        drawable.setBounds(left,top,left+size,top+size);drawable.draw(canvas);
    }
}
