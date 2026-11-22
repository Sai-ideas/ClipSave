# 剪存

剪存是原生 Android 剪貼簿管理工具，支援 Android 10 及以上版本。它以 Clipboard Companion 的公開功能為參考，使用獨立介面與本機儲存。

## 使用方式

- 在其他 App 複製內容後，打開剪存，按「儲存目前剪貼簿」並確認。也可以在其他 App 的分享清單選「剪存」，或在剪存內新增文字、用系統檔案選擇器匯入圖片與 PDF。
- 透過類型、收藏、文字、檔名或標籤找剪輯。選「更多」可置頂、加標籤、標為敏感、分享或刪除。支援預覽圖片和 PDF 首頁。
- 在隱私設定調整保留期限或啟用離開時上鎖。預設保留 30 天。置頂項目保留至手動刪除，收藏項目仍遵守保留期限。過期資料會在剪存處理資料庫時清理。

## 隱私設計

- 剪輯內容、標籤、檔名與附件使用 Android Keystore 管理的 AES-256-GCM 金鑰加密，儲存在 App 私有目錄。關閉 Android 備份與裝置移轉，App 未宣告網路、無障礙服務或廣泛檔案權限。
- 儲存由使用者確認。系統標記為敏感的剪貼簿內容會自動套用遮罩，也可手動標記。清單遮罩包含內容、檔名及標籤，搜尋時略過敏感剪輯。主畫面及對話框使用系統的安全視窗旗標。
- 選用的 App 鎖使用系統螢幕鎖定憑證保護畫面。儲存加密持續啟用。個別附件由 Android 授予接收 App 讀取權限，複製或分享後，接收方可自行保留資料。選取系統上的雲端檔案時，檔案提供者可自行下載來源檔案。
- Android 10 起限制非前景 App 讀取剪貼簿。剪存在使用者按下按鈕時讀取當前剪貼簿。每次可收錄一則文字、連結或一個附件。文字上限 20,000 個字元，附件上限 16 MiB，最多 200 筆及 96 MiB 附件。

參考 [Clipboard Companion 商店頁](https://play.google.com/store/apps/details?id=com.vinsontech.clipboardcompanion) 與 [Android 剪貼簿存取限制](https://developer.android.com/about/versions/10/privacy/changes#clipboard-data)。

## 建置與安裝

用 Android Studio 開啟本資料夾，使用 JDK 17 和 Android SDK Platform 35。也可以使用 Gradle Wrapper 建置開發版。

```sh
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

隨附的試用 APK 使用這次建置的開發簽章。本機重建時 Gradle 會使用你的開發簽章。若以不同簽章替換同套件的試用版，Android 要求先解除安裝，裝置上的剪存資料也會隨之刪除。
