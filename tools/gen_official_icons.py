#!/usr/bin/env python3
"""把官方 App 解出来的 res/drawable/ic_*.xml 矢量图转成 Compose ImageVector 代码。

用法：python3 tools/gen_official_icons.py
输出：app/src/main/java/com/shenyu/chat/ui/base/OfficialIcons.kt
"""
import os
import re

MAP = {
    'Menu': 'ic_menu_outline_24',
    'NewChat': 'ic_ds_new_chat_outline_20',
    'NewChatCircle': 'ic_new_chat_outline_24',
    'Send': 'ic_send_fill_20',
    'Stop': 'ic_stop_fill_20',
    'Mic': 'ic_mic_filled_20',
    'Attachment': 'ic_paperclip_outline_20',
    'Search': 'ic_search_outline_16',
    'DeepThink': 'ic_think_outline_16',
    'DeepThinkOn': 'ic_flash_on_fill_24',
    'Globe': 'ic_globe_language_outline_20',
    'Close': 'ic_close_outline_20',
    'Back': 'ic_chevron_left_outline_20',
    'ChevronRight': 'ic_chevron_right_outline_20',
    'ChevronDown': 'ic_chevron_down_outline_20',
    'MoreHorizontal': 'ic_ellipsis',
    'Edit': 'ic_edit_outline_20',
    'Delete': 'ic_trash_outline_20',
    'Copy': 'ic_copy_outline',
    'Refresh': 'ic_refresh_outline_20',
    'Regenerate': 'ic_regegerate_outline_24',
    'ThumbUp': 'ic_like_outline',
    'ThumbDown': 'ic_dislike_outline',
    'ThumbUpFill': 'ic_like_fill',
    'ThumbDownFill': 'ic_dislike_fill',
    'Speaker': 'ic_play_media_outline_24',
    'SpeakerOff': 'ic_mute_audio_outline_24',
    'Playing': 'ic_playing_fill_24',
    'Download': 'ic_download_outline',
    'Check': 'ic_check_outline_20',
    'Info': 'ic_info_outline',
    'Sun': 'ic_sun_regular',
    'Moon': 'ic_moon_regular',
    'Pin': 'ic_pin_outline_20',
    'Unpin': 'ic_unpin_outline_20',
    'Link': 'ic_link_outline_20',
    'User': 'ic_user_regular',
    'Help': 'ic_help_outline',
    'Add': 'ic_add_outline_26',
    'Voice': 'ic_voice_outline_26',
    'Keyboard': 'ic_keyboard_outline_26',
    'Share': 'ic_share_outline_20',
    'Think': 'ic_think_outline_16',
    'Branch': 'ic_branch_outline_20',
    'Camera': 'ic_camera_outline_20',
    'Photo': 'ic_photo_outline_20',
    'Folder': 'ic_folder_outline_20',
    'Warning': 'ic_warning_outline_20',
    'Phone': 'ic_phone_outline_24',
    'Report': 'ic_report_outline',
    'Fullscreen': 'ic_fullscreen_outline',
    'Select': 'ic_select_outline',
    'SelectAll': 'ic_select_all_outline_20',
    'KeyboardVoice': 'ic_keyboard_voice_24',
    'Loading': 'ic_loading',
    'LogoVertical': 'ic_logo_deepseek_vertical',
    'SearchWeb': 'ic_search_web',
    'Wand': 'ic_wand',
    'Quote': 'ic_excerpt_quote',
    'Data': 'ic_data_regular',
    'Profile': 'ic_profile',
    'CallHangUp': 'ic_call_hang_up',
    'CallMute': 'ic_call_live_update_mute',
    'Wechat': 'ic_wechat_outline_20',
}

DRAWABLE = 'app/src/main/res/drawable'
OUT = 'app/src/main/java/com/shenyu/chat/ui/base/OfficialIcons.kt'


