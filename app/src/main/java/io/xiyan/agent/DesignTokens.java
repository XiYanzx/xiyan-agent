// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

/** Xiyan's shared visual roles. See docs/DESIGN-SYSTEM.zh-CN.md. */
final class DesignTokens {
    static final int TOUCH=48, PAGE=20, PAGE_NARROW=16, CONTENT_MAX=760;
    static final int RADIUS_CONTROL=14, RADIUS_PANEL=20, RADIUS_COMPOSER=24, RADIUS_SHEET=28;
    static final int MOTION_ENTER=180, MOTION_PANEL=220;
    private DesignTokens() { }
    static class Palette {
        final int background,surface,raised,text,muted,accent,onAccent,accentSurface,border,alert,alertSurface;
        final int sidebar,secondary,secondarySurface,success,successSurface,warning,warningSurface;
        Palette(boolean light) {
            background=light?0xfff7f9f6:0xff101919;surface=light?0xffffffff:0xff172323;
            raised=light?0xffedf2ee:0xff21302e;text=light?0xff172d2a:0xffedf5f2;
            muted=light?0xff5d726d:0xffa1b6ae;accent=light?0xff087f72:0xff79d5bd;
            onAccent=light?0xffffffff:0xff0a342b;accentSurface=light?0xffe1f2eb:0xff183c33;
            border=light?0xffdde7e1:0xff304440;sidebar=light?0xfff0f5ef:0xff14201e;
            secondary=light?0xff7956b5:0xffbea3ec;secondarySurface=light?0xfff0eafa:0xff30263f;
            success=light?0xff087f72:0xff79d5bd;successSurface=light?0xffe1f2eb:0xff183c33;
            warning=light?0xff926117:0xffe5b477;warningSurface=light?0xfffff3df:0xff30291c;
            alert=light?0xffbc3c50:0xfff39bab;alertSurface=light?0xffffedf0:0xff3a242a;
        }
    }
}
