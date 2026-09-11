# Maestro 冒烟 flows (L2 模拟器 UI 回归)

跑法 (redroid, 设备 serial 用 adb devices 查):
    maestro test --device <serial> 01_home_smoke.yaml
    maestro test --device <serial> 02_import_flow.yaml
    maestro test --device <serial> 03_library_categories.yaml   # v0.2.1 书库分类

前置: 安装 debug/release APK。**每次 pm clear 后必须重授相机权限**, 否则
01 会卡在系统权限弹窗 ("Allow 背诵助手 to take pictures...") 上误报 FAILED:
    adb -s <serial> shell pm clear <pkg>
    adb -s <serial> shell pm grant <pkg> android.permission.CAMERA

flows 会真实导入篇目到 SharedPreferences, 跑完可 pm clear 复位。

已知约束 (实测):
- Maestro text 选择器是整串 full match, 子串一律 .* 包裹
- inputText 后必须 hideKeyboard, 否则 IME 候选条遮挡搜索结果
- **LazyColumn 懒渲染**: 目标条目在屏外 (如英语 chip 第 5 卡) 时 assertVisible
  直接红。解法 = 先 assert 首个可见卡证分类生效, 再走搜索精确命中 (03 flow)
- **断言文案以数据文件为准**: Toast 是 `已导入「${title}」`, title 含中点副标题
  (如「古诗三首·题西林壁」), flow 里凭记忆写简称必翻车
- 拍照 OCR 链路 redroid 无 camera provider 测不了, 归 L3 真机项
