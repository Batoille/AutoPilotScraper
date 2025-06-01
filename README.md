# AutoPilotScraper

A Windows-only Java application that automates scraping of SportyBet’s Aviator game. It launches a visible Chrome instance using a bundled Chrome binary and ChromeDriver, captures bet data in real time, and writes results to a SQLite database (`betdata.db`). The scraper also logs summary statistics (profit/loss, “Brad” flags, etc.) into a separate table for downstream analysis.

---

## Directory Structure

```
AutoPilotScraper/
├─ drivers/
│   ├─ chrome_win64/
│   │   └─ chrome.exe
│   └─ chromedriver.exe
├─ src/
│   └─ com/AviatorScraper/
│         ├─ AviatorScraperShare.java
│         ├─ SoundDetectionListener.java
│         └─ (any other Java classes)
├─ vac/
│   └─ (VAC Audio Cable installer and setup files)
├─ .gitignore
├─ pom.xml          (optional, if using Maven)
└─ README.md
```

- **drivers/**  
  - `chrome_win64/chrome.exe`: Bundled Chrome 64-bit executable for Windows.  
  - `chromedriver.exe`: Bundled ChromeDriver matching the Chrome version.

- **src/com/AviatorScraper/**  
  - All Java source files.  
  - The main class is `AviatorScraperShare.java`, which initializes Chrome, captures bets, and writes to SQLite.

- **vac/**  
  - Contains the VAC Audio Cable installer and setup. You must install this before the first run.

- **.gitignore**  
  - Ensures IDE files, build outputs, and runtime artifacts (SQLite databases, logs) are not committed.

- **pom.xml** (if present)  
  - Defines Maven dependencies (Selenium, SQLite JDBC, etc.) and build instructions.

---

## Prerequisites

1. **Windows 10 or 11** (64-bit).  
2. **Java Development Kit (JDK) 11 or newer** installed and `java`/`javac` on your PATH.  
3. **Maven** (optional). If you prefer the plain `javac` approach, you can compile directly without Maven; but if you have a `pom.xml` in the project root, Maven will automatically download dependencies.  
4. **VAC Audio Cable** (must be installed before first run).  
5. **No separate Chrome install is required**: a compatible `chrome.exe` is bundled under `drivers/chrome_win64/`.  
6. **No separate ChromeDriver install is required**: `chromedriver.exe` is bundled under `drivers/`.  
7. **SportyBet Login Cookies in Chrome Profile**  
   - This software assumes that the Chrome profile you select already has a valid SportyBet login (cookies).  
   - If you haven’t logged into SportyBet and saved your credentials in that profile, open `drivers/chrome_win64/chrome.exe` manually with the same profile, log in at SportyBet, and save the “stay logged in” cookies before running the scraper.  
8. **A stable Internet connection** for Maven to fetch dependencies (if you use Maven).

---

## Audio Setup

Before running the scraper for the first time:

1. **Install VAC Audio Cable**  
   - Open the `vac/` folder.  
   - Run the VAC Audio Cable installer and accept all default settings.

2. **Verify VAC is running**  
   - After installation, you should see “Line 1” appear as a microphone input device in Windows Sound Settings.  
   - Go to **Control Panel → Sound → Recording** and confirm “Line 1 (VB-Audio Virtual Cable)” is listed and enabled.

3. **Configure Chrome’s Audio Output**  
   - Launch Chrome from `drivers/chrome_win64/chrome.exe` manually (double-click).  
   - Right-click the speaker icon in the system tray, choose **Open Volume mixer**.  
   - In the Volume mixer, locate the Chrome application’s volume slider.  
   - Change Chrome’s output device to **Line 1**. This routes all audio from the Aviator game into the VAC cable for sound detection.

Once VAC is installed and Chrome’s output device is set to Line 1, the scraper’s sound-detection logic can monitor the game audio correctly. Leave VAC running in the background whenever using this scraper.

---

## Build & Run

You can choose **either** Maven or the plain `javac`/`java` approach. Before actually running the scraper, make sure your chosen Chrome profile already has SportyBet login cookies—see note below.

---

### Pre-Run: Ensure SportyBet Login Cookies

1. **Open bundled Chrome manually**  
   ```
   drivers/chrome_win64/chrome.exe
   ```
2. **Log in to SportyBet** and select “stay logged in” so cookies are stored in that profile.  
3. **Close Chrome**.  
4. Now the scraper can reuse that profile’s cookies and access Aviator automatically.

---

### A. Using Maven (Recommended)

1. **Ensure you have Maven installed** and on your PATH.  
2. **From the project root**, run:
   ```bash
   mvn clean package
   ```
   Maven will:  
   - Download Selenium and SQLite JDBC dependencies  
   - Compile all Java classes under `src/`  
   - Package a shaded (“fat”) JAR in `target/`

3. **Run the JAR**:
   ```bash
   java -jar target/AviatorScraperShare-1.0.0-shaded.jar
   ```
   Replace `1.0.0` with the current version if it differs. The scraper will:

   1. Kill any stray Chrome processes.  
   2. Prompt for a **Brad threshold** (a numeric value you choose).  
   3. Detect your Chrome profiles under `%LOCALAPPDATA%\Google\Chrome\User Data`.  
   4. Let you pick a profile via a pop-up.  
   5. Launch Chrome (non-headless) via `drivers/chrome_win64/chrome.exe` and load the Aviator game.  
   6. Continuously detect “sound events” (from VAC), scrape bet data, insert into `betdata.db`, and write summary rows (with **STARTED**/**STOPPED** status).

