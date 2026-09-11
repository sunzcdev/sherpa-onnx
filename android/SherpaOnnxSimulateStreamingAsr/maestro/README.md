# Maestro 冒烟 flows (L2 模拟器 UI 回归)

跑法 (redroid, 设备 serial 用 adb devices 查):
    maestro test --device <serial> 01_home_smoke.yaml
    maestro test --device <serial> 02_import_flow.yaml

前置: 安装 debug/release APK 即可, 无需其他状态。
02 会真实导入《春晓》到 SharedPreferences, 跑完可 pm clear 复位。

已知约束 (实测):
- Maestro text 选择器是整串 full match, 子串一律 .* 包裹
- inputText 后必须 hideKeyboard, 否则 IME 候选条遮挡搜索结果
- 拍照 OCR 链路 redroid 无 camera provider 测不了, 归 L3 真机项
