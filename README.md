# 機車超速提醒（Android）

騎機車時用 GPS 測速。**在測速照相路段**（照相點前 400 m 內）一達到照相點限速 + 7 km/h（限速 50 → 57）就立即每 0.5 秒嗶一聲，直到速度降下來為止。沒有照相點的路段只在畫面上用橘色數字提示、不出聲（可在設定關閉「只在測速照相路段警告超速」，改成所有路段都會叫）。

## 功能
- **自動限速（離線）**：App 內建全台灣 OpenStreetMap 道路限速資料（約 12 萬條道路，其中 2.2 萬條有限速），**不需要網路**。依位置和行進方向找出目前所在道路；路口會選和你行進方向一致的那條路。有機車專用限速（`maxspeed:motorcycle`）時優先使用。
- **限速判斷順序**：測速照相路段內用照相點公告的限速 → OSM 道路限速 → 前段道路限速（沿用 30 秒）→ 預設限速（可調，預設 50）。
- **超速警報**：一達到門檻就立即開始嗶，每 0.5 秒 2 kHz 嗶聲，一低於門檻就停。
- **測速照相提醒**：內建警政署「測速執法設置點」開放資料（1,909 點），前方 400 m（可調）且方向相符時響三聲提示音，同一個點 5 分鐘內不重複提醒。照相路段中 App 畫面頂端會出現閃爍的黃色大橫幅（距離、限速、地點），車速框加黃框；浮動視窗也會加粗黃框並顯示「📷 測速照相」和距離。
- **背景執行**：前景服務 + 喚醒鎖，關螢幕、切到導航也會持續運作；聲音走「導航語音」音訊通道，會從藍牙耳機或安全帽對講機輸出，並暫時降低音樂音量。
- **浮動視窗**：切到 Google 地圖等其他 App 時，畫面上浮著一個小框顯示車速、限速和前方照相點距離，超速時整個變紅；可以拖曳到任何位置（會記住），點一下回到 App。第一次使用需要允許「顯示在其他應用程式上層」。
- **神盾連動（選用）**：開啟「機車超速提醒：神盾連動」無障礙服務並選好神盾後，神盾背景浮動視窗跳出測速照相／科技執法警告時，本 App 也會進入照相路段（黃框、照相路段才叫的超速警報）；神盾顯示限速數字時用它的限速判斷。只讀神盾的警告視窗（`rlayView2Root` 的限速／類型、`rlayView3Root` 的距離），不讀其他 App、不複製神盾的資料庫。
- 可調：容許值、預設／手動限速、照相提醒開關與距離、音量。

## 安裝
1. 把 APK 下載到手機並點開安裝（需允許「安裝未知應用程式」）。
2. 打開 App → 按「開始」→ 允許**精確位置**和**通知**權限。
3. 建議按「關閉省電限制」，避免部分手機（小米、OPPO、三星等）在背景把 App 關掉。

## 限制
- OSM 限速資料在台灣主要道路大多有，巷弄常沒有，這時會用預設限速。
- 測速照相資料是 App 內建的快照，**不含行動測速**；要更新請重新產生資料並重新編譯（見下方）。
- GPS 速度通常比機車儀表低幾 km/h（儀表會多報），57 是 GPS 速度。
- 僅供輔助提醒，請以現場標誌為準。

## 更新地圖資料
到 GitHub repo 的 **Actions → Update map data → Run workflow**，約 5 分鐘會用最新的 OpenStreetMap 台灣圖資和警政署測速點資料重建 `roads.bin`、`cameras.csv` 並提交回 repo，之後重新編譯 APK 即可。

## 編譯
```bash
# 道路限速資料（需要 pip install osmium）
curl -o taiwan.osm.pbf https://download.geofabrik.de/asia/taiwan-latest.osm.pbf
python3 tools/build_roads.py taiwan.osm.pbf app/src/main/assets/roads.bin

# 更新測速照相資料（來源 https://data.gov.tw/dataset/7320）
curl -o cams.csv "https://opdadm.moi.gov.tw/api/v1/no-auth/resource/api/dataset/EA5E6FCD-B82D-43B7-A5CF-E9893253187E/resource/CD32FD37-EA0D-4F76-8C5F-7E2514FA4E1F/download"
python3 tools/build_cameras.py cams.csv app/src/main/assets/cameras.csv

# 單元測試與 APK（需要 Android SDK，在 local.properties 設定 sdk.dir）
./gradlew testDebugUnitTest assembleRelease
# 輸出：app/build/outputs/apk/release/app-release.apk
```

`keystore/speedalert.keystore` 是本專案固定的簽章金鑰（密碼 `speedalert`），讓之後重新編譯的版本可以直接覆蓋安裝。只用於自己安裝，不適合上架 Play 商店。

## 資料來源與授權
- 道路限速：© OpenStreetMap contributors（ODbL），Geofabrik 台灣圖資。
- 測速照相點：內政部警政署「測速執法設置點」，政府資料開放授權條款第 1 版。