---

### B. Using `javac`/`java` Directly

1. **Download Selenium Java client** (e.g. `selenium-java-4.x.x.jar`) and **SQLite JDBC** (e.g. `sqlite-jdbc-3.x.x.jar`). Place both JARs in a folder called `lib/` at project root:
   ```
   AutoPilotScraper/
   ├─ lib/
   │   ├─ selenium-java-4.x.x.jar
   │   ├─ sqlite-jdbc-3.x.x.jar
   │   └─ (any transitive dependencies, e.g. selenium-*.jar)
   └─ src/
       └─ com/AviatorScraper/AviatorScraperShare.java
   ```


2. **Compile** the Java classes:
   ```bash
   javac -cp "lib/*" -d out src/com/AviatorScraper/*.java
   ```
   This places `.class` files under `out/com/AviatorScraper/`.

3. **Run** the application:
   ```bash
   java -cp "out;lib/*" com.AviatorScraper.AviatorScraperShare
   ```
   The scraper will launch as described above.

---

## How It Works (High Level)

1. **Chrome Launch**  
   - The code first checks for a system property `chrome.binary`. If you start Java with:
     ```bash
     java -Dchrome.binary="C:/Path/To/Chrome/chrome.exe" …
     ```
     it uses that. Otherwise it automatically uses the bundled binary at:
     ```
     drivers/chrome_win64/chrome.exe
     ```
   - If that bundled path is missing, a file-chooser dialog appears so you can manually locate `chrome.exe`.

2. **ChromeDriver Initialization**  
   - Always uses the bundled path:
     ```
     drivers/chromedriver.exe
     ```
   - If that file is missing, the application shows an error dialog and exits.

3. **Chrome Profile Selection**  
   - The scraper enumerates all subfolders under `%LOCALAPPDATA%\Google\Chrome\User Data` named `Default` or `Profile N`.  
   - It then pops up a dialog so you select one. Cookies and session data (including SportyBet login) persist between runs if you choose the same profile.

