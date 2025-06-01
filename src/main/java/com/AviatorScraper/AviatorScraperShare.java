package com.AviatorScraper;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchFrameException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.*;
import java.io.*;
import java.sql.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Deque;
import java.util.LinkedList;
import java.util.stream.Collectors;

public class AviatorScraperShare implements SoundDetectionListener {
    private static final String CONFIG_FILE = "config.properties";

    private WebDriver driver;
    private WebDriverWait wait;
    private Connection connection;

    private int bradThreshold = 4000;
    private Deque<Integer> bradDeque = new LinkedList<>();

    private Set<String> lastBetItems = new HashSet<>();

    // Tracks whether this is the very first SummaryData insert in this run
    private boolean newSession = true;

    // To keep track of which row we last inserted, so we can mark it STOPPED
    private Long lastSummaryId = null;

    public AviatorScraperShare() {
        // 1) Kill any stray Chrome processes
        closeChromeIfRunning();

        // 2) Prompt for Brad threshold
        promptForBradThreshold();

        // 3) Dynamically discover all Chrome profiles under the User Data folder
        String userDataDir = System.getenv("LOCALAPPDATA")
                + File.separator + "Google"
                + File.separator + "Chrome"
                + File.separator + "User Data";

        File userDataFolder = new File(userDataDir);
        if (!userDataFolder.exists() || !userDataFolder.isDirectory()) {
            JOptionPane.showMessageDialog(
                    null,
                    "Cannot find Chrome User Data directory at:\n" + userDataDir + "\n" +
                            "Please ensure Chrome is installed and a profile exists.",
                    "Chrome User Data Not Found",
                    JOptionPane.ERROR_MESSAGE
            );
            System.exit(1);
        }

        // List all immediate subdirectories (candidate profile folders)
        String[] allSubfolders = userDataFolder.list((current, name) -> {
            File f = new File(current, name);
            return f.isDirectory();
        });

        // Filter to include only "Default" or "Profile N" where N is an integer
        List<String> profileFolders = Arrays.stream(allSubfolders)
                .filter(name -> name.equals("Default") || name.matches("Profile \\d+"))
                .collect(Collectors.toList());

        if (profileFolders.isEmpty()) {
            JOptionPane.showMessageDialog(
                    null,
                    "No valid Chrome profiles found under:\n" + userDataDir,
                    "No Profiles Detected",
                    JOptionPane.ERROR_MESSAGE
            );
            System.exit(1);
        }

        // Convert to array for the dialog
        String[] profilesArray = profileFolders.toArray(new String[0]);
        String profileChoice = (String) JOptionPane.showInputDialog(
                null,
                "Select Chrome profile to use:",
                "Chrome Profile Selection",
                JOptionPane.QUESTION_MESSAGE,
                null,
                profilesArray,
                profilesArray[0]
        );
        if (profileChoice == null) {
            System.out.println("No profile selected. Exiting.");
            System.exit(0);
        }

        // 4) Build ChromeOptions, including using the chosen profile
        ChromeOptions options = new ChromeOptions();

        // 4a) Try to find a bundled chrome.exe under drivers; else prompt/user-specified override
        String chromeBinaryOverride = System.getProperty("chrome.binary");
        String chromeBinaryPath;
        if (chromeBinaryOverride != null && new File(chromeBinaryOverride).exists()) {
            chromeBinaryPath = chromeBinaryOverride;
        } else {
            // Fallback to a bundled Chrome/Chromium executable
            chromeBinaryPath = "drivers/chrome_win64/chrome.exe";
            if (!new File(chromeBinaryPath).exists()) {
                // If that bundled path doesn’t exist, prompt the user
                chromeBinaryPath = getOrPromptForChromePath();
                if (chromeBinaryPath == null) {
                    JOptionPane.showMessageDialog(
                            null,
                            "Chrome executable not found. Please install Chrome or specify its location.",
                            "Chrome Not Found",
                            JOptionPane.ERROR_MESSAGE
                    );
                    System.exit(1);
                }
            }
        }
        options.setBinary(chromeBinaryPath);

        // 4b) Reuse the chosen Chrome profile so SportyBet cookies carry over
        options.addArguments(
                "user-data-dir=" + userDataDir,
                "profile-directory=" + profileChoice
        );

        // 4c) Hide Chrome’s “controlled by automated test software” banner
        options.setExperimentalOption("excludeSwitches", Arrays.asList("enable-automation"));
        options.setExperimentalOption("useAutomationExtension", false);

        // 5) Launch ChromeDriver + mask navigator.webdriver
        System.setProperty("webdriver.chrome.driver", "drivers/chromedriver.exe");
        driver = new ChromeDriver(options);
        wait = new WebDriverWait(driver, Duration.ofSeconds(20));

        // Override navigator.webdriver to avoid detection
        ((JavascriptExecutor) driver).executeScript(
                "Object.defineProperty(navigator, 'webdriver', { get: () => undefined });"
        );

        // 6) Initialize SQLite database + tables
        initializeDatabaseAndTables();

        // 7) Hook to mark last summary as STOPPED on JVM shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));

        // 8) Load the Aviator game page and switch into nested iframes
        loadGamePage();
    }

