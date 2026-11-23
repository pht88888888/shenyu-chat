package com.shenyu.chat.ui.base

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.MenuOpen
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 全 App 图标总表。
 *
 * 约定：
 * - 只在这里 import material-icons 的具体图标，业务代码一律用 DsIcons.xxx，
 *   避免各处散落 import 造成同名图标冲突（filled/outlined 同名属性在 Kotlin 里会撞）。
 * - 选型优先「语义准」+「一定存在」：核心图标用 material-icons-core 里必然有的
 *   (Menu/Search/Settings/Close/ArrowBack/Edit/Delete/Check/Add/Refresh/ThumbUp/Info/Person)，
 *   其余用 material-icons-extended 里已确认存在的名字。
 * - 发送用 ArrowUpward（向上箭头），与 DeepSeek 官方输入框一致，而不是侧飞的纸飞机。
 * - 智能搜索用 Public（地球）、深度思考用 AutoAwesome（星芒）、跟随系统用 DesktopWindows（显示器）。
 */
object DsIcons {

    /** 左侧栏开关（≡） */
    val Menu: ImageVector = OfficialIcons.Menu

    /** 新建对话（带加号的对话气泡） */
    val NewChat: ImageVector = OfficialIcons.NewChat

    /**
     * 新建对话（自绘）：圆环 + 加号，笔画式，比 Material 的 AddComment 更接近官方视觉。
     * 用 stroke 绘制，靠 Icon 的 tint 统一着色，深浅色都适用。
     */
    val NewChatCircle: ImageVector by lazy {
        ImageVector.Builder(
            name = "NewChatCircle",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = PathParser()
                    .parsePathString("M12 3.4a8.6 8.6 0 1 0 0 17.2a8.6 8.6 0 1 0 0-17.2")
                    .toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                fill = null,
            )
            addPath(
                pathData = PathParser().parsePathString("M12 8v8M8 12h8").toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                fill = null,
            )
        }.build()
    }

    /** 发送（向上箭头） */
    val Send: ImageVector = OfficialIcons.Send

    /** 停止生成（实心方块） */
    val Stop: ImageVector = OfficialIcons.Stop

    /** 语音输入 */
    val Mic: ImageVector = OfficialIcons.Mic

    /** 语音通话入口（官方 ic_phone_outline_24） */
    val Phone: ImageVector = OfficialIcons.Phone

    /** 通话挂断（官方 ic_call_hang_up） */
    val CallHangUp: ImageVector = OfficialIcons.CallHangUp

    /** 通话静音（官方 ic_call_live_update_mute） */
    val CallMute: ImageVector = OfficialIcons.CallMute

    /** 附件（回形针） */
    val Attachment: ImageVector = OfficialIcons.Attachment

    /** 搜索（放大镜） */
    val Search: ImageVector = OfficialIcons.Search

    /** 深度思考（星芒） */
    val DeepThink: ImageVector = OfficialIcons.DeepThink

    /** 智能搜索（地球） */
    val Globe: ImageVector = OfficialIcons.Globe

    /** 设置（齿轮） */
    val Settings: ImageVector = Icons.Outlined.Settings

    /** 关闭（×） */
    val Close: ImageVector = OfficialIcons.Close

    /** 返回（左箭头） */
    val Back: ImageVector = OfficialIcons.Back

    /** 右侧箭头 › */
    val ChevronRight: ImageVector = OfficialIcons.ChevronRight

    /** 向下箭头 ˅ */
    val ChevronDown: ImageVector = OfficialIcons.ChevronDown

    /** 更多（⋯） */
    val MoreHorizontal: ImageVector = OfficialIcons.MoreHorizontal

    /** 数据管理（官方 IconDataOutline16：数据库圆柱 + 齿轮） */
    val Database: ImageVector by lazy {
        ImageVector.Builder(
            name = "Database",
            defaultWidth = 16.dp,
            defaultHeight = 16.dp,
            viewportWidth = 16f,
            viewportHeight = 16f,
        ).apply {
            addPath(
                pathData = PathParser().parsePathString("M12.0997 8.54554C12.2905 8.54989 12.3541 8.58056 12.4535 8.74614L12.8849 9.46387C12.9851 9.63071 13.0464 9.66013 13.2388 9.66447H14.1138C14.3417 9.66448 14.3512 9.66937 14.4686 9.86507L14.892 10.5717C14.9942 10.7422 14.9948 10.8247 14.892 10.9961L14.4756 11.6906C14.3741 11.8677 14.3694 11.9379 14.4756 12.115L14.892 12.8096C14.9942 12.9801 14.9947 13.0625 14.892 13.234L14.4686 13.9406C14.3643 14.1028 14.3063 14.1354 14.1138 14.1412H13.2388C13.0465 14.1456 12.985 14.1752 12.8849 14.3418L12.4535 15.0595C12.353 15.2195 12.2895 15.2558 12.0997 15.2601H11.2237C10.9962 15.2601 10.9871 15.2548 10.8699 15.0595L10.4384 14.3418C10.3383 14.175 10.2767 14.1456 10.0846 14.1412H9.2096C9.01854 14.1355 8.95761 14.1006 8.85477 13.9406L8.43139 13.234C8.32562 13.0576 8.33148 12.9862 8.43139 12.8096L8.84771 12.115C8.95165 11.9416 8.94659 11.863 8.84771 11.6906L8.43139 10.9961C8.32767 10.8232 8.33411 10.7437 8.43139 10.5717L8.85477 9.86507C8.95447 9.69891 9.01875 9.67017 9.2096 9.66447H10.0846C10.2741 9.66441 10.3414 9.62547 10.4384 9.46387L10.8699 8.74614C10.987 8.55106 10.9963 8.54554 11.2237 8.54554H12.0997ZM11.6612 10.232C11.3326 10.7798 10.8155 11.0948 10.1743 11.106C10.4443 11.61 10.4425 12.1976 10.1743 12.6987C10.803 12.7096 11.3391 13.0359 11.6612 13.5727C11.9855 13.0323 12.5131 12.7098 13.148 12.6987C12.879 12.196 12.8789 11.6086 13.148 11.106C12.5076 11.0948 11.9894 10.7794 11.6612 10.232Z").toNodes(),
                fill = SolidColor(Color.Black),
            )
            addPath(
                pathData = PathParser().parsePathString("M7.51205 0.790627C9.19055 0.790649 10.7401 1.0691 11.892 1.54364C12.4664 1.78029 12.9719 2.07885 13.3436 2.4408C13.7171 2.80467 13.9916 3.27253 13.9918 3.82384V7.90442C13.6067 7.69532 13.1907 7.53597 12.7529 7.43366V5.66454C12.4928 5.82898 12.2028 5.97601 11.892 6.10405C10.74 6.57865 9.19071 6.85706 7.51205 6.85706C5.8337 6.85703 4.285 6.57852 3.13309 6.10405C2.82215 5.97593 2.53164 5.8291 2.27121 5.66454V7.4135C2.27134 7.75678 2.6066 8.27106 3.62502 8.73405C4.58641 9.17097 5.95762 9.45591 7.50499 9.45681C7.24582 9.83133 7.03684 10.2434 6.88706 10.6826C5.44388 10.6162 4.12516 10.3216 3.11192 9.86104C2.81708 9.72698 2.53185 9.56866 2.27121 9.38928V11.2542C2.27158 11.5974 2.60697 12.1109 3.62502 12.5737C4.41933 12.9347 5.4937 13.1898 6.71569 13.2693C6.80349 13.7128 6.9513 14.1345 7.14814 14.5273C5.60324 14.4862 4.18593 14.1889 3.11192 13.7007C2.01039 13.1998 1.03366 12.3814 1.03333 11.2542V3.82384C1.03352 3.27273 1.30721 2.80461 1.68049 2.4408C2.05211 2.07893 2.55887 1.78026 3.13309 1.54364C4.28492 1.06926 5.83393 0.790683 7.51205 0.790627ZM7.51205 2.02851C5.95492 2.02857 4.57354 2.29079 3.60486 2.68979C3.11958 2.88977 2.76667 3.11253 2.5454 3.32788C2.32671 3.54101 2.2714 3.7089 2.27121 3.82384C2.27121 3.93882 2.32624 4.10625 2.5454 4.3198C2.76667 4.53527 3.11927 4.75781 3.60486 4.9579C4.5736 5.35699 5.95467 5.61914 7.51205 5.61918C9.06942 5.61918 10.4505 5.35695 11.4192 4.9579C11.9051 4.75773 12.2584 4.53536 12.4797 4.3198C12.6988 4.10627 12.7529 3.93882 12.7529 3.82384C12.7527 3.70889 12.6984 3.54104 12.4797 3.32788C12.2584 3.11239 11.9049 2.88989 11.4192 2.68979C10.4505 2.29079 9.06925 2.02853 7.51205 2.02851Z").toNodes(),
                fill = SolidColor(Color.Black),
            )
        }.build()
    }

    /** 编辑（铅笔） */
    val Edit: ImageVector = OfficialIcons.Edit

    /** 删除 */
    val Delete: ImageVector = OfficialIcons.Delete

    /** 复制 */
    val Copy: ImageVector = OfficialIcons.Copy

    /** 重新生成（环形箭头） */
    val Refresh: ImageVector = OfficialIcons.Refresh

    /** 点赞 */
    val ThumbUp: ImageVector = OfficialIcons.ThumbUp

    /** 点踩 */
    val ThumbDown: ImageVector = OfficialIcons.ThumbDown

    /** 相册（图片） */
    val Photo: ImageVector = OfficialIcons.Photo

    /** 拍照 */
    val Camera: ImageVector = OfficialIcons.Camera

    /** 文件夹 */
    val Folder: ImageVector = OfficialIcons.Folder

    /** 分享 */
    val Share: ImageVector = OfficialIcons.Share

    /** 思考（官方 think 小图标） */
    val Think: ImageVector = OfficialIcons.Think

    /** 分支 */
    val Branch: ImageVector = OfficialIcons.Branch

    /** 未置顶 */
    val Unpin: ImageVector = OfficialIcons.Unpin

    /** 警告 */
    val Warning: ImageVector = OfficialIcons.Warning

    /** 喜欢（实心，选中态） */
    val ThumbUpFill: ImageVector = OfficialIcons.ThumbUpFill

    /** 不喜欢（实心，选中态） */
    val ThumbDownFill: ImageVector = OfficialIcons.ThumbDownFill

    /** 朗读（喇叭） */
    val Speaker: ImageVector = OfficialIcons.Speaker
    val SpeakerOff: ImageVector = OfficialIcons.SpeakerOff

    /** 下载 */
    val Download: ImageVector = OfficialIcons.Download

    /** 上传 */
    val Upload: ImageVector = Icons.Outlined.Upload

    /** 勾选 */
    val Check: ImageVector = OfficialIcons.Check

    /** 错误（感叹号圆圈） */
    val Error: ImageVector = Icons.Outlined.ErrorOutline

    /** 信息 */
    val Info: ImageVector = OfficialIcons.Info

    /** 浅色模式（太阳） */
    val Sun: ImageVector = OfficialIcons.Sun

    /** 深色模式（月亮） */
    val Moon: ImageVector = OfficialIcons.Moon

    /** 跟随系统（显示器） */
    val System: ImageVector = Icons.Outlined.DesktopWindows

    /** 置顶（图钉） */
    val Pin: ImageVector = OfficialIcons.Pin

    /** 语言 / 翻译 */
    val Translate: ImageVector = Icons.Outlined.Translate

    /** 字体大小（AA） */
    val FontSize: ImageVector = Icons.Outlined.FormatSize

    /** 数据管理（数据库） */
    val Data: ImageVector = Icons.Outlined.Storage

    /** 账号 */
    val User: ImageVector = OfficialIcons.Profile

    /** 帮助 */
    val Help: ImageVector = OfficialIcons.Help

    /** 链接 */
    val Link: ImageVector = OfficialIcons.Link

    /** 回收站 / 彻底删除 */
    val Trash: ImageVector = Icons.Outlined.DeleteForever

    /** 新增（＋） */
    val Add: ImageVector = OfficialIcons.Add
    val AddCircle: ImageVector = Icons.Outlined.AddCircleOutline
    val Voice: ImageVector = OfficialIcons.Voice

    /** 收起键盘 */
    val Keyboard: ImageVector = OfficialIcons.Keyboard

    /** 展开侧栏 */
    val Expand: ImageVector = Icons.AutoMirrored.Outlined.MenuOpen
}
