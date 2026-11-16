# Privacy Policy / 私隱政策 — 烏龜鍵盤 － 廣東話 (Turtle Keyboard)

**Effective date / 生效日期:** 2026-11-16  
**Package / 套件名稱:** `com.madeinhk.cantonesevoicekeyboard`

---

## English

### Core Promise: We Never Send What You Type to Any Server

**烏龜鍵盤 － 廣東話 (Turtle Keyboard)** is designed around input privacy. **We never log or send what you type, write, or speak to any server.**

### 1. On-Device Input Processing
- All keyboard input features — Cantonese Quick (速成 3 & 5), English typing, contextual autocorrect, swipe typing, emoji search, Cantonese/Traditional Chinese handwriting recognition, and Cantonese/English voice transcription — run **locally on your device**.
- We never log keystrokes, typed text, handwritten strokes, clipboard text, or voice recordings to any server.

### 2. Microphone & Offline Voice Typing (`android.permission.RECORD_AUDIO`)
- Voice typing is optional. Microphone access is used **only** while you actively tap the microphone button to speak.
- Speech audio is processed in memory on your device by an offline speech recognition model (`Qwen3-ASR-1.7B-Cantonese-HK`, based on `Qwen/Qwen3-ASR-1.7B`) and is **never saved to storage or uploaded anywhere**.
- When you enable voice typing and grant microphone permission, the Google Play Store app downloads the speech model (~2 GB) via **Google Play Asset Delivery** over unmetered Wi-Fi.

### 3. On-Device Personalization & Clipboard
- **Quick (速成) selection learning:** If enabled, the Chinese words you select in Quick are remembered locally in your device’s private no-backup storage (`allowBackup="false"`) to rank your frequent choices higher. You can disable this feature or clear your learned history at any time in **Settings**.
- **Form-field completions:** Up to five recent entries in dedicated email, phone, name, or postal-address fields are stored locally in private app storage (`allowBackup="false"`) on your device to offer quick completions. They never leave your device.
- **Clipboard paste chip:** While the keyboard is open, it checks your current Android clipboard locally on-device solely to offer a one-tap paste suggestion before you start typing. Clipboard contents are never stored on disk or uploaded anywhere.

### 4. Data Retention & Deletion
- Because we never collect or transmit your typed, handwritten, or spoken content to any server, we hold none of your typed content on external servers.
- You can clear learned Quick history directly in **Settings → Clear learned history**, or erase all local preferences at any time via **Android Settings → Apps → 烏龜鍵盤 － 廣東話 → Storage & cache → Clear storage** or by uninstalling the app.

---

## 繁體中文（香港）

### 核心承諾：我們絕不會將你輸入嘅內容傳送到任何伺服器

**「烏龜鍵盤 － 廣東話」** 以保護輸入私隱為核心設計。**我們絕不會記錄你打嘅字、寫嘅字或講嘅說話，亦絕不會將內容傳送到任何伺服器。**

### 1. 本機輸入處理
- 所有鍵盤輸入功能——包括粵語速成（三代及五代）、英文輸入、語境自動修正、滑行輸入、表情符號搜尋、粵語及繁體中文手寫識別，以及中英夾雜語音轉文字——**全部喺你部裝置本機運行**。
- 我們絕不會將按鍵記錄、輸入文字、手寫筆劃、剪貼簿內容或語音錄音傳送到任何伺服器。

### 2. 咪高峰與離線語音輸入（`android.permission.RECORD_AUDIO`）
- 語音輸入屬於可選用功能。只有當你主動撳鍵盤上嘅咪高峰掣講嘢時，系統先會讀取咪高峰音訊。
- 語音音訊只會喺本機記憶體內由離線語音識別模型（`Qwen3-ASR-1.7B-Cantonese-HK`，基於 `Qwen/Qwen3-ASR-1.7B`）即時轉成文字，**絕不會儲存成檔案或上傳到任何地方**。
- 當你開啟語音輸入並允許咪高峰權限後，Google Play 商店會透過 **Google Play Asset Delivery** 於不限數據用量嘅 Wi-Fi 下載約 2 GB 語音模型。

### 3. 本機個人化記錄與剪貼簿
- **速成選字習慣學習：** 開啟後，你喺速成揀過嘅中文字詞只會儲存喺本機私密且不作雲端備份嘅空間（`allowBackup="false"`），方便優先顯示常用字詞。你可隨時喺 **設定** 關閉此功能或撳「清除學習記錄」。
- **表格欄位填寫建議：** 喺電郵、電話、姓名或地址專用欄位輸入過嘅最近最多 5 項資料，只會保存喺本機私密儲存空間（`allowBackup="false"`）作快速填寫建議，絕不會離開你嘅裝置。
- **剪貼簿貼上建議：** 打開鍵盤時，鍵盤只會喺本機讀取目前剪貼簿項目以提供一鍵貼上建議，絕不會將剪貼簿內容寫入磁碟或上傳到任何地方。

### 4. 資料保留與清除
- 由於我們從不收集或傳送你輸入、手寫或講出嘅內容到伺服器，我們嘅伺服器上並無保存你嘅輸入內容。
- 你可隨時喺應用程式內 **設定 → 清除學習記錄** 刪除速成記錄，或透過 **Android 設定 → 應用程式 → 烏龜鍵盤 － 廣東話 → 儲存空間和快取 → 清除儲存空間**（或直接解除安裝應用程式）徹底刪除所有本機設定及記錄。
