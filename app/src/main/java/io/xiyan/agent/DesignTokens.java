// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

/** Xiyan's shared visual roles. See docs/DESIGN-SYSTEM-2.zh-CN.md. */
final class DesignTokens {
    static final int TOUCH=48, PAGE=20, PAGE_NARROW=16, CONTENT_MAX=760;
    static final int RADIUS_CONTROL=8, RADIUS_PANEL=4, RADIUS_COMPOSER=10, RADIUS_SHEET=12;
    static final int MOTION_ENTER=180, MOTION_PANEL=220;
    private DesignTokens() { }
    static class Palette {
        final int background,surface,raised,text,muted,accent,onAccent,accentSurface,border,alert,alertSurface;
        final int sidebar,secondary,secondarySurface,success,successSurface,warning,warningSurface;
        Palette(boolean light) {
            background=light?0xfffaf9f6:0xff111111;surface=light?0xffffffff:0xff191919;
            raised=light?0xfff0efeb:0xff252525;text=light?0xff151515:0xfff3f0e8;
            muted=light?0xff626262:0xffb2aea6;accent=light?0xffc43d1c:0xffff9778;
            onAccent=light?0xffffffff:0xff20110d;accentSurface=light?0xfffcebe5:0xff39231e;
            border=light?0xffd8d6d0:0xff393733;sidebar=background;
            secondary=accent;secondarySurface=accentSurface;
            success=light?0xff326548:0xffa3d1ad;successSurface=light?0xffedf4ed:0xff203026;
            warning=light?0xff85560d:0xffe8bc71;warningSurface=light?0xfffff3df:0xff30291c;
            alert=light?0xffb42338:0xffffa0ad;alertSurface=light?0xffffedf0:0xff3a2228;
        }
    }
}