4. **Sound-Detection Loop**  
   - A background thread runs a `SoundDetector` (based on your `SoundDetectionListener` implementation).  
   - Because Chrome’s audio output is routed through VAC’s “Line 1,” the detector picks up game sounds.  
   - Every time “sound is detected,” the `onSoundDetected()` method:
     1. Clicks **Previous** (or **All Bets** if it detects duplicate bet data).  
     2. Scrapes all `<app-bets-list-item>` elements (bet amount, cash-out) under the scrollable container.  
     3. Reads current **Bank** value from `<span class="balance-amount">`.  
     4. Inserts each bet into the **BetData** table.  
     5. Computes profit/loss, “Brad” (1 if profit > threshold, 0 otherwise), rolling window of “Brad” flags.  
     6. Inserts a new row into **SummaryData** (marking **STARTED** for the very first row of each run).  
     7. Clicks **All Bets** at the end to prepare for the next iteration.

5. **Database Structure**  
   - **BetData**  
     ```sql
     id INTEGER PRIMARY KEY AUTOINCREMENT,
     BetAmount REAL,
     Multiplier TEXT,
     CashOut REAL,
     RoundMultiplier TEXT
     ```
   - **SummaryData**  
     ```sql
     id INTEGER PRIMARY KEY AUTOINCREMENT,
     RoundMultiplier TEXT,
     TotalBetAmount REAL,
     TotalCashOut REAL,
     ProfitLoss REAL,
     Brad INTEGER,
     Bank REAL,
     SummaryDate TEXT,
     SummaryTime TEXT,
     Status TEXT
     ```
     - The first summary row in each run carries `Status = "STARTED"`.  
     - When the JVM exits or a critical error occurs, the most recent row’s `Status` is updated to `"STOPPED"`.

   - **InputTensorData** (used for LSTM/AI aggregations—create or update as needed).

6. **Shutdown Hook**  
   - The code registers a shutdown hook to mark the last row in **SummaryData** as `"STOPPED"` and record a termination timestamp in **InputTensorData**, then quits Chrome and closes the SQLite connection gracefully.

---

## Troubleshooting

- **VAC Audio Cable Issues**  
  - If VAC is not installed or not running, you will not see “Line 1” as a microphone device in Windows Sound Settings. Ensure VAC is installed from the `vac/` folder and that “Line 1 (VB-Audio Virtual Cable)” appears under **Control Panel → Sound → Recording**.

- **SportyBet Login Cookies Missing**  
  - If the selected Chrome profile has no SportyBet login cookies, the scraper cannot access the game. Open `drivers/chrome_win64/chrome.exe` manually, log in at SportyBet, and ensure you stay logged in. Then close Chrome and rerun the scraper.

- **Chrome or ChromeDriver Missing**  
  - If `drivers/chrome_win64/chrome.exe` is not found, a file-chooser dialog will appear. Navigate to your local Chrome installation or restore the bundled `chrome.exe`.  
  - If `drivers/chromedriver.exe` is missing, the scraper will show an error dialog and exit. Make sure you’ve committed `chromedriver.exe` into the `drivers/` folder.

- **“Cannot find Chrome User Data directory” Error**  
  - Ensure `%LOCALAPPDATA%\Google\Chrome\User Data` exists.  
  - If you have a custom install, launch Java with:
    ```bash
    java -Dchrome.binary="C:/Path/To/Chrome/chrome.exe" -jar …
    ```
    or manually restore `drivers/chrome_win64/chrome.exe`.

- **Maven Dependency Errors**  
  - If Maven cannot download dependencies, check your internet connection and your `~/.m2/settings.xml` (proxy settings).  
  - You can alternatively download the required JARs manually into `lib/` and use the `javac`/`java` steps.

- **“SQLite Error”**  
  - Verify that you have write permission in the working directory (where `betdata.db` is created).  
  - If you see “database is locked,” ensure no other process is holding onto `betdata.db`.

- **Pop-up Dialogs in Headless Environments**  
  - By design, the scraper uses Swing dialogs for threshold input and profile selection. If you run in a headless environment (no GUI), those prompts will hang. In that case:
    1. Modify `promptForBradThreshold()` to default to a fixed threshold (e.g. `4000`).  
    2. Bypass profile selection by hard-coding `profileChoice = "Default"` (or your preferred profile).  
