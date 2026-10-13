package com.guga.tuchuan;

import android.graphics.Color;

/**
 * UI 配色集中常量 —— 「B 尼康黄黑风」（用户 2026-10-13 拍板）：
 * 纯黑底 #000000、近黑卡片 #0A0A0A、尼康黄 #FFDE00 主色（标题强调/勾选/主按钮黄底黑字/进度条）、
 * 正文白、次级信息灰；粗壮无衬线观感靠系统 sans-serif 加粗（在各控件 setTypeface 加 BOLD）。
 * 全 App 禁止散写颜色字面量，换皮只改这一处 + res/values/themes.xml。
 */
public final class Ui {
    private Ui() {}

    public static final int BG        = Color.rgb(0x00, 0x00, 0x00); // 纯黑底
    public static final int PANEL     = Color.rgb(0x0A, 0x0A, 0x0A); // 近黑卡片
    public static final int PANEL_ALT = Color.rgb(0x16, 0x16, 0x16); // 次级面板/按下态
    public static final int STROKE    = Color.rgb(0x2A, 0x2A, 0x2A); // 卡片描边（黑底上分层）
    public static final int TEXT      = Color.rgb(0xFF, 0xFF, 0xFF); // 正文白
    public static final int TEXT_DIM  = Color.rgb(0x9E, 0x9E, 0x9E); // 次级灰
    public static final int ACCENT    = Color.rgb(0xFF, 0xDE, 0x00); // 尼康黄
    public static final int ACCENT_TX = Color.rgb(0x00, 0x00, 0x00); // 黄底上的黑字
    public static final int OK        = Color.rgb(0x4C, 0xC3, 0x7A); // 成功绿
    public static final int ERR       = Color.rgb(0xE5, 0x5B, 0x4C); // 失败红
    // 格式徽标底色（徽标图形本体是 VectorDrawable，这三个仅作色块底衬）
    public static final int BADGE_JPG = Color.rgb(0xFF, 0xDE, 0x00);
    public static final int BADGE_NEF = Color.rgb(0x8A, 0x5C, 0xB8);
    public static final int BADGE_MP4 = Color.rgb(0xE5, 0x5B, 0x4C);
}