def parse(name):
    text = open(os.path.join(DRAWABLE, name + '.xml'), encoding='utf-8').read()
    w = re.search(r'android:width="([\d.]+)dip"', text).group(1)
    h = re.search(r'android:height="([\d.]+)dip"', text).group(1)
    vw = re.search(r'android:viewportWidth="([\d.]+)"', text).group(1)
    vh = re.search(r'android:viewportHeight="([\d.]+)"', text).group(1)
    paths = []
    for m in re.finditer(r'<path\b([^/]*)/>', text):
        attrs = m.group(1)
        pd = re.search(r'android:pathData="([^"]+)"', attrs).group(1)
        ft_match = re.search(r'android:fillType="([^"]*)"', attrs)
        even_odd = bool(ft_match and ft_match.group(1) == 'evenOdd')
        fill_m = re.search(r'android:fillColor="([^"]*)"', attrs)
        stroke_m = re.search(r'android:strokeColor="([^"]*)"', attrs)
        width_m = re.search(r'android:strokeWidth="([\d.]+)"', attrs)
        fill = fill_m.group(1) if fill_m else None
        stroke = stroke_m.group(1) if stroke_m else None
        stroke_w = float(width_m.group(1)) if width_m else None
        # 官方大量图标是「描边型」（fillColor 透明 + strokeColor），必须按 stroke 生成，
        # 否则圆环会被填成实心黑圆（语音、加号这类图标尤其明显）。
        has_fill = fill is not None and fill not in ('#00000000',)
        has_stroke = stroke is not None and stroke not in ('#00000000',)
        paths.append((pd, even_odd, has_fill, has_stroke, stroke_w))
    return w, h, vw, vh, paths


def main():
    lines = [
        'package com.shenyu.chat.ui.base',
        '',
        'import androidx.compose.ui.graphics.Color',
        'import androidx.compose.ui.graphics.PathFillType',
        'import androidx.compose.ui.graphics.SolidColor',
        'import androidx.compose.ui.graphics.vector.ImageVector',
        'import androidx.compose.ui.graphics.vector.PathParser',
        'import androidx.compose.ui.unit.dp',
        '',
        '/**',
        ' * 官方 App 2.8.0 图标（由 res/drawable/ic_*.xml 自动转换）。',
        ' * 生成脚本 tools/gen_official_icons.py，勿手改本文件。',
        ' * 填充色统一黑色，实际着色由 Icon(tint = ...) 决定。',
        ' */',
        'object OfficialIcons {',
        '',
    ]
    missing = []
    for key, res in MAP.items():
        if not os.path.exists(os.path.join(DRAWABLE, res + '.xml')):
            missing.append((key, res))
            continue
        w, h, vw, vh, paths = parse(res)
        lines.append(f'    /** {res} */')
        lines.append(f'    val {key}: ImageVector by lazy {{')
        lines.append(
            f'        ImageVector.Builder(name = "{res}", defaultWidth = {w}.dp, '
            f'defaultHeight = {h}.dp, viewportWidth = {vw}f, viewportHeight = {vh}f).apply {{'
        )
        for pd, even_odd, has_fill, has_stroke, stroke_w in paths:
            esc = pd.replace('\\', '\\\\').replace('"', '\\"')
            args = [f'pathData = PathParser().parsePathString("{esc}").toNodes()']
            if not has_fill and not has_stroke:
                continue
            # 有填充就只用填充（官方 fill 型图标再叠加描边会整体显粗一圈）；
            # 只有填充透明时才按描边画，否则圆环类图标会被填成实心。
            if has_fill:
                args.append('fill = SolidColor(Color.Black)')
            else:
                args.append('fill = null')
                args.append('stroke = SolidColor(Color.Black)')
                args.append(f'strokeLineWidth = {stroke_w or 1.0}f')
            if even_odd:
                args.append('pathFillType = PathFillType.EvenOdd')
            lines.append(f'            addPath({", ".join(args)})')
        lines.append('        }.build()')
        lines.append('    }')
        lines.append('')
    lines.append('}')
    open(OUT, 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
    print(f'生成 {len(MAP) - len(missing)} 个图标；缺失 {missing}')


if __name__ == '__main__':
    main()