    // ———————————————————————————————————————————————————————————————————————
    // Try standard Windows install locations for Chrome; fallback to config or file chooser
    // ———————————————————————————————————————————————————————————————————————
    private String getOrPromptForChromePath() {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(CONFIG_FILE)) {
            props.load(fis);
            String p = props.getProperty("chromePath");
            if (p != null && new File(p).exists()) {
                return p;
            }
        } catch (IOException ignored) {}

        // Config didn’t exist or path was invalid—prompt user
        String chromePath = promptUserForChromePath();
        if (chromePath != null) {
            try (FileOutputStream fos = new FileOutputStream(CONFIG_FILE)) {
                props.setProperty("chromePath", chromePath);
                props.store(fos, "Chrome Path Configuration");
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        return chromePath;
    }

    private static String promptUserForChromePath() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Chrome Executable");
        chooser.setFileFilter(new FileNameExtensionFilter("Chrome Executable", "exe"));
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            return chooser.getSelectedFile().getAbsolutePath();
        }
        return null;
    }

    // ———————————————————————————————————————————————————————————————————————
    // Prompt for Brad threshold (no changes here)
    // ———————————————————————————————————————————————————————————————————————
    private void promptForBradThreshold() {
        String s = JOptionPane.showInputDialog(
                null,
                "Enter the Brad threshold:",
                "Brad Threshold Input",
                JOptionPane.QUESTION_MESSAGE
        );
        if (s != null) {
            try {
                int t = Integer.parseInt(s);
                if (t > 0) {
                    bradThreshold = t;
                    System.out.println("Brad threshold set to: " + t);
                    return;
                }
            } catch (NumberFormatException ignored) {}
        }
        System.out.println("Using default Brad threshold: " + bradThreshold);
    }

    public int getBradThreshold() {
        return bradThreshold;
    }

    // ———————————————————————————————————————————————————————————————————————
    // Kill Chrome if already running (no changes here)
    // ———————————————————————————————————————————————————————————————————————
    private void closeChromeIfRunning() {
        try {
            new ProcessBuilder("taskkill", "/F", "/IM", "chrome.exe").start().waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ———————————————————————————————————————————————————————————————————————
    // Initializes SQLite connection and (re)creates tables
    // ———————————————————————————————————————————————————————————————————————
    private void initializeDatabaseAndTables() {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:betdata.db");
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(
                        "CREATE TABLE IF NOT EXISTS BetData (" +
                                "  id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                                "  BetAmount REAL, " +
                                "  Multiplier TEXT, " +
                                "  CashOut REAL, " +
                                "  RoundMultiplier TEXT" +
                                ")"
                );

                stmt.execute(
                        "CREATE TABLE IF NOT EXISTS SummaryData (" +
                                "  id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                                "  RoundMultiplier TEXT, " +
                                "  TotalBetAmount REAL, " +
                                "  TotalCashOut REAL, " +
                                "  ProfitLoss REAL, " +
                                "  Brad INTEGER, " +
                                "  Bank REAL, " +
                                "  SummaryDate TEXT, " +
                                "  SummaryTime TEXT, " +
                                "  Status TEXT" +
                                ")"
                );

                stmt.execute(
                        "CREATE TABLE IF NOT EXISTS InputTensorData (" +
                                "  id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                                "  input_tensor_group TEXT UNIQUE, " +
                                "  lowest_target_multiplier REAL, " +
                                "  highest_target_multiplier REAL, " +
                                "  mean_target_multiplier REAL, " +
                                "  mode_target_range_start REAL, " +
                                "  mode_target_range_end REAL, " +
                                "  mode_range_target_count INTEGER, " +
                                "  above_mode_range_target_count INTEGER, " +
                                "  below_mode_range_target_count INTEGER, " +
                                "  group_count INTEGER, " +
                                "  target_confidence REAL, " +
                                "  TerminationTimestamp TEXT" +
                                ")"
                );

                System.out.println("Database tables (BetData, SummaryData, InputTensorData) recreated.");
            }
        } catch (SQLException e) {
            System.err.println("Error initializing database: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ———————————————————————————————————————————————————————————————————————
    // Play-safe element finder (no changes here)
    // ———————————————————————————————————————————————————————————————————————
    private WebElement findElementWithRetry(By locator, int maxRetries, long delayMillis) {
        int attempts = 0;
        while (attempts < maxRetries) {
            try {
                return wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
            } catch (Exception e) {
                attempts++;
                if (attempts >= maxRetries) {
                    handleCriticalError(e);
                    throw new RuntimeException("Failed to locate element: " + locator, e);
                }
                try { Thread.sleep(delayMillis); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
        throw new RuntimeException("Locator retry exhausted: " + locator);
    }

    // ———————————————————————————————————————————————————————————————————————
    // Critical-error recovery (no changes here)
    // ———————————————————————————————————————————————————————————————————————
    private void handleCriticalError(Exception e) {
        System.err.println("Critical error: " + e.getMessage());
        if (lastSummaryId != null) {
            updateSummaryStatus(lastSummaryId, "STOPPED");
        }
        recordTerminationTimestamp();
        if (driver != null) {
            driver.quit();
        }
        restartChromeAndResume();
    }

    // ———————————————————————————————————————————————————————————————————————
    // Helper: update a single SummaryData row’s Status (no changes here)
    // ———————————————————————————————————————————————————————————————————————
    private void updateSummaryStatus(long id, String status) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE SummaryData SET Status = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    // ———————————————————————————————————————————————————————————————————————
    // Helper: record termination time in InputTensorData (no changes here)
    // ———————————————————————————————————————————————————————————————————————
    private void recordTerminationTimestamp() {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE InputTensorData SET TerminationTimestamp = ? WHERE input_tensor_group = ?")) {
            ps.setString(1, now);
            ps.setString(2, "TERMINATION");
            if (ps.executeUpdate() == 0) {
                try (PreparedStatement ips = connection.prepareStatement(
                        "INSERT INTO InputTensorData (input_tensor_group, TerminationTimestamp) VALUES (?, ?)")) {
                    ips.setString(1, "TERMINATION");
                    ips.setString(2, now);
                    ips.executeUpdate();
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    // ———————————————————————————————————————————————————————————————————————
    // Restart Chrome after a crash (updated to include binary and masking)
    // ———————————————————————————————————————————————————————————————————————
    private void restartChromeAndResume() {
        ChromeOptions options = new ChromeOptions();

        // Re‐use the same Chrome binary and profile
        String chromeBinary = System.getProperty("chrome.binary");
        if (chromeBinary == null) {
            chromeBinary = "drivers/chrome_win64/chrome.exe";
        }
        options.setBinary(chromeBinary);

        // The same user-data-dir used earlier
        String userDataDir = System.getenv("LOCALAPPDATA")
                + File.separator + "Google"
                + File.separator + "Chrome"
                + File.separator + "User Data";

        options.addArguments("user-data-dir=" + userDataDir);
        options.setExperimentalOption("excludeSwitches", Arrays.asList("enable-automation"));
        options.setExperimentalOption("useAutomationExtension", false);

        this.driver = new ChromeDriver(options);
        this.wait   = new WebDriverWait(driver, Duration.ofSeconds(20));

        // Re‐mask navigator.webdriver
        ((JavascriptExecutor) driver).executeScript(
                "Object.defineProperty(navigator, 'webdriver', { get: () => undefined });"
        );

        newSession = true;
        loadGamePage();
        System.out.println("Chrome restarted successfully.");
    }

    // ———————————————————————————————————————————————————————————————————————
    // Wrapper for tasks that need crash handling (no changes here)
    // ———————————————————————————————————————————————————————————————————————
    private void runWithCriticalHandling(Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            handleCriticalError(e);
            throw e;
        }
    }

    // ———————————————————————————————————————————————————————————————————————
    // Start scraping loop (no changes here)
    // ———————————————————————————————————————————————————————————————————————
    public void start() {
        SoundDetector detector = new SoundDetector(this);
        new Thread(() -> runWithCriticalHandling(detector::detectSound)).start();
        loadGamePage();
    }

    // ———————————————————————————————————————————————————————————————————————
    // Load the Aviator game page and switch into nested iframes
    // ———————————————————————————————————————————————————————————————————————
    private void loadGamePage() {
        try {
            // 1) Navigate to SportyBet’s games lobby
            driver.get("https://www.sportybet.com/gh/games");

            // 2) Wait for the outer “games-lobby” iframe and switch into it
            WebElement lobby = wait.until(
                    ExpectedConditions.presenceOfElementLocated(By.id("games-lobby"))
            );
            driver.switchTo().frame(lobby);

            // 3) Click the Aviator tile (id="game_item19")
            WebElement item = wait.until(
                    ExpectedConditions.elementToBeClickable(By.id("game_item19"))
            );
            item.click();

            // 4) Switch into the inner “turbo-games-iframe”
            WebElement turboFrame = wait.until(
                    ExpectedConditions.visibilityOfElementLocated(By.className("turbo-games-iframe"))
            );
            driver.switchTo().frame(turboFrame);

            // 5) (Optional) Click the “auto” tab if your setup requires it
            wait.until(ExpectedConditions.elementToBeClickable(
                    By.cssSelector("button.tab.ng-star-inserted")
            )).click();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ———————————————————————————————————————————————————————————————————————
    // Retry switching into nested iframes (no changes here)
    // ———————————————————————————————————————————————————————————————————————
    private boolean switchToIframeWithRetry(By[] locators, int maxRetries, long delayMillis) throws NoSuchFrameException {
        for (By locator : locators) {
            int attempts = 0;
            while (attempts++ < maxRetries) {
                try {
                    WebElement frame = wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
                    driver.switchTo().frame(frame);
                    return true;
                } catch (TimeoutException e) {
                    if (attempts >= maxRetries) throw new NoSuchFrameException("Cannot locate iframe: " + locator);
                    try { Thread.sleep(delayMillis); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
        return false;
    }

    // ———————————————————————————————————————————————————————————————————————
    // Log all visible iframes (no changes here)
    // ———————————————————————————————————————————————————————————————————————
    private void logVisibleIframes() {
        for (WebElement f : driver.findElements(By.tagName("iframe"))) {
            System.out.printf("Iframe — ID:%s, Name:%s, Class:%s%n",
                    f.getAttribute("id"), f.getAttribute("name"), f.getAttribute("class"));
        }
    }

    // ———————————————————————————————————————————————————————————————————————
    // On each “sound detected,” pull the bets, insert into DB, etc.
    // Uses <span class="balance-amount"> instead of id="j_balance"
    // ———————————————————————————————————————————————————————————————————————
    @Override
    public void onSoundDetected() {
        try {
            // 1) Locate and click the “Previous” button
            WebElement previousButton = wait.until(
                    ExpectedConditions.elementToBeClickable(
                            By.xpath(
                                    "//button[contains(@class,'tab') and contains(@class,'ng-star-inserted') " +
                                            "and normalize-space(text())='Previous']"
                            )
                    )
            );
            try {
                previousButton.click();
            } catch (WebDriverException clickFail) {
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", previousButton);
            }
            System.out.println("'Previous' button clicked.");

            // 2) Read the current round multiplier from <app-previous-round-result>
            WebElement roundMultiplierElement = findElementWithRetry(
                    By.cssSelector("app-previous-round-result .result-multiplier"),
                    3,
                    200
            );
            String roundText = roundMultiplierElement.getText().trim();   // e.g. "1.10x"
            String numericPart = roundText.endsWith("x")
                    ? roundText.substring(0, roundText.length() - 1)
                    : roundText;
            System.out.println("Round multiplier: " + numericPart);
            String currentRoundMultiplier = numericPart;

            // 3) Locate the scrollable container for <app-bets-list-item>
            WebElement betWrapper = findElementWithRetry(
                    By.className("cdk-virtual-scroll-content-wrapper"),
                    3,
                    200
            );

            // 4) Find every <app-bets-list-item> and parse bet amounts + cash-out
            List<WebElement> betItemsList = betWrapper.findElements(
                    By.cssSelector("app-bets-list-item")
            );
            System.out.println("Found " + betItemsList.size() + " bet items.");

            Set<String> currentBetItems = new HashSet<>();
            double totalBetAmount = 0.0;
            double totalCashOut  = 0.0;

            for (WebElement betItem : betItemsList) {
                // 4a) Bet amount (e.g. "950.00")
                WebElement amountDiv = betItem.findElement(
                        By.cssSelector("app-bet-amount > div")
                );
                String betAmountText = amountDiv.getText().trim().replace(",", "");
                if (betAmountText.equalsIgnoreCase("FREE BET")) {
                    System.out.println("Skipping FREE BET item.");
                    continue;
                }
                double betAmount = Double.parseDouble(betAmountText);
                totalBetAmount += betAmount;

                // 4b) Cash-out / win (e.g. "0.00")
                WebElement winDiv = betItem.findElement(
                        By.cssSelector(".bet-list-item-column.win")
                );
                String cashOutText = winDiv.getText().trim().replace(",", "");
                double cashOut = Double.parseDouble(cashOutText);
                totalCashOut += cashOut;

                // 4c) Build a dedupe key
                String betIdentifier = betAmountText + "_" + cashOutText;
                currentBetItems.add(betIdentifier);
            }

            // 5) Read the current bank balance from <span class="balance-amount">
            double bank = 0.0;
            try {
                WebElement balanceAmountElem = wait.until(
                        ExpectedConditions.visibilityOfElementLocated(
                                By.cssSelector("span.balance-amount")
                        )
                );
                String bankText = balanceAmountElem.getText().trim().replace(",", "");
                bank = Double.parseDouble(bankText);
                System.out.println("Extracted Bank value: " + bank);
            } catch (Exception e) {
                System.out.println("Failed to extract Bank value: " + e.getMessage());
                e.printStackTrace();
            }

            // 6) Deduplication: if same bet items as last time, click “All Bets” instead of “Previous”
            if (currentBetItems.equals(lastBetItems)) {
                System.out.println("Duplicate bet items detected. Switching to All Bets.");

                WebElement allBetsButton = wait.until(
                        ExpectedConditions.elementToBeClickable(
                                By.xpath("//button[contains(@class,'tab') and normalize-space(text())='All Bets']")
                        )
                );
                try {
                    allBetsButton.click();
                } catch (WebDriverException clickFail) {
                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", allBetsButton);
                }
                System.out.println("'All Bets' button clicked for duplicate skip.");
                return;
            }

            lastBetItems = currentBetItems;

            // 7) Insert each bet row into BetData
            try (PreparedStatement pstmt = connection.prepareStatement(
                    "INSERT INTO BetData (BetAmount, Multiplier, CashOut, RoundMultiplier) VALUES (?, ?, ?, ?)"
            )) {
                for (WebElement betItem : betItemsList) {
                    WebElement amountDiv = betItem.findElement(
                            By.cssSelector("app-bet-amount > div")
                    );
                    String betAmountText = amountDiv.getText().trim().replace(",", "");
                    if (betAmountText.equalsIgnoreCase("FREE BET")) {
                        continue;
                    }
                    double betAmount = Double.parseDouble(betAmountText);

                    String multiplierText = "N/A";

                    WebElement winDiv = betItem.findElement(
                            By.cssSelector(".bet-list-item-column.win")
                    );
                    String cashOutText = winDiv.getText().trim().replace(",", "");
                    double cashOut = Double.parseDouble(cashOutText);

                    pstmt.setDouble(1, betAmount);
                    pstmt.setString(2, multiplierText);
                    pstmt.setDouble(3, cashOut);
                    pstmt.setString(4, currentRoundMultiplier);
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
                System.out.println("Bet data inserted into database.");
            } catch (SQLException e) {
                System.out.println("Error inserting BetData: " + e.getMessage());
                e.printStackTrace();
            }

            // 8) Insert a SummaryData row (mark “STARTED” on the very first run)
            insertSummaryData(currentRoundMultiplier, totalBetAmount, totalCashOut, bank);

            // 9) Finally, click “All Bets” to prepare for next iteration
            WebElement allBetsButton = wait.until(
                    ExpectedConditions.elementToBeClickable(
                            By.xpath("//button[contains(@class,'tab') and normalize-space(text())='All Bets']")
                    )
            );
            allBetsButton.click();
            System.out.println("'All Bets' button clicked.");

        } catch (Exception e) {
            System.out.println("Error during sound detection handling: " + e.getMessage());
            e.printStackTrace();
            handleCriticalError(e);
        }
    }

    // ———————————————————————————————————————————————————————————————————————
    // Inserts a new SummaryData row (no changes here)
    // ———————————————————————————————————————————————————————————————————————
    private void insertSummaryData(String roundMultiplier,
                                   double totalBetAmount,
                                   double totalCashOut,
                                   double bank) {
        // 1) Compute profit/loss and the Brad flag
        double profitLoss = totalBetAmount - totalCashOut;
        int currentBrad = (profitLoss > bradThreshold) ? 1 : 0;

        // 2) Maintain rolling window of last 4 Brad values
        bradDeque.addLast(currentBrad);
        if (bradDeque.size() > 4) {
            bradDeque.removeFirst();
        }

        // 3) Get current date/time
        LocalDateTime now = LocalDateTime.now();
        String summaryDate = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String summaryTime = now.format(DateTimeFormatter.ofPattern("HH:mm:ss"));

        // 4) Only the first insert in this run gets "STARTED"
        String statusValue = newSession ? "STARTED" : "";
        newSession = false;

        // 5) Insert the row (no input_tensor, PredictionTargetID or TargetMultiplier)
        String sql =
                "INSERT INTO SummaryData " +
                        "(RoundMultiplier, TotalBetAmount, TotalCashOut, ProfitLoss, " +
                        "Brad, Bank, SummaryDate, SummaryTime, Status) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, roundMultiplier);
            ps.setDouble(2, totalBetAmount);
            ps.setDouble(3, totalCashOut);
            ps.setDouble(4, profitLoss);
            ps.setInt(5, currentBrad);
            ps.setDouble(6, bank);
            ps.setString(7, summaryDate);
            ps.setString(8, summaryTime);
            ps.setString(9, statusValue);

            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    lastSummaryId = rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ———————————————————————————————————————————————————————————————————————
    // On JVM shutdown: mark last summary as "STOPPED" & record termination timestamp (no changes here)
    // ———————————————————————————————————————————————————————————————————————
    public void stop() {
        if (lastSummaryId != null) {
            updateSummaryStatus(lastSummaryId, "STOPPED");
        }
        recordTerminationTimestamp();

        if (driver != null) {
            driver.quit();
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    // ———————————————————————————————————————————————————————————————————————
    // Main entrypoint (no changes here)
    // ———————————————————————————————————————————————————————————————————————
    public static void main(String[] args) {
        AviatorScraperShare scraper = new AviatorScraperShare();
        Thread.setDefaultUncaughtExceptionHandler((t, err) -> {
            if (err instanceof Exception) {
                scraper.handleCriticalError((Exception) err);
            } else {
                err.printStackTrace();
                System.exit(1);
            }
        });
        scraper.start();
    }

    // ———————————————————————————————————————————————————————————————————————
    // InputTensorData aggregation methods (no changes here)
    // buildInputTensorDataTableFull, updateLatestInputTensorDataGroup,
    // updateOrInsertGroupAggregation, calculateMode, etc.
    // ———————————————————————————————————————————————————————————————————————

    public void buildInputTensorDataTableFull() {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS InputTensorData");
            String createSQL = "CREATE TABLE IF NOT EXISTS InputTensorData (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "input_tensor_group TEXT UNIQUE, " +
                    "lowest_target_multiplier REAL, " +
                    "highest_target_multiplier REAL, " +
                    "mean_target_multiplier REAL, " +
                    "mode_target_range_start REAL, " +
                    "mode_target_range_end REAL, " +
                    "mode_range_target_count INTEGER, " +
                    "above_mode_range_target_count INTEGER, " +
                    "below_mode_range_target_count INTEGER, " +
                    "group_count INTEGER, " +
                    "target_confidence REAL, " +
                    "TerminationTimestamp TEXT" +
                    ")";
            stmt.execute(createSQL);
            System.out.println("InputTensorData table created (full build).");

            String[] groups = {
                    "[0,0,0,0]", "[0,0,0,1]", "[0,0,1,0]", "[0,0,1,1]",
                    "[0,1,0,0]", "[0,1,0,1]", "[0,1,1,0]", "[0,1,1,1]",
                    "[1,0,0,0]", "[1,0,0,1]", "[1,0,1,0]", "[1,0,1,1]",
                    "[1,1,0,0]", "[1,1,0,1]", "[1,1,1,0]", "[1,1,1,1]"
            };
            for (String group : groups) {
                updateOrInsertGroupAggregation(group);
            }
            System.out.println("Full build of InputTensorData completed.");
        } catch (SQLException e) {
            System.out.println("Error building InputTensorData table (full build): " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void updateLatestInputTensorDataGroup() {
        try {
            String getLatestGroupQuery =
                    "SELECT input_tensor " +
                            "FROM SummaryData " +
                            "WHERE TargetMultiplier IS NOT NULL " +
                            "  AND LENGTH(input_tensor)=9 " +
                            "ORDER BY id DESC LIMIT 1";

            try (PreparedStatement ps = connection.prepareStatement(getLatestGroupQuery);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String group = rs.getString("input_tensor").replaceAll("\\s+", "");
                    updateOrInsertGroupAggregation(group);
                }
            }
        } catch (SQLException ex) {
            System.out.println("Error updating latest InputTensorData group: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private void updateOrInsertGroupAggregation(String group) throws SQLException {
        String aggQuery =
                "SELECT " +
                        "   MIN(CAST(TargetMultiplier AS REAL)) AS lowest_target_multiplier, " +
                        "   MAX(CAST(TargetMultiplier AS REAL)) AS highest_target_multiplier, " +
                        "   AVG(CAST(TargetMultiplier AS REAL)) AS mean_target_multiplier, " +
                        "   COUNT(*) AS group_count " +
                        "FROM SummaryData " +
                        "WHERE input_tensor = ? " +
                        "  AND LENGTH(input_tensor)=9 " +
                        "  AND TargetMultiplier IS NOT NULL " +
                        "  AND id >= ? " +
                        "  AND SummaryDate = ?";

        try (PreparedStatement aggStmt = connection.prepareStatement(aggQuery)) {
            long minId = 1;  // or previously tracked minAggregationId
            String currentRunDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

            aggStmt.setString(1, group);
            aggStmt.setLong(2, minId);
            aggStmt.setString(3, currentRunDate);

            try (ResultSet aggRs = aggStmt.executeQuery()) {
                if (aggRs.next()) {
                    double lowest = aggRs.getDouble("lowest_target_multiplier");
                    double highest = aggRs.getDouble("highest_target_multiplier");
                    double mean = aggRs.getDouble("mean_target_multiplier");
                    int groupCount = aggRs.getInt("group_count");

                    if (groupCount == 0) return;

                    List<Double> multipliers = new ArrayList<>();
                    String fetchMultipliersQuery =
                            "SELECT CAST(TargetMultiplier AS REAL) AS val " +
                                    "FROM SummaryData " +
                                    "WHERE input_tensor = ? " +
                                    "  AND LENGTH(input_tensor)=9 " +
                                    "  AND TargetMultiplier IS NOT NULL " +
                                    "  AND id >= ? " +
                                    "  AND SummaryDate = ?";
                    try (PreparedStatement fetchStmt = connection.prepareStatement(fetchMultipliersQuery)) {
                        fetchStmt.setString(1, group);
                        fetchStmt.setLong(2, minId);
                        fetchStmt.setString(3, currentRunDate);
                        try (ResultSet fetchRs = fetchStmt.executeQuery()) {
                            while (fetchRs.next()) {
                                multipliers.add(fetchRs.getDouble("val"));
                            }
                        }
                    }

                    double modeStart = 0.0, modeEnd = 0.0, confidence = 0.0;
                    int modeCount = 0, aboveCount = 0, belowCount = 0;
                    if (!multipliers.isEmpty()) {
                        double[] modeStats = calculateMode(multipliers);
                        modeStart   = modeStats[0];
                        modeEnd     = modeStats[1];
                        modeCount   = (int) modeStats[2];
                        aboveCount  = (int) modeStats[3];
                        belowCount  = (int) modeStats[4];
                        confidence  = modeStats[5];
                    }

                    String checkQuery = "SELECT id FROM InputTensorData WHERE input_tensor_group = ?";
                    try (PreparedStatement checkStmt = connection.prepareStatement(checkQuery)) {
                        checkStmt.setString(1, group);
                        try (ResultSet checkRs = checkStmt.executeQuery()) {
                            if (checkRs.next()) {
                                String updateQuery =
                                        "UPDATE InputTensorData " +
                                                "SET lowest_target_multiplier = ?, " +
                                                "    highest_target_multiplier= ?, " +
                                                "    mean_target_multiplier   = ?, " +
                                                "    mode_target_range_start  = ?, " +
                                                "    mode_target_range_end    = ?, " +
                                                "    mode_range_target_count  = ?, " +
                                                "    above_mode_range_target_count = ?, " +
                                                "    below_mode_range_target_count = ?, " +
                                                "    group_count              = ?, " +
                                                "    target_confidence        = ? " +
                                                "WHERE input_tensor_group = ?";
                                try (PreparedStatement updateStmt = connection.prepareStatement(updateQuery)) {
                                    updateStmt.setDouble(1, lowest);
                                    updateStmt.setDouble(2, highest);
                                    updateStmt.setDouble(3, mean);
                                    updateStmt.setDouble(4, modeStart);
                                    updateStmt.setDouble(5, modeEnd);
                                    updateStmt.setInt(6, modeCount);
                                    updateStmt.setInt(7, aboveCount);
                                    updateStmt.setInt(8, belowCount);
                                    updateStmt.setInt(9, groupCount);
                                    updateStmt.setDouble(10, confidence);
                                    updateStmt.setString(11, group);
                                    updateStmt.executeUpdate();
                                }
                            } else {
                                String insertQuery =
                                        "INSERT INTO InputTensorData (" +
                                                "  input_tensor_group, lowest_target_multiplier, highest_target_multiplier, " +
                                                "  mean_target_multiplier, mode_target_range_start, mode_target_range_end, " +
                                                "  mode_range_target_count, above_mode_range_target_count, below_mode_range_target_count, " +
                                                "  group_count, target_confidence" +
                                                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                                try (PreparedStatement insertStmt = connection.prepareStatement(insertQuery)) {
                                    insertStmt.setString(1, group);
                                    insertStmt.setDouble(2, lowest);
                                    insertStmt.setDouble(3, highest);
                                    insertStmt.setDouble(4, mean);
                                    insertStmt.setDouble(5, modeStart);
                                    insertStmt.setDouble(6, modeEnd);
                                    insertStmt.setInt(7, modeCount);
                                    insertStmt.setInt(8, aboveCount);
                                    insertStmt.setInt(9, belowCount);
                                    insertStmt.setInt(10, groupCount);
                                    insertStmt.setDouble(11, confidence);
                                    insertStmt.executeUpdate();
                                }
                            }
                        }
                    }
                }
            }
        } catch (SQLException ex) {
            System.out.println("Error updating latest InputTensorData group: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private double[] calculateMode(List<Double> multipliers) {
        if (multipliers == null || multipliers.isEmpty()) {
            return new double[]{1.0, 1.0, 0, 0, 0, 0};
        }
        Collections.sort(multipliers);
        int n = multipliers.size();
        int q1Index = (int) Math.floor(0.25 * (n - 1));
        int q3Index = (int) Math.floor(0.75 * (n - 1));
        double q1 = multipliers.get(q1Index);
        double q3 = multipliers.get(q3Index);
        double iqr = q3 - q1;

        double binWidth = (iqr > 0) ? (2.0 * iqr / Math.pow(n, 1.0 / 3.0)) : 0.1;
        if (binWidth <= 0) {
            binWidth = 0.1;
        }

        Map<Double, Integer> binCounts = new HashMap<>();
        double modeStart = 0, modeEnd = 0;
        int modeCount = 0;
        for (double value : multipliers) {
            double binStart = Math.floor(value / binWidth) * binWidth;
            int count = binCounts.getOrDefault(binStart, 0) + 1;
            binCounts.put(binStart, count);
            if (count > modeCount) {
                modeCount = count;
                modeStart = binStart;
                modeEnd = binStart + binWidth;
            }
        }
        modeStart = Math.max(modeStart, 1.0);
        modeEnd = Math.max(modeEnd, modeStart + binWidth);

        int belowCount = 0, aboveCount = 0;
        for (double v : multipliers) {
            if (v < modeStart) {
                belowCount++;
            } else if (v > modeEnd) {
                aboveCount++;
            }
        }

        double confidence = modeCount + aboveCount - belowCount;
        return new double[]{modeStart, modeEnd, modeCount, aboveCount, belowCount, confidence};
    }
}
