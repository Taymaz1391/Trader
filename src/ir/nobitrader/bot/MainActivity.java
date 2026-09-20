package ir.nobitrader.bot;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Single-screen Persian RTL dashboard: market ticker, bot controls, position,
 * settings, account connection, backtest and logs. All views are built
 * programmatically (dark theme, no external dependencies).
 */
public class MainActivity extends Activity {

    // palette
    private static final int BG = 0xFF0B0F17;
    private static final int CARD = 0xFF141B2B;
    private static final int CARD2 = 0xFF101624;
    private static final int STROKE = 0xFF23304A;
    private static final int TEXT = 0xFFE8EDF6;
    private static final int TEXT2 = 0xFF93A0B8;
    private static final int GREEN = 0xFF16C784;
    private static final int RED = 0xFFEA3943;
    private static final int GOLD = 0xFFF0B90B;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private BotEngine engine;
    private Prefs prefs;

    // views
    private TextView statusPill, priceView, changeView, symbolTitle, livePill;
    private TextView walletView, posView, statsView, logView, backtestView;
    private TextView stratDesc, amountHint, liveWarn, analysisView;
    private EditText tokenEdit, amountEdit, slEdit, tpEdit, trailEdit, tgTokenEdit, tgChatEdit;
    private Spinner symbolSpin, tfSpin, intervalSpin, stratSpin;
    private Switch liveSwitch, trailSwitch, dcaSwitch, atrSwitch, riskSwitch, tp1Switch;
    private LinearLayout[] tabs = new LinearLayout[4];
    private ScrollView[] pages = new ScrollView[4];
    private EditText riskEdit;
    private EditText dailyEdit, alertPriceEdit;
    private EditText secretEdit;
    private Button testBtn;
    private TextView connView;
    private Button playBtn;
    private boolean replaying = false;
    private int replayPos = 0, replayLen = 0;
    private double[][] replayEvents = new double[0][];
    private Runnable replayTick;
    private Switch htfSwitch;
    private Spinner alertSymSpin, alertDirSpin;
    private LinearLayout alertsList;
    private static final String[] CHIP_SYMS = {"BTCIRT", "ETHIRT", "SOLIRT", "XRPIRT", "DOGEIRT", "TRXIRT"};
    private final TextView[] chipPrice = new TextView[CHIP_SYMS.length];
    private final TextView[] chipChg = new TextView[CHIP_SYMS.length];
    private final LinearLayout[] chipLay = new LinearLayout[CHIP_SYMS.length];
    private long lastChipsAt = 0;
    private LinearLayout dcaBox;
    private Spinner dcaSpin, dcaMaxSpin;
    private Button startBtn, connectBtn, backtestBtn, sellBtn, resetBtn, csvBtn, tgTestBtn, optBtn;
    private RiskGauge riskGauge;
    private EquityView equityView;
    private double lastShownPrice = 0;
    private LinearLayout sellResetRow;
    private ChartView chartView;

    private boolean resumed = false;
    private int shownVersion = -1;
    private int tick = 0;
    private volatile boolean priceBusy = false;
    private volatile long lastPriceOkAt = 0;   // last successful price fetch
    private Runnable keySaver;                 // debounced API-key auto-save
    private volatile boolean chartBusy = false;
    private volatile boolean btBusy = false;
    private volatile boolean optBusy = false;

    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        engine = BotEngine.get(this);
        prefs = new Prefs(this);
        Store.init(prefs);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        setContentView(buildUi());
        loadIntoUi();

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        refreshUi();
        fetchChart();
        fetchPrice();
        ui.postDelayed(poll, 2500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(poll);
        if (keySaver != null) ui.removeCallbacks(keySaver);
        if (tokenEdit != null) saveKeys(true);
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            refreshUi();
            tick++;
            if (tick % 4 == 0) fetchPrice();
            if (tick % 18 == 1) fetchChart();
            ui.postDelayed(this, 2500);
        }
    };

    // ---------------------------------------------------------------- UI

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        return t;
    }

    private TextView label(String s) {
        return text(s, 13f, TEXT2, false);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(CARD);
        gd.setCornerRadius(dp(16));
        gd.setStroke(dp(1), STROKE);
        c.setBackground(gd);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        r.setLayoutParams(lp);
        return r;
    }

    private EditText numberInput(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(15f);
        e.setTextColor(TEXT);
        e.setHintTextColor(TEXT2);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setBackground(null);
        return e;
    }

    private Button button(String s, int bgColor) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(15f);
        b.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        b.setAllCaps(false);
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(dp(12));
        android.graphics.drawable.RippleDrawable rp = new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x40FFFFFF), gd, null);
        b.setBackground(rp);
        return b;
    }

    private View vline() {
        View v = new View(this);
        v.setBackgroundColor(0x33FFFFFF);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }

    private View buildUi() {
        // ---- outer shell: tab bar + 4 pages ----
        LinearLayout base = new LinearLayout(this);
        base.setOrientation(LinearLayout.VERTICAL);
        base.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        base.setBackgroundColor(BG);

        LinearLayout tabbar = new LinearLayout(this);
        tabbar.setOrientation(LinearLayout.HORIZONTAL);
        tabbar.setBackgroundColor(0xFF0D1320);
        tabbar.setPadding(dp(6), dp(6), dp(6), dp(6));
        base.addView(tabbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        String[] tabTitles = {"🏠 داشبورد", "📊 نمودار", "📜 معاملات", "⚙️ تنظیمات"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            tabs[i] = new LinearLayout(this);
            tabs[i].setOrientation(LinearLayout.VERTICAL);
            tabs[i].setGravity(Gravity.CENTER);
            tabs[i].setPadding(dp(4), dp(9), dp(4), dp(9));
            TextView tt = text(tabTitles[i], 13f, TEXT2, i == 0);
            tabs[i].addView(tt);
            tabs[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTab(idx);
                }
            });
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) tl.setMarginStart(dp(4));
            tabs[i].setLayoutParams(tl);
            tabbar.addView(tabs[i]);
        }

        LinearLayout pageHost = new LinearLayout(this);
        pageHost.setOrientation(LinearLayout.VERTICAL);
        base.addView(pageHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        String[] pageNames = {"dash", "chart", "trades", "settings"};
        LinearLayout[] pageRoots = new LinearLayout[4];
        for (int i = 0; i < 4; i++) {
            pages[i] = new ScrollView(this);
            pages[i].setFillViewport(true);
            pages[i].setVisibility(i == 0 ? View.VISIBLE : View.GONE);
            pageRoots[i] = new LinearLayout(this);
            pageRoots[i].setOrientation(LinearLayout.VERTICAL);
            pageRoots[i].setPadding(dp(14), dp(12), dp(14), dp(24));
            pages[i].addView(pageRoots[i], new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            pageHost.addView(pages[i], plp);
        }
        LinearLayout root = pageRoots[0];      // dashboard (default target of existing code)
        LinearLayout linDash = pageRoots[0];
        LinearLayout linChart = pageRoots[1];
        LinearLayout linTrades = pageRoots[2];
        LinearLayout linSettings = pageRoots[3];
        final LinearLayout[] P = pageRoots;

        // ---------- header (gradient hero) ----------
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable heroBg =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                        new int[]{0xFF0E1B33, 0xFF0B1220, 0xFF0E2A26});
        heroBg.setCornerRadius(dp(20));
        heroBg.setStroke(dp(1), 0xFF23304A);
        hero.setBackground(heroBg);
        hero.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hero.setLayoutParams(heroLp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iv = new ImageView(this);
        iv.setImageResource(R.mipmap.ic_launcher);
        head.addView(iv, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(10), 0, 0, 0);
        titles.addView(text("ربات تریدر نوبیتکس", 19f, TEXT, true));
        titles.addView(text("معامله‌گر خودکار بازار رمزارز", 12f, TEXT2, false));
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        statusPill = text("● متوقف", 12f, TEXT2, true);
        statusPill.setPadding(dp(10), dp(5), dp(10), dp(5));
        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
        pill.setColor(0xFF1B2334);
        pill.setCornerRadius(dp(20));
        statusPill.setBackground(pill);
        head.addView(statusPill);
        hero.addView(head);
        linDash.addView(hero);

        // ---------- live market chips (tap to switch symbol) ----------
        android.widget.HorizontalScrollView chipsScroll = new android.widget.HorizontalScrollView(this);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < CHIP_SYMS.length; i++) {
            final int fi = i;
            final Market cm = Market.of(CHIP_SYMS[i]);
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.VERTICAL);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(12), dp(7), dp(12), dp(7));
            android.graphics.drawable.GradientDrawable chipBg =
                    new android.graphics.drawable.GradientDrawable();
            chipBg.setColor(0xFF151D30);
            chipBg.setCornerRadius(dp(12));
            chipBg.setStroke(dp(1), STROKE);
            chip.setBackground(chipBg);
            chip.addView(text(Market.coinName(cm.src), 10f, TEXT2, true));
            TextView cp = text("…", 12f, TEXT, true);
            chip.addView(cp);
            TextView cc2 = text("", 10f, TEXT2, false);
            chip.addView(cc2);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectSymbol(cm.symbol);
                }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.setMarginStart(i == 0 ? 0 : dp(6));
            chips.addView(chip, clp);
            chipPrice[fi] = cp;
            chipChg[fi] = cc2;
            chipLay[fi] = chip;
        }
        chipsScroll.addView(chips, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams hsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hsLp.topMargin = dp(8);
        linDash.addView(chipsScroll, hsLp);

        // ---------- market card ----------
        LinearLayout mc = card();
        mc.addView(text("💰 بازار و قیمت لحظه‌ای", 15f, GOLD, true));
        LinearLayout mrow = row();
        mrow.addView(label("نماد بازار"));
        symbolTitle = text("", 13f, TEXT2, false);
        mrow.addView(symbolTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mrow.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        mc.addView(mrow);
        symbolSpin = new Spinner(this);
        ArrayAdapter<String> symAd = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, Market.titles());
        symAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        symbolSpin.setAdapter(symAd);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        spLp.topMargin = dp(6);
        symbolSpin.setLayoutParams(spLp);
        mc.addView(symbolSpin);
        LinearLayout prow = row();
        LinearLayout pcol = new LinearLayout(this);
        pcol.setOrientation(LinearLayout.VERTICAL);
        priceView = text("—", 26f, TEXT, true);
        changeView = text("", 14f, TEXT2, true);
        livePill = text("در حال اتصال به نوبیتکس…", 11f, TEXT2, true);
        pcol.addView(priceView);
        pcol.addView(changeView);
        pcol.addView(livePill);
        prow.addView(pcol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mc.addView(prow);
        linDash.addView(mc);

        // ---------- chart card ----------
        LinearLayout chc = card();
        chc.addView(text("🕯️ نمودار و تحلیل لحظه‌ای", 15f, GOLD, true));

        // overlay toggles
        LinearLayout ovRow = row();
        ovRow.setGravity(Gravity.CENTER_VERTICAL);
        final TextView bbChip = text(" باند بولینگر ", 11f, TEXT2, false);
        final TextView e50Chip = text(" EMA50 ", 11f, TEXT2, false);
        android.graphics.drawable.GradientDrawable chipOff =
                new android.graphics.drawable.GradientDrawable();
        chipOff.setColor(0xFF151D30);
        chipOff.setCornerRadius(dp(12));
        chipOff.setStroke(dp(1), STROKE);
        bbChip.setBackground(chipOff);
        android.graphics.drawable.GradientDrawable chipOff2 =
                new android.graphics.drawable.GradientDrawable();
        chipOff2.setColor(0xFF151D30);
        chipOff2.setCornerRadius(dp(12));
        chipOff2.setStroke(dp(1), STROKE);
        e50Chip.setBackground(chipOff2);
        bbChip.setPadding(dp(8), dp(4), dp(8), dp(4));
        e50Chip.setPadding(dp(8), dp(4), dp(8), dp(4));
        bbChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean on = chartView.toggleBb();
                bbChip.setTextColor(on ? GOLD : TEXT2);
                bbChip.setTypeface(Typeface.create(Typeface.DEFAULT, on ? Typeface.BOLD : Typeface.NORMAL));
            }
        });
        e50Chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean on = chartView.toggleEma50();
                e50Chip.setTextColor(on ? GOLD : TEXT2);
                e50Chip.setTypeface(Typeface.create(Typeface.DEFAULT, on ? Typeface.BOLD : Typeface.NORMAL));
            }
        });
        ovRow.addView(bbChip);
        LinearLayout.LayoutParams e5lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        e5lp.setMarginStart(dp(6));
        ovRow.addView(e50Chip, e5lp);
        chc.addView(ovRow);

        chartView = new ChartView(this);
        LinearLayout.LayoutParams cvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cvLp.topMargin = dp(8);
        chartView.setLayoutParams(cvLp);
        chc.addView(chartView);
        analysisView = text("خط طلایی: EMA21 — میله‌های پایین: حجم معاملات — خط‌چین: قیمت ورود شما", 12f, TEXT2, false);
        chc.addView(margin(analysisView, 6));
        playBtn = button("🎬 پخش شبیه‌سازی روی نمودار", 0xFF2A3752);
        chc.addView(margin(playBtn, 8));
        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (replaying) stopReplay();
                else startReplay();
            }
        });
        linChart.addView(chc);

        // ---------- control card ----------
        LinearLayout cc = card();
        startBtn = button("▶  شروع ربات", GREEN);
        startBtn.setTextSize(17f);
        startBtn.setPadding(dp(12), dp(14), dp(12), dp(14));
        cc.addView(startBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        linDash.addView(cc);

        // ---------- position card ----------
        LinearLayout pc = card();
        pc.addView(text("💼 وضعیت و پوزیشن", 15f, GOLD, true));
        posView = text("در انتظار سیگنال خرید…", 14f, TEXT, false);
        posView.setLineSpacing(dp(3), 1f);
        pc.addView(margin(posView, 8));
        riskGauge = new RiskGauge(this);
        pc.addView(margin(riskGauge, 4));
        statsView = text("", 13f, TEXT2, false);
        pc.addView(margin(statsView, 6));
        pc.addView(margin(text("روند سود تجمعی", 13f, GOLD, true), 12));
        equityView = new EquityView(this);
        pc.addView(margin(equityView, 4));
        sellResetRow = new LinearLayout(this);
        sellResetRow.setOrientation(LinearLayout.HORIZONTAL);
        sellBtn = button("فروش فوری", RED);
        android.graphics.drawable.GradientDrawable resetBg = new android.graphics.drawable.GradientDrawable();
        resetBg.setColor(CARD2);
        resetBg.setCornerRadius(dp(12));
        resetBg.setStroke(dp(1), STROKE);
        resetBtn = button("بازنشانی وضعیت", CARD2);
        resetBtn.setBackground(resetBg);
        resetBtn.setTextColor(TEXT2);
        LinearLayout.LayoutParams sbl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sbl.setMarginEnd(dp(8));
        sellResetRow.addView(sellBtn, sbl);
        sellResetRow.addView(resetBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams srl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srl.topMargin = dp(10);
        sellResetRow.setLayoutParams(srl);
        pc.addView(sellResetRow);
        linDash.addView(pc);

        // ---------- settings card ----------
        LinearLayout sc = card();
        sc.addView(text("⚙️ تنظیمات ربات", 15f, GOLD, true));

        sc.addView(margin(label("بازه کندل (تایم‌فریم)"), 10));
        tfSpin = new Spinner(this);
        ArrayAdapter<String> tfAd = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"۱۵ دقیقه", "۱ ساعت", "۴ ساعت", "۱ روز"});
        tfAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        tfSpin.setAdapter(tfAd);
        tfSpin.setLayoutParams(spinnerLp());
        sc.addView(tfSpin);

        sc.addView(margin(label("فاصله بررسی بازار"), 10));
        intervalSpin = new Spinner(this);
        ArrayAdapter<String> ivAd = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"۳۰ ثانیه", "۱ دقیقه", "۲ دقیقه", "۵ دقیقه", "۱۰ دقیقه", "۱۵ دقیقه"});
        ivAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        intervalSpin.setAdapter(ivAd);
        intervalSpin.setLayoutParams(spinnerLp());
        sc.addView(intervalSpin);

        sc.addView(margin(label("استراتژی معاملاتی"), 10));
        String[] stratNames = new String[Strategy.ALL.length];
        for (int i = 0; i < Strategy.ALL.length; i++) stratNames[i] = Strategy.ALL[i].name();
        stratSpin = new Spinner(this);
        ArrayAdapter<String> stAd = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, stratNames);
        stAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        stratSpin.setAdapter(stAd);
        stratSpin.setLayoutParams(spinnerLp());
        sc.addView(stratSpin);
        stratDesc = text("", 12f, TEXT2, false);
        stratDesc.setLineSpacing(dp(2), 1f);
        sc.addView(margin(stratDesc, 4));

        sc.addView(margin(label("مبلغ هر معامله"), 10));
        amountEdit = numberInput("مثلاً 500000");
        amountHint = text("تومان", 12f, TEXT2, false);
        LinearLayout amRow = row();
        amRow.addView(amountEdit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        amRow.addView(amountHint);
        sc.addView(amRow);

        LinearLayout slRow = row();
        LinearLayout slCol = new LinearLayout(this);
        slCol.setOrientation(LinearLayout.VERTICAL);
        slCol.addView(label("حد ضرر (٪)"));
        slEdit = numberInput("4");
        slCol.addView(slEdit);
        LinearLayout tpCol = new LinearLayout(this);
        tpCol.setOrientation(LinearLayout.VERTICAL);
        tpCol.addView(label("حد سود (٪)"));
        tpEdit = numberInput("8");
        tpCol.addView(tpEdit);
        slRow.addView(slCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams tpLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tpLp.setMarginStart(dp(16));
        slRow.addView(tpCol, tpLp);
        sc.addView(slRow);

        // higher-timeframe trend filter
        LinearLayout htfHead = row();
        LinearLayout htfCol = new LinearLayout(this);
        htfCol.setOrientation(LinearLayout.VERTICAL);
        htfCol.addView(text("فیلتر تایم‌فریم بالاتر", 14f, TEXT, true));
        htfCol.addView(text("خرید فقط وقتی انجام شود که روند تایم‌فریم ۴ برابر بزرگ‌تر هم صعودی باشد", 11f, TEXT2, false));
        htfHead.addView(htfCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        htfSwitch = new Switch(this);
        htfHead.addView(htfSwitch);
        sc.addView(margin(htfHead, 12));

        // daily loss limit
        LinearLayout dlRow = row();
        LinearLayout dlCol = new LinearLayout(this);
        dlCol.setOrientation(LinearLayout.VERTICAL);
        dlCol.addView(text("حد ضرر روزانه (٪)", 14f, TEXT, true));
        dlCol.addView(text("اگر ضرر قطعی امروز از این درصد سرمایه بیشتر شود، ورود جدید تا فردا متوقف می‌شود (۰ = خاموش)", 11f, TEXT2, false));
        dlRow.addView(dlCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        dailyEdit = numberInput("5");
        dailyEdit.setMinWidth(dp(70));
        dailyEdit.setMaxWidth(dp(90));
        dlRow.addView(dailyEdit);
        sc.addView(margin(dlRow, 4));

        // partial take-profit
        LinearLayout tp1Head = row();
        LinearLayout tp1Col = new LinearLayout(this);
        tp1Col.setOrientation(LinearLayout.VERTICAL);
        tp1Col.addView(text("برداشت سود پله‌ای (TP1)", 14f, TEXT, true));
        tp1Col.addView(text("با رسیدن به نصف حد سود، نصف پوزیشن با سود بسته می‌شود و برای بقیه، حد ضرر به نقطه ورود (بی‌ضرر) منتقل می‌شود", 11f, TEXT2, false));
        tp1Head.addView(tp1Col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tp1Switch = new Switch(this);
        tp1Head.addView(tp1Switch);
        sc.addView(margin(tp1Head, 12));

        // risk-based dynamic sizing
        LinearLayout riskHead = row();
        LinearLayout riskCol = new LinearLayout(this);
        riskCol.setOrientation(LinearLayout.VERTICAL);
        riskCol.addView(text("حجم پویا بر اساس ریسک", 14f, TEXT, true));
        riskCol.addView(text("مبلغ هر معامله طوری محاسبه شود که رسیدن به حد ضرر فقط درصد مشخصی از سرمایه را از دست بدهد", 11f, TEXT2, false));
        riskHead.addView(riskCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        riskSwitch = new Switch(this);
        riskHead.addView(riskSwitch);
        sc.addView(margin(riskHead, 12));
        riskEdit = numberInput("ریسک هر معامله (٪) — مثلاً 1");
        riskEdit.setVisibility(View.GONE);
        sc.addView(riskEdit);

        // ATR adaptive stops
        LinearLayout atrHead = row();
        LinearLayout atrCol = new LinearLayout(this);
        atrCol.setOrientation(LinearLayout.VERTICAL);
        atrCol.addView(text("حد ضرر/سود تطبیقی با نوسان (ATR)", 14f, TEXT, true));
        atrCol.addView(text("در بازار پرنوسان حد ضرر بازتر، در بازار آرام‌تر سفت‌تر می‌شود", 11f, TEXT2, false));
        atrHead.addView(atrCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        atrSwitch = new Switch(this);
        atrHead.addView(atrSwitch);
        sc.addView(margin(atrHead, 12));

        // trailing stop
        LinearLayout trHead = row();
        trHead.addView(text("حد ضرر متحرک", 15f, TEXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        trailSwitch = new Switch(this);
        trHead.addView(trailSwitch);
        sc.addView(margin(trHead, 12));
        trailEdit = numberInput("مثلاً 3 (درصد)");
        trailEdit.setVisibility(View.GONE);
        sc.addView(trailEdit);

        // DCA section
        LinearLayout dcaHead = row();
        dcaHead.addView(text("خرید پله‌ای (DCA)", 15f, TEXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        dcaSwitch = new Switch(this);
        dcaHead.addView(dcaSwitch);
        sc.addView(margin(dcaHead, 12));

        dcaBox = new LinearLayout(this);
        dcaBox.setOrientation(LinearLayout.VERTICAL);
        dcaBox.addView(margin(label("فاصله بین پله‌ها"), 8));
        dcaSpin = new Spinner(this);
        ArrayAdapter<String> dcaAd = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"هر ۴ کندل", "هر ۱۲ کندل", "هر ۲۴ کندل", "هر ۴۸ کندل"});
        dcaAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        dcaSpin.setAdapter(dcaAd);
        dcaSpin.setLayoutParams(spinnerLp());
        dcaBox.addView(dcaSpin);
        dcaBox.addView(margin(label("حداکثر پله در هر پوزیشن"), 10));
        dcaMaxSpin = new Spinner(this);
        ArrayAdapter<String> dcaMaxAd = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"۳ پله", "۵ پله", "۱۰ پله", "بدون محدودیت"});
        dcaMaxAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        dcaMaxSpin.setAdapter(dcaMaxAd);
        dcaMaxSpin.setLayoutParams(spinnerLp());
        dcaBox.addView(dcaMaxSpin);
        TextView dcaHint = text("در این حالت ربات به‌جای انتظار برای سیگنال، در فواصل منظم خرید می‌کند؛ قیمت ورود میانگینِ وزنی پله‌ها می‌شود و خروج طبق حد ضرر/سود، حد ضرر متحرک یا سیگنال فروش انجام می‌شود.", 11f, TEXT2, false);
        dcaHint.setLineSpacing(dp(2), 1f);
        dcaBox.addView(margin(dcaHint, 6));
        dcaBox.setVisibility(View.GONE);
        sc.addView(dcaBox);

        sc.addView(margin(vline(), 12));
        LinearLayout liveRow = row();
        liveRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView liveLbl = text("معامله واقعی (Live)", 15f, TEXT, true);
        liveRow.addView(liveLbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        liveSwitch = new Switch(this);
        liveRow.addView(liveSwitch);
        sc.addView(liveRow);
        liveWarn = text("⚠️ در این حالت سفارش‌های واقعی در حساب نوبیتکس شما ثبت می‌شود!", 12f, RED, false);
        liveWarn.setVisibility(View.GONE);
        sc.addView(margin(liveWarn, 4));

        // --- telegram section ---
        sc.addView(margin(vline(), 12));
        sc.addView(text("اعلان تلگرام (اختیاری)", 14f, GOLD, true));
        sc.addView(margin(label("توکن ربات تلگرام — از @BotFather بگیرید"), 8));
        tgTokenEdit = new EditText(this);
        tgTokenEdit.setHint("123456:ABC-DEF...");
        tgTokenEdit.setTextSize(14f);
        tgTokenEdit.setTextColor(TEXT);
        tgTokenEdit.setHintTextColor(TEXT2);
        sc.addView(tgTokenEdit);
        sc.addView(margin(label("شناسه چت — از @userinfobot بگیرید"), 8));
        tgChatEdit = new EditText(this);
        tgChatEdit.setHint("123456789");
        tgChatEdit.setTextSize(14f);
        tgChatEdit.setTextColor(TEXT);
        tgChatEdit.setHintTextColor(TEXT2);
        tgChatEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        sc.addView(tgChatEdit);
        tgTestBtn = button("ارسال پیام آزمایشی", 0xFF2A3752);
        tgTestBtn.setTextColor(TEXT);
        LinearLayout.LayoutParams tgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tgLp.topMargin = dp(8);
        tgTestBtn.setLayoutParams(tgLp);
        sc.addView(tgTestBtn);
        TextView tgHint = text("با فعال بودن VPN، پیام خرید/فروش و روشن/خاموش شدن ربات برایتان ارسال می‌شود.", 11f, TEXT2, false);
        sc.addView(margin(tgHint, 6));
        linSettings.addView(sc);

        // ---------- account card ----------
        LinearLayout ac = card();
        ac.addView(text("🔑 اتصال به حساب نوبیتکس", 15f, GOLD, true));
        ac.addView(margin(label("توکن یا کلید عمومی API"), 10));
        tokenEdit = new EditText(this);
        tokenEdit.setHint("توکن کلاسیک یا کلید عمومی (Key) از پنل نوبیتکس");
        tokenEdit.setTextSize(14f);
        tokenEdit.setTextColor(TEXT);
        tokenEdit.setHintTextColor(TEXT2);
        tokenEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenEdit.setTransformationMethod(PasswordTransformationMethod.getInstance());
        tokenEdit.setTypeface(Typeface.DEFAULT);
        ac.addView(tokenEdit);
        ac.addView(margin(label("سکرت کی (کلید خصوصی) — فقط برای کلید API جدید"), 10));
        secretEdit = new EditText(this);
        secretEdit.setHint("اگر نوبیتکس به شما Key + Secret داده، Secret را اینجا بگذارید");
        secretEdit.setTextSize(14f);
        secretEdit.setTextColor(TEXT);
        secretEdit.setHintTextColor(TEXT2);
        secretEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secretEdit.setTransformationMethod(PasswordTransformationMethod.getInstance());
        secretEdit.setTypeface(Typeface.DEFAULT);
        ac.addView(secretEdit);
        connView = text("دو حالت پشتیبانی می‌شود:\n• توکن کلاسیک → فیلد بالا فقط\n• کلید API جدید (Key + Secret) → هر دو فیلد، با امضای Ed25519", 11f, TEXT2, false);
        connView.setLineSpacing(dp(2), 1f);
        ac.addView(margin(connView, 6));
        testBtn = button("🔌 تست اتصال", 0xFF2A3752);
        testBtn.setTextColor(TEXT);
        LinearLayout.LayoutParams tbLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tbLp.topMargin = dp(10);
        ac.addView(testBtn, tbLp);
        connectBtn = button("بررسی موجودی 💰", 0xFF2A3752);
        connectBtn.setTextColor(TEXT);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cbLp.topMargin = dp(10);
        cbLp.setMarginStart(dp(8));
        connectBtn.setLayoutParams(cbLp);
        ac.addView(connectBtn);
        walletView = text("برای معامله واقعی، کلید را وارد و بررسی کنید. کلید فقط روی همین گوشی ذخیره می‌شود.", 12f, TEXT2, false);
        walletView.setLineSpacing(dp(2), 1f);
        ac.addView(margin(walletView, 8));
        linSettings.addView(ac);

        testBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runConnTest();
            }
        });

        // ---- auto-save the API key/secret while typing (debounced) ----
        keySaver = new Runnable() {
            @Override
            public void run() {
                saveKeys(false);
            }
        };
        android.text.TextWatcher keyWatcher = new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence cs, int a, int b2, int c2) {
            }

            @Override
            public void onTextChanged(CharSequence cs, int a, int b2, int c2) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                ui.removeCallbacks(keySaver);
                ui.postDelayed(keySaver, 900);
            }
        };
        tokenEdit.addTextChangedListener(keyWatcher);
        secretEdit.addTextChangedListener(keyWatcher);

        // ---------- backtest card ----------
        LinearLayout bc = card();
        bc.addView(text("🧪 بک‌تست استراتژی‌ها", 15f, GOLD, true));
        backtestBtn = button("اجرای بک‌تست روی داده تاریخی", 0xFF2A3752);
        backtestBtn.setTextColor(TEXT);
        LinearLayout.LayoutParams bbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bbLp.topMargin = dp(10);
        backtestBtn.setLayoutParams(bbLp);
        bc.addView(backtestBtn);
        optBtn = button("🎯 بهینه‌ساز خودکار حد ضرر/سود", 0xFF3A2E12);
        LinearLayout.LayoutParams obLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        obLp.topMargin = dp(8);
        optBtn.setLayoutParams(obLp);
        optBtn.setTextColor(GOLD);
        bc.addView(optBtn);
        backtestView = text("هر چهار استراتژی روی ۵۰۰ کندل اخیر بازار انتخابی شبیه‌سازی می‌شوند و نتیجه مقایسه داده می‌شود.", 12f, TEXT2, false);
        backtestView.setLineSpacing(dp(3), 1f);
        bc.addView(margin(backtestView, 8));
        linChart.addView(bc);

        // ---------- log card ----------
        LinearLayout lc = card();
        lc.addView(text("📋 گزارش و معاملات", 15f, GOLD, true));
        logView = text("هنوز رخدادی ثبت نشده است.", 12f, TEXT2, false);
        logView.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams lvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lvLp.topMargin = dp(8);
        logView.setLayoutParams(lvLp);
        lc.addView(logView);
        csvBtn = button("📄 خروجی CSV معاملات", 0xFF2A3752);
        csvBtn.setTextColor(TEXT);
        LinearLayout.LayoutParams csvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        csvLp.topMargin = dp(10);
        csvBtn.setLayoutParams(csvLp);
        lc.addView(csvBtn);
        linTrades.addView(lc);

        // ---------- footer ----------
        TextView foot = text("NobiTrader v1.9 — معامله در بازار رمزارز با ریسک همراه است؛ مسئولیت معاملات بر عهده کاربر است. همیشه اول با حالت شبیه‌سازی تست کنید.", 11f, TEXT2, false);
        foot.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fLp.topMargin = dp(14);
        foot.setLayoutParams(fLp);
        // ---------- price alerts ----------
        LinearLayout alc = card();
        alc.addView(text("🔔 آلارم قیمت", 15f, GOLD, true));
        alc.addView(text("وقتی قیمت به سطح دلخواه برسد، اعلان و پیام تلگرام دریافت می‌کنید (حتی وقتی ربات خاموش است، با هر چرخه بررسی فعال می‌شود)", 11f, TEXT2, false));
        LinearLayout alertRow = row();
        alertSymSpin = new Spinner(this);
        ArrayAdapter<String> alertSymAd = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, Market.titles());
        alertSymAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        alertSymSpin.setAdapter(alertSymAd);
        alertRow.addView(alertSymSpin, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f));
        alertDirSpin = new Spinner(this);
        ArrayAdapter<String> alertDirAd = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, new String[]{"بالاتر از", "پایین‌تر از"});
        alertDirAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        alertDirSpin.setAdapter(alertDirAd);
        alertRow.addView(alertDirSpin, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        alc.addView(alertRow);
        LinearLayout alertRow2 = row();
        alertPriceEdit = numberInput("قیمت هدف — مثلاً 125000");
        alertRow2.addView(alertPriceEdit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button addAlertBtn = button("＋ افزودن", GOLD);
        addAlertBtn.setTextColor(0xFF0D1320);
        alertRow2.addView(addAlertBtn);
        alc.addView(alertRow2);
        alertsList = new LinearLayout(this);
        alertsList.setOrientation(LinearLayout.VERTICAL);
        alc.addView(alertsList);
        linSettings.addView(alc);

        addAlertBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    double pr = parse(alertPriceEdit.getText().toString(), -1);
                    if (pr <= 0) {
                        toast("قیمت معتبر وارد کنید");
                        return;
                    }
                    int symPos = alertSymSpin.getSelectedItemPosition();
                    String sym = symPos >= 0 && symPos < Market.SYMBOLS.length
                            ? Market.SYMBOLS[symPos] : Market.SYMBOLS[0];
                    boolean above = alertDirSpin.getSelectedItemPosition() == 0;
                    org.json.JSONArray arr = new org.json.JSONArray(prefs.alertsJson());
                    org.json.JSONObject a = new org.json.JSONObject();
                    a.put("sym", sym);
                    a.put("dir", above ? "above" : "below");
                    a.put("price", pr);
                    arr.put(a);
                    prefs.setAlertsJson(arr.toString());
                    alertPriceEdit.setText("");
                    renderAlerts();
                    toast("آلارم ثبت شد ✅");
                } catch (Exception e) {
                    toast("قیمت معتبر وارد کنید");
                }
            }
        });

        linSettings.addView(foot);

        hookListeners();
        showTab(0);
        return base;
    }

    private void showTab(int idx) {
        for (int i = 0; i < 4; i++) {
            pages[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            boolean on = i == idx;
            TextView t = (TextView) tabs[i].getChildAt(0);
            t.setTextColor(on ? GOLD : TEXT2);
            t.setTypeface(Typeface.create(Typeface.DEFAULT, on ? Typeface.BOLD : Typeface.NORMAL));
            android.graphics.drawable.GradientDrawable tb = new android.graphics.drawable.GradientDrawable();
            tb.setColor(on ? 0xFF1A2438 : 0x00000000);
            tb.setCornerRadius(dp(10));
            tabs[i].setBackground(tb);
        }
    }

    private LinearLayout.LayoutParams spinnerLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        return lp;
    }

    private View margin(View v, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        v.setLayoutParams(lp);
        return v;
    }

    // ---------------------------------------------------------------- actions

    private void hookListeners() {
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleBot();
            }
        });

        connectBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkConnection();
            }
        });

        backtestBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runBacktest();
            }
        });

        sellBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirm("فروش فوری", "کل پوزیشن فعلی با قیمت بازار فروخته شود؟", new Runnable() {
                    @Override
                    public void run() {
                        startThread(new Runnable() {
                            @Override
                            public void run() {
                                engine.forceSell();
                            }
                        });
                    }
                });
            }
        });

        resetBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirm("بازنشانی وضعیت", "پوزیشن و آمار معاملات پاک شود؟ (تأثیری روی موجودی واقعی ندارد)", new Runnable() {
                    @Override
                    public void run() {
                        prefs.resetState();
                        Store.clear();
                        refreshUi();
                    }
                });
            }
        });

        symbolSpin.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                prefs.setSymbol(Market.SYMBOLS[position]);
                updateAmountHint();
                shownVersion = -1; // force refresh of market texts
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        tfSpin.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                prefs.setResolution(new String[]{"15", "60", "240", "D"}[position]);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        intervalSpin.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                prefs.setIntervalSec(new int[]{30, 60, 120, 300, 600, 900}[position]);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        stratSpin.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                prefs.setStrategyId(position);
                stratDesc.setText(Strategy.ALL[position].desc());
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        liveSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    confirmLive();
                } else {
                    prefs.setLive(false);
                    liveWarn.setVisibility(View.GONE);
                }
            }
        });

        trailSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                trailEdit.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                if (isChecked && trailEdit.getText().toString().trim().isEmpty()) {
                    trailEdit.setText("3");
                }
            }
        });

        riskSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                riskEdit.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                if (isChecked && riskEdit.getText().toString().trim().isEmpty()) {
                    riskEdit.setText("1");
                }
            }
        });

        optBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runOptimizer();
            }
        });

        dcaSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                dcaBox.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            }
        });

        tgTestBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testTelegram();
            }
        });

        csvBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportCsv();
            }
        });
    }

    /** save + verify the telegram settings by sending a test message */
    private void testTelegram() {
        final String token = tgTokenEdit.getText().toString().trim();
        final String chat = tgChatEdit.getText().toString().trim();
        if (token.isEmpty() || chat.isEmpty()) {
            toast("توکن ربات و شناسه چت را وارد کنید");
            return;
        }
        prefs.setTg(token, chat);
        tgTestBtn.setEnabled(false);
        toast("در حال ارسال پیام آزمایشی…");
        startThread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = Telegram.send(token, chat,
                        "✅ پیام آزمایشی ربات تریدر نوبیتکس — اتصال برقرار است.");
                postUi(new Runnable() {
                    @Override
                    public void run() {
                        tgTestBtn.setEnabled(true);
                        toast(ok ? "پیام تلگرام ارسال شد ✅" : "ارسال ناموفق — توکن/چت/اینترنت (VPN) را بررسی کنید");
                    }
                });
            }
        });
    }

    /** share the trade journal as a real .csv file (Downloads) or as text on old devices */
    private void exportCsv() {
        String csv = Store.csv();
        if (csv == null || csv.split("\n").length < 2) {
            toast("هنوز معامله‌ای ثبت نشده است");
            return;
        }
        try {
            String name = "NobiTrader-trades-" +
                    new java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new java.util.Date())
                    + ".csv";
            if (Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/csv");
                android.net.Uri uri = getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri != null) {
                    java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                    os.write(csv.getBytes("UTF-8"));
                    os.flush();
                    os.close();
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("text/csv");
                    share.putExtra(Intent.EXTRA_STREAM, uri);
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(share, "اشتراک فایل CSV"));
                    toast("فایل " + name + " در پوشه Downloads ذخیره شد");
                    return;
                }
            }
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_SUBJECT, name);
            share.putExtra(Intent.EXTRA_TEXT, csv);
            startActivity(Intent.createChooser(share, "اشتراک گزارش معاملات"));
        } catch (Exception e) {
            toast("خطا در ساخت فایل: " + e.getMessage());
        }
    }

    private void confirmLive() {
        String token = tokenEdit.getText().toString().trim();
        if (token.isEmpty()) {
            toast("برای معامله واقعی ابتدا توکن API را وارد کنید");
            liveSwitch.setChecked(false);
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("فعال‌سازی معامله واقعی");
        b.setMessage("در حالت واقعی، ربات سفارش خرید و فروش واقعی در حساب نوبیتکس شما ثبت می‌کند و ممکن است ضرر مالی داشته باشید.\n\nمطمئنید؟ (به توکن فقط دسترسی «معامله» بدهید، نه برداشت)");
        b.setPositiveButton("بله، فعال کن", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                prefs.setLive(true);
                liveWarn.setVisibility(View.VISIBLE);
                Store.log("⚡ حالت معامله واقعی فعال شد");
            }
        });
        b.setNegativeButton("بی‌خیال", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                liveSwitch.setChecked(false);
            }
        });
        b.show();
    }

    private void confirm(String title, String msg, final Runnable onYes) {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(title);
        b.setMessage(msg);
        b.setPositiveButton("بله", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                onYes.run();
            }
        });
        b.setNegativeButton("خیر", null);
        b.show();
    }

    private void toggleBot() {
        if (engine.isRunning()) {
            BotService.stop(this);
            toast("ربات متوقف شد");
            refreshUi();
            return;
        }
        if (!saveFromUi()) return;
        Prefs.Cfg cfg = prefs.cfg();
        if (cfg.live && cfg.token.isEmpty()) {
            toast("برای معامله واقعی ابتدا توکن API را وارد کنید");
            return;
        }
        Store.log("راه‌اندازی ربات: " + Market.of(cfg.symbol).title
                + " | استراتژی: " + Strategy.byId(cfg.strategyId).name()
                + (cfg.dca ? " + خرید پله‌ای" : "")
                + " | تایم‌فریم: " + tfName(cfg.resolution)
                + " | حالت: " + (cfg.live ? "واقعی ⚡" : "شبیه‌سازی"));
        BotService.start(this);
        toast("ربات شروع شد ✅");
        refreshUi();
    }

    private boolean saveFromUi() {
        double amount = parse(amountEdit.getText().toString(), -1);
        double sl = parse(slEdit.getText().toString(), -1);
        double tp = parse(tpEdit.getText().toString(), -1);
        if (amount <= 0) {
            toast("مبلغ هر معامله را وارد کنید");
            return false;
        }
        if (sl <= 0 || tp <= 0 || sl > 95 || tp > 1000) {
            toast("حد ضرر و حد سود معتبر وارد کنید");
            return false;
        }
        double trail = 0;
        if (trailSwitch.isChecked()) {
            trail = parse(trailEdit.getText().toString(), -1);
            if (trail < 0.3 || trail > 50) {
                toast("درصد حد ضرر متحرک باید بین ۰.۳ تا ۵۰ باشد");
                return false;
            }
        }
        Market m = Market.of(Market.SYMBOLS[symbolSpin.getSelectedItemPosition()]);
        boolean live = liveSwitch.isChecked();
        double min = m.isRls ? NobitexApi.MIN_RLS / 10.0 : NobitexApi.MIN_USDT;
        double paperMin = m.isRls ? 10000 : 1;
        if (amount < (live ? min * 1.05 : paperMin)) {
            toast("حداقل مبلغ معامله: " + Fmt.quote(min, m.isRls) + " " + m.quoteUnit()
                    + (live ? "" : " (برای شبیه‌سازی حداقل " + Fmt.quote(paperMin, m.isRls) + ")"));
            return false;
        }
        prefs.setToken(tokenEdit.getText().toString().trim());
        prefs.setApiSecret(secretEdit.getText().toString().trim());
        prefs.setTradeAmount(amount);
        prefs.setSlPct(sl);
        prefs.setTpPct(tp);
        double risk = 1;
        if (riskSwitch.isChecked()) {
            risk = parse(riskEdit.getText().toString(), -1);
            if (risk < 0.1 || risk > 10) {
                toast("درصد ریسک هر معامله باید بین ۰.۱ تا ۱۰ باشد");
                return false;
            }
        }
        double dl = parse(dailyEdit.getText().toString(), 5);
        if (dl < 0 || dl > 50) {
            toast("حد ضرر روزانه باید بین ۰ تا ۵۰ باشد");
            return false;
        }
        prefs.setHtfFilter(htfSwitch.isChecked());
        prefs.setDailyLossPct(dl);
        prefs.setTrailingPct(trail);
        prefs.setTp1Enabled(tp1Switch.isChecked());
        prefs.setRiskSizing(riskSwitch.isChecked());
        prefs.setRiskPct(risk);
        prefs.setAtrStops(atrSwitch.isChecked());
        prefs.setDca(dcaSwitch.isChecked());
        prefs.setDcaInterval(new int[]{4, 12, 24, 48}[dcaSpin.getSelectedItemPosition()]);
        prefs.setDcaMax(new int[]{3, 5, 10, 0}[dcaMaxSpin.getSelectedItemPosition()]);
        prefs.setLive(live);
        return true;
    }

    private void loadIntoUi() {
        Prefs.Cfg c = prefs.cfg();
        tokenEdit.setText(c.token);
        secretEdit.setText(c.apiSecret);
        amountEdit.setText(fmtNum(c.tradeAmount));
        slEdit.setText(fmtNum(c.slPct));
        tpEdit.setText(fmtNum(c.tpPct));
        liveSwitch.setChecked(c.live);
        liveWarn.setVisibility(c.live ? View.VISIBLE : View.GONE);
        stratDesc.setText(Strategy.byId(c.strategyId).desc());

        int symPos = 0;
        for (int i = 0; i < Market.SYMBOLS.length; i++) {
            if (Market.SYMBOLS[i].equals(c.symbol)) symPos = i;
        }
        symbolSpin.setSelection(symPos, false);
        trailSwitch.setChecked(c.trailingPct > 0);
        trailEdit.setText(c.trailingPct > 0 ? fmtNum(c.trailingPct) : "");
        trailEdit.setVisibility(c.trailingPct > 0 ? View.VISIBLE : View.GONE);
        tgTokenEdit.setText(c.tgToken);
        tgChatEdit.setText(c.tgChat);
        tp1Switch.setChecked(c.tp1Enabled);
        htfSwitch.setChecked(c.htfFilter);
        if (dailyEdit != null) dailyEdit.setText(fmtNum(c.dailyLossPct));
        riskSwitch.setChecked(c.riskSizing);
        riskEdit.setText(c.riskSizing ? fmtNum(c.riskPct) : "");
        riskEdit.setVisibility(c.riskSizing ? View.VISIBLE : View.GONE);
        atrSwitch.setChecked(c.atrStops);
        dcaSwitch.setChecked(c.dca);
        dcaBox.setVisibility(c.dca ? View.VISIBLE : View.GONE);
        int[] dcaI = {4, 12, 24, 48};
        int dcaPos = 2;
        for (int i = 0; i < dcaI.length; i++) {
            if (dcaI[i] == c.dcaIntervalCandles) dcaPos = i;
        }
        dcaSpin.setSelection(dcaPos, false);
        int[] dcaM = {3, 5, 10, 0};
        int dcaMaxPos = 1;
        for (int i = 0; i < dcaM.length; i++) {
            if (dcaM[i] == c.dcaMaxLadders) dcaMaxPos = i;
        }
        dcaMaxSpin.setSelection(dcaMaxPos, false);
        String[] tfs = {"15", "60", "240", "D"};
        for (int i = 0; i < tfs.length; i++) {
            if (tfs[i].equals(c.resolution)) tfSpin.setSelection(i, false);
        }
        int[] ivs = {30, 60, 120, 300, 600, 900};
        for (int i = 0; i < ivs.length; i++) {
            if (ivs[i] == c.intervalSec) intervalSpin.setSelection(i, false);
        }
        stratSpin.setSelection(c.strategyId, false);
        updateAmountHint();
    }

    private void updateAmountHint() {
        Market m = Market.of(Market.SYMBOLS[symbolSpin.getSelectedItemPosition()]);
        amountHint.setText(m.quoteUnit());
        symbolTitle.setText("(" + m.symbol + ")");
    }

    private void checkConnection() {
        final String token = tokenEdit.getText().toString().trim();
        if (token.isEmpty()) {
            toast("ابتدا توکن API را وارد کنید");
            return;
        }
        prefs.setToken(token);
        prefs.setApiSecret(secretEdit.getText().toString().trim());
        final String secret = secretEdit.getText().toString().trim();
        walletView.setText("در حال بررسی…");
        startThread(new Runnable() {
            @Override
            public void run() {
                try {
                    NobitexApi api = new NobitexApi(token, secret);
                    final double rls = api.walletBalance("rls");
                    final Market m = Market.of(Market.SYMBOLS[symbolSpin.getSelectedItemPosition()]);
                    final double base = api.walletBalance(m.src);
                    postUi(new Runnable() {
                        @Override
                        public void run() {
                            StringBuilder sb = new StringBuilder("✅ اتصال برقرار است.\n");
                            if (rls >= 0) sb.append("موجودی تومان: ").append(Fmt.toman(rls)).append('\n');
                            else sb.append("کیف پول تومان یافت نشد\n");
                            if (base >= 0) sb.append("موجودی ").append(Market.coinName(m.src)).append(": ").append(Fmt.amount(base));
                            walletView.setTextColor(GREEN);
                            walletView.setText(sb.toString().trim());
                        }
                    });
                } catch (final Exception e) {
                    postUi(new Runnable() {
                        @Override
                        public void run() {
                            walletView.setTextColor(RED);
                            walletView.setText("❌ اتصال ناموفق: " + (e.getMessage() != null ? e.getMessage() : e));
                        }
                    });
                }
            }
        });
    }

    private void runBacktest() {
        if (btBusy) return;
        if (!saveFromUi()) return;
        btBusy = true;
        backtestBtn.setEnabled(false);
        final Prefs.Cfg cfg = prefs.cfg();
        final Market m = Market.of(cfg.symbol);
        backtestView.setText("⏳ در حال دریافت داده تاریخی " + m.title + "…");
        startThread(new Runnable() {
            @Override
            public void run() {
                String out;
                try {
                    NobitexApi api = new NobitexApi(cfg.token, cfg.apiSecret);
                    Candle[] cs = api.udfHistory(m.symbol, cfg.resolution, 500);
                    StringBuilder sb = new StringBuilder();
                    sb.append("📊 نتیجه بک‌تست روی ").append(cs.length).append(" کندل ").append(tfName(cfg.resolution)).append(" (")
                            .append(m.title).append(")\n");
                    sb.append("حد ضرر ").append(Fmt.pct(-cfg.slPct)).append(" | حد سود ").append(Fmt.pct(cfg.tpPct));
                    if (cfg.atrStops) {
                        sb.append(" | حد ضرر تطبیقی ATR");
                    }
                    if (cfg.trailingPct > 0) {
                        sb.append(" | حد ضرر متحرک ").append(fmtNum(cfg.trailingPct)).append("٪");
                    }
                    if (cfg.dca) {
                        sb.append(" | خرید پله‌ای هر ").append(cfg.dcaIntervalCandles).append(" کندل");
                    }
                    sb.append(" | کارمزد تقریبی ۰.۲۵٪\n\n");
                    Backtester.Result best = null;
                    for (int s = 0; s < Strategy.ALL.length; s++) {
                        Backtester.Result r = Backtester.run(Strategy.ALL[s], cs, cfg.slPct, cfg.tpPct,
                                cfg.trailingPct, cfg.dca ? cfg.dcaIntervalCandles : 0,
                                cfg.dca ? cfg.dcaMaxLadders : 0, cfg.atrStops, cfg.tp1Enabled,
                                Backtester.DEFAULT_FEE);
                        if (!r.ok) continue;
                        if (best == null || r.netPct > best.netPct) best = r;
                        sb.append("• ").append(r.name).append('\n');
                        sb.append("   بازده: ").append(Fmt.pct(r.netPct))
                                .append("  |  خرید و نگهداری: ").append(Fmt.pct(r.bhPct)).append('\n');
                        sb.append("   معاملات: ").append(r.trades)
                                .append("  |  نرخ برد: ").append(r.trades == 0 ? "—" : Math.round(100.0 * r.wins / r.trades) + "٪")
                                .append("  |  بیشترین افت: ").append(String.format(Locale.US, "%.1f", r.maxDDPct)).append("٪\n\n");
                    }
                    if (best != null) {
                        sb.append("🏆 بهترین عملکرد: ").append(best.name);
                    }
                    out = sb.toString().trim();
                } catch (final Exception e) {
                    out = "❌ بک‌تست ناموفق: " + (e.getMessage() != null ? e.getMessage() : e);
                }
                final String res = out;
                postUi(new Runnable() {
                    @Override
                    public void run() {
                        backtestView.setText(res);
                        btBusy = false;
                        backtestBtn.setEnabled(true);
                    }
                });
            }
        });
    }

    /** grid-search SL/TP for the selected strategy over recent candles and apply the best */
    private void runOptimizer() {
        if (optBusy) return;
        optBusy = true;
        optBtn.setEnabled(false);
        final Prefs.Cfg cfg = prefs.cfg();
        final Strategy strat = Strategy.byId(cfg.strategyId);
        backtestView.setText("⏳ بهینه‌سازی در حال اجرا… (" + strat.name() + " روی ۵۰۰ کندل)");
        startThread(new Runnable() {
            @Override
            public void run() {
                String out;
                double bestSl = cfg.slPct, bestTp = cfg.tpPct;
                try {
                    NobitexApi api = new NobitexApi(null);
                    Candle[] cs = api.udfHistory(Market.of(cfg.symbol).symbol, cfg.resolution, 500);
                    double[] sls = {2, 3, 4, 5, 6, 8};
                    double[] ratios = {1.5, 2.0, 2.5, 3.0};
                    // walk-forward: optimize on the first 60% of candles,
                    // then validate the winner on the unseen last 40%
                    int cut = (int) (cs.length * 0.6);
                    Candle[] train = java.util.Arrays.copyOfRange(cs, 0, cut);
                    Candle[] test = java.util.Arrays.copyOfRange(cs, cut, cs.length);
                    double best = Double.NEGATIVE_INFINITY;
                    StringBuilder top = new StringBuilder();
                    double[] bestNet = {0};
                    java.util.ArrayList<double[]> results = new java.util.ArrayList<double[]>();
                    for (double sl : sls) {
                        for (double rt : ratios) {
                            Backtester.Result r = Backtester.run(strat, train, sl, sl * rt,
                                    0, 0, 0, false, false, Backtester.DEFAULT_FEE);
                            if (!r.ok) continue;
                            results.add(new double[]{r.netPct, sl, sl * rt, r.trades});
                            if (r.netPct > best) {
                                best = r.netPct;
                                bestSl = sl;
                                bestTp = sl * rt;
                                bestNet[0] = r.netPct;
                            }
                        }
                    }
                    java.util.Collections.sort(results, new java.util.Comparator<double[]>() {
                        public int compare(double[] a, double[] b) {
                            return Double.compare(b[0], a[0]);
                        }
                    });
                    for (int i = 0; i < Math.min(3, results.size()); i++) {
                        double[] x = results.get(i);
                        top.append(String.format(Locale.US, "   %.0f%% SL / %.1f%% TP → %+.1f%% (%d معامله)%n",
                                x[1], x[2], x[0], (int) x[3]));
                    }
                    // validate on the out-of-sample window
                    Backtester.Result vf = Backtester.run(strat, test, bestSl, bestTp,
                            0, 0, 0, false, false, Backtester.DEFAULT_FEE);
                    Backtester.Result full = Backtester.run(strat, cs, bestSl, bestTp,
                            0, 0, 0, false, false, Backtester.DEFAULT_FEE);
                    String wf = "\nاعتبارسنجی روی ۴۰٪ داده دیده‌نشده: "
                            + (vf.ok ? Fmt.pct(vf.netPct) : "—")
                            + " | بازده کل پنجره: " + (full.ok ? Fmt.pct(full.netPct) : "—");
                    if (vf.ok && vf.netPct < 0 && bestNet[0] > 0) {
                        wf += "\n⚠️ افت در بازه آزمون — احتمال بیش‌برازش؛ با احتیاط استفاده کنید.";
                    }
                    out = "🎯 بهترین ترکیب: حد ضرر " + fmtNum(bestSl) + "٪ / حد سود " + fmtNum(bestTp)
                            + "٪ → بازده آموزش " + Fmt.pct(bestNet[0])
                            + "\n\nبرترین‌ها (داده آموزش):\n" + top.toString().trim()
                            + wf
                            + "\n\n✅ مقادیر بهینه در تنظیمات اعمال شد.";
                    final double fSl = bestSl, fTp = bestTp;
                    postUi(new Runnable() {
                        @Override
                        public void run() {
                            slEdit.setText(fmtNum(fSl));
                            tpEdit.setText(fmtNum(fTp));
                        }
                    });
                    prefs.setSlPct(fSl);
                    prefs.setTpPct(fTp);
                } catch (final Exception e) {
                    out = "❌ بهینه‌سازی ناموفق: " + (e.getMessage() != null ? e.getMessage() : e);
                }
                final String res = out;
                postUi(new Runnable() {
                    @Override
                    public void run() {
                        backtestView.setText(res);
                        optBtn.setEnabled(true);
                        optBusy = false;
                    }
                });
            }
        });
    }

    private void fetchPrice() {
        if (priceBusy) return;
        priceBusy = true;
        startThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Prefs.Cfg cfg = prefs.cfg();
                    Market m = Market.of(cfg.symbol);
                    NobitexApi api = new NobitexApi(null);
                    double p = api.lastPrice(m.symbol);
                    engine.lastPrice = p;
                    lastPriceOkAt = System.currentTimeMillis();
                    prefs.setLastPrice(p, m.symbol);
                    try {
                        NobitexApi.DayStats st = api.stats(m.src, m.dst);
                        engine.dayChangePct = st.changePct();
                    } catch (Exception ignored) {
                    }
                    WidgetProvider.push(MainActivity.this);
                } catch (Exception ignored) {
                } finally {
                    priceBusy = false;
                }
            }
        });
    }

    /** fetch candle history (or reuse the engine cache) and redraw the chart + analysis */
    private void fetchChart() {
        if (chartBusy) return;
        chartBusy = true;
        startThread(new Runnable() {
            @Override
            public void run() {
                try {
                    final Prefs.Cfg cfg = prefs.cfg();
                    final Market m = Market.of(cfg.symbol);
                    Candle[] cs = null;
                    if (engine.candlesAt > 0
                            && System.currentTimeMillis() - engine.candlesAt < 90_000L
                            && engine.lastCandles.length > 60) {
                        cs = engine.lastCandles;
                    }
                    if (cs == null) {
                        cs = new NobitexApi(null).udfHistory(m.symbol, cfg.resolution, 300);
                    }
                    if (cs.length < 30) return;
                    final Candle[] f = cs;
                    int n = f.length;
                    Strategy.Ctx ctx = Strategy.Ctx.compute(f);
                    int from = Math.max(0, n - 80);
                    int len = n - from;
                    final Candle[] sub = new Candle[len];
                    final double[] ema = new double[len];
                    final double[] rsiArr = new double[len];
                    final double[] macdArr = new double[len];
                    final double[] sigArr = new double[len];
                    final double[] bbU = new double[len];
                    final double[] bbM = new double[len];
                    final double[] bbL = new double[len];
                    final double[] e50 = new double[len];
                    for (int i = from; i < n; i++) {
                        sub[i - from] = f[i];
                        ema[i - from] = ctx.ema21[i];
                        rsiArr[i - from] = ctx.rsi14[i];
                        macdArr[i - from] = ctx.macdLine[i];
                        sigArr[i - from] = ctx.macdSig[i];
                        bbU[i - from] = ctx.bbUp[i];
                        bbM[i - from] = ctx.bbMid[i];
                        bbL[i - from] = ctx.bbLo[i];
                        e50[i - from] = ctx.sma50[i];
                    }
                    chartView.setOverlays(bbU, bbM, bbL, e50);
                    // map trade timestamps to their candle open time for markers
                    long tfSec = Market.tfSeconds(cfg.resolution);
                    java.util.ArrayList<double[]> ms = Store.markers();
                    java.util.ArrayList<double[]> mks = new java.util.ArrayList<double[]>();
                    long firstT = f[0].t;
                    for (double[] mk : ms) {
                        long candleT = ((long) (mk[0] / tfSec)) * tfSec;
                        if (candleT >= firstT) {
                            mks.add(new double[]{candleT, mk[1], mk[2]});
                        }
                    }
                    final double[][] markers = mks.toArray(new double[0][]);
                    final double lastP = f[n - 1].c;
                    boolean upTrend = ctx.ema9[n - 1] > ctx.ema21[n - 1];
                    int score = new Strategy.ComboScore().score(f, n - 1, ctx);
                    StringBuilder an = new StringBuilder();
                    an.append("RSI: ").append(String.format(Locale.US, "%.1f", ctx.rsi14[n - 1]));
                    if (!Double.isNaN(ctx.adx14[n - 1])) {
                        an.append("   |   قدرت روند (ADX): ").append(String.format(Locale.US, "%.0f", ctx.adx14[n - 1]));
                    }
                    if (!Double.isNaN(ctx.atr14[n - 1]) && lastP > 0) {
                        an.append("   |   نوسان (ATR): ").append(String.format(Locale.US, "%.1f", ctx.atr14[n - 1] / lastP * 100.0)).append("٪");
                    }
                    an.append("\nروند: ").append(upTrend ? "صعودی 📈" : "نزولی 📉")
                            .append("   |   امتیاز ترکیبی: ").append(score).append("/۷");
                    final String analysis = an.toString();
                    final double entry = prefs.posActive() ? prefs.posEntry() : 0;
                    final String label = Fmt.quote(lastP, m.isRls);
                    postUi(new Runnable() {
                        @Override
                        public void run() {
                            chartView.setData(sub, ema, rsiArr, macdArr, sigArr, entry, label, markers);
                            analysisView.setText(analysis);
                        }
                    });
                } catch (Exception ignored) {
                } finally {
                    chartBusy = false;
                }
            }
        });
    }

    // ---------------------------------------------------------------- refresh

    private void refreshUi() {
        Prefs.Cfg cfg = prefs.cfg();
        Market m = Market.of(cfg.symbol);

        refreshChips();
        renderAlerts();
        for (int i = 0; i < CHIP_SYMS.length; i++) {
            if (chipLay[i] == null) continue;
            try {
                android.graphics.drawable.GradientDrawable g =
                        (android.graphics.drawable.GradientDrawable) chipLay[i].getBackground();
                g.setStroke(dp(1), CHIP_SYMS[i].equals(cfg.symbol) ? GOLD : STROKE);
            } catch (Throwable ignored) {
            }
        }

        boolean run = engine.isRunning();
        statusPill.setText(run ? "● در حال اجرا" : (engine.lastError.isEmpty() ? "● متوقف" : "● خطا"));
        statusPill.setTextColor(run ? GREEN : TEXT2);
        android.graphics.drawable.GradientDrawable pillBg =
                new android.graphics.drawable.GradientDrawable();
        pillBg.setColor(run ? 0x3316C784 : 0xFF1B2334);
        pillBg.setCornerRadius(dp(20));
        pillBg.setStroke(dp(1), run ? 0x5516C784 : STROKE);
        statusPill.setBackground(pillBg);
        startBtn.setText(run ? "■  توقف ربات" : "▶  شروع ربات");
        try {
            startBtn.getBackground().setTint(run ? RED : GREEN);
        } catch (Throwable ignored) {
        }

        double p = engine.lastPrice;
        boolean fromCache = false;
        if (p <= 0 && prefs.lastPriceSym().equals(cfg.symbol)) {
            p = prefs.lastPrice();
            fromCache = p > 0;
        }
        long nowMs = System.currentTimeMillis();
        if (lastPriceOkAt > 0 && nowMs - lastPriceOkAt < 35000L) {
            livePill.setText("● قیمت زنده از نوبیتکس");
            livePill.setTextColor(GREEN);
        } else if (lastPriceOkAt > 0) {
            livePill.setText("● اتصال قطع — در حال تلاش مجدد…");
            livePill.setTextColor(RED);
        } else if (fromCache) {
            livePill.setText("آخرین قیمت ذخیره‌شده — در حال اتصال…");
            livePill.setTextColor(TEXT2);
        } else {
            livePill.setText("در حال اتصال به نوبیتکس…");
            livePill.setTextColor(TEXT2);
        }
        if (p > 0) {
            if (lastShownPrice > 0 && p != lastShownPrice) {
                priceView.setTextColor(p > lastShownPrice ? GREEN : RED);
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        priceView.setTextColor(TEXT);
                    }
                }, 700);
            }
            lastShownPrice = p;
            priceView.setText(Fmt.quote(p, m.isRls) + " " + m.quoteUnit());
            double ch = engine.dayChangePct;
            if (ch != 0) {
                changeView.setText("تغییر ۲۴ ساعته: " + Fmt.pct(ch));
                changeView.setTextColor(ch >= 0 ? GREEN : RED);
            }
        }

        boolean inPos = prefs.posActive();
        if (inPos) {
            double entry = prefs.posEntry();
            double amount = prefs.posAmount();
            double pnlPct = entry > 0 ? (p > 0 ? p : entry) / entry * 100.0 - 100.0 : 0;
            StringBuilder sb = new StringBuilder();
            sb.append("📌 در پوزیشن ").append(prefs.posLive() ? "واقعی ⚡" : "شبیه‌سازی").append('\n');
            sb.append("مقدار: ").append(Fmt.amount(amount)).append(' ').append(Market.coinName(m.src)).append('\n');
            int ladders = prefs.posLadders();
            sb.append(ladders > 1 ? "قیمت میانگین ورود: " : "قیمت ورود: ")
                    .append(Fmt.quote(entry, m.isRls)).append('\n');
            if (ladders > 1) sb.append("پله‌های خرید: ").append(ladders).append('\n');
            if (cfg.trailingPct > 0) {
                double peak = prefs.posPeak();
                if (peak > 0) sb.append("اوج پس از ورود: ").append(Fmt.quote(peak, m.isRls)).append('\n');
            }
            if (p > 0) {
                sb.append("قیمت فعلی: ").append(Fmt.quote(p, m.isRls)).append("  (").append(Fmt.pct(pnlPct)).append(")");
            }
            posView.setText(sb.toString());
            posView.setTextColor(pnlPct >= 0 ? GREEN : RED);
        } else {
            posView.setText("در انتظار سیگنال خرید…");
            posView.setTextColor(TEXT);
        }
        sellResetRow.setVisibility(inPos ? View.VISIBLE : View.GONE);
        chartView.updateEntry(inPos ? prefs.posEntry() : 0);
        if (inPos) {
            riskGauge.setData(prefs.posEntry(), p > 0 ? p : prefs.posEntry(),
                    cfg.atrStops ? cfg.slPct : cfg.slPct, cfg.tpPct);
        }
        riskGauge.setVisibility(inPos ? View.VISIBLE : View.GONE);

        double realized = prefs.realizedPnl();
        statsView.setTextColor(realized > 0 ? GREEN : (realized < 0 ? RED : TEXT2));
        statsView.setText("سود تحقق‌یافته: " + Fmt.quote(realized, m.isRls) + " " + m.quoteUnit()
                + "   |   معاملات: " + prefs.tradeCount()
                + "   |   برد: " + prefs.winCount()
                + (engine.lastCheck > 0 ? "\nآخرین بررسی: " + Fmt.time(engine.lastCheck / 1000L) : ""));

        if (shownVersion != Store.version()) {
            shownVersion = Store.version();
            equityView.setData(Store.pnlSeries());
            String lt = Store.logText(25);
            String tt = Store.tradesText(m, 8);
            if (tt.length() > 0) {
                logView.setText("آخرین معاملات:\n" + tt + "\n\n" + (lt.length() > 0 ? lt : "—"));
            } else {
                logView.setText(lt.length() > 0 ? lt : "هنوز رخدادی ثبت نشده است.");
            }
            logView.setTextColor(TEXT2);
        }
    }

    // ---------------------------------------------------------------- utils

    /** switch the active market from a chip tap */
    private void selectSymbol(String sym) {
        prefs.setSymbol(sym);
        for (int i = 0; i < Market.SYMBOLS.length; i++) {
            if (Market.SYMBOLS[i].equals(sym)) {
                symbolSpin.setSelection(i);
                break;
            }
        }
        shownVersion = -1;
        updateAmountHint();
        refreshUi();
    }

    /** refresh live prices on the dashboard chips (throttled to ~1/min) */
    private void refreshChips() {
        long now = System.currentTimeMillis();
        if (now - lastChipsAt < 45_000L) return;
        lastChipsAt = now;
        final Prefs.Cfg cfg = prefs.cfg();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    NobitexApi api = new NobitexApi(cfg.token, cfg.apiSecret);
                    for (int i = 0; i < CHIP_SYMS.length; i++) {
                        final int fi = i;
                        try {
                            Market cm = Market.of(CHIP_SYMS[fi]);
                            NobitexApi.DayStats st = api.stats(cm.src, cm.dst);
                            final String priceTxt = Fmt.quote(st.latest, cm.isRls);
                            final double ch = st.changePct();
                            postUi(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        chipPrice[fi].setText(priceTxt);
                                        chipChg[fi].setText((ch >= 0 ? "▲ " : "▼ ") + Fmt.pct(ch));
                                        chipChg[fi].setTextColor(ch >= 0 ? GREEN : RED);
                                    } catch (Throwable ignored) {
                                    }
                                }
                            });
                        } catch (Exception ignored) {
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }).start();
    }

    /** rebuild the active price-alert rows from prefs */
    private void renderAlerts() {
        if (alertsList == null) return;
        alertsList.removeAllViews();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(prefs.alertsJson());
            for (int i = 0; i < arr.length(); i++) {
                final int idx = i;
                org.json.JSONObject a = arr.getJSONObject(i);
                Market am = Market.of(a.optString("sym", Market.SYMBOLS[0]));
                boolean above = "above".equals(a.optString("dir"));
                LinearLayout r = row();
                TextView t = text("🔔 " + Market.coinName(am.src) + " "
                        + (above ? "بالاتر از " : "پایین‌تر از ")
                        + Fmt.quote(a.optDouble("price", 0), am.isRls), 12f, TEXT, false);
                r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                Button del = button("✕", RED);
                del.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            org.json.JSONArray arr2 = new org.json.JSONArray(prefs.alertsJson());
                            org.json.JSONArray keep = new org.json.JSONArray();
                            for (int j = 0; j < arr2.length(); j++) {
                                if (j != idx) keep.put(arr2.get(j));
                            }
                            prefs.setAlertsJson(keep.toString());
                            renderAlerts();
                        } catch (Exception ignored) {
                        }
                    }
                });
                r.addView(del);
                alertsList.addView(r);
            }
            if (arr.length() == 0) {
                alertsList.addView(text("آلارمی ثبت نشده — ارز، شرط و قیمت را انتخاب کنید", 11f, TEXT2, false));
            }
        } catch (Exception ignored) {
        }
    }

    /** ---- replay simulator: animate the strategy trading over history ---- */
    private void startReplay() {
        final Prefs.Cfg cfg = prefs.cfg();
        final Market m = Market.of(cfg.symbol);
        playBtn.setText("⏳ در حال دریافت داده…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    NobitexApi api = new NobitexApi(cfg.token, cfg.apiSecret);
                    final Candle[] cs = api.udfHistory(m.symbol, cfg.resolution, 300);
                    if (cs.length < Strategy.WARMUP + 10) {
                        replayFail("داده کافی برای پخش وجود ندارد");
                        return;
                    }
                    Strategy st = Strategy.byId(cfg.strategyId);
                    Backtester.Result r = Backtester.run(st, cs, cfg.slPct, cfg.tpPct,
                            cfg.trailingPct, 0, 0, cfg.atrStops, cfg.tp1Enabled,
                            Backtester.DEFAULT_FEE);
                    if (!r.ok || r.events.isEmpty()) {
                        replayFail("در این بازه سیگنالی برای پخش ثبت نشد");
                        return;
                    }
                    final Strategy.Ctx ctx = Strategy.Ctx.compute(cs);
                    final double[][] evts = r.events.toArray(new double[0][]);
                    final int n = cs.length;
                    postUi(new Runnable() {
                        @Override
                        public void run() {
                            double[] emp = new double[0];
                            chartView.setData(cs, ctx.ema21, ctx.rsi14, ctx.macdLine, ctx.macdSig,
                                    0, Fmt.quote(cs[n - 1].c, m.isRls), new double[0][]);
                            chartView.setOverlays(ctx.bbUp, ctx.bbMid, ctx.bbLo, ctx.sma50);
                            chartView.beginReplay(evts);
                            replaying = true;
                            replayEvents = evts;
                            replayLen = n;
                            replayPos = Strategy.WARMUP;
                            playBtn.setText("■ توقف پخش");
                            try {
                                playBtn.getBackground().setTint(RED);
                            } catch (Throwable ignored) {
                            }
                            scheduleReplayTick();
                        }
                    });
                } catch (Exception e) {
                    replayFail("خطا در دریافت داده: " + e.getMessage());
                }
            }
        }).start();
    }

    private void replayFail(final String msg) {
        postUi(new Runnable() {
            @Override
            public void run() {
                toast(msg);
                resetPlayBtn();
            }
        });
    }

    private void resetPlayBtn() {
        playBtn.setText("🎬 پخش شبیه‌سازی روی نمودار");
        try {
            playBtn.getBackground().setTint(0xFF2A3752);
        } catch (Throwable ignored) {
        }
    }

    private void scheduleReplayTick() {
        replayTick = new Runnable() {
            @Override
            public void run() {
                if (!replaying) return;
                replayPos += 2;
                if (replayPos > replayLen) replayPos = replayLen;
                int cnt = 0;
                double eq = 0;
                for (double[] ev : replayEvents) {
                    if (ev[0] < replayPos) {
                        cnt++;
                        eq = ev[3];
                    }
                }
                chartView.setReplayUpto(replayPos, "");
                analysisView.setText("🎬 پخش شبیه‌سازی — کندل " + replayPos + "/" + replayLen
                        + " — معاملات: " + cnt
                        + (cnt > 0 ? " — سود تجمعی: " + Fmt.pct(eq) : ""));
                if (replayPos >= replayLen) {
                    stopReplay();
                    return;
                }
                ui.postDelayed(this, 40);
            }
        };
        ui.postDelayed(replayTick, 400);
    }

    private void stopReplay() {
        replaying = false;
        if (replayTick != null) ui.removeCallbacks(replayTick);
        chartView.endReplay();
        resetPlayBtn();
        fetchChart();
    }

    /** persist the API key + secret immediately (never lose credentials) */
    private void saveKeys(boolean silent) {
        try {
            String k = tokenEdit.getText().toString().trim();
            String sc = secretEdit.getText().toString().trim();
            Prefs.Cfg c = prefs.cfg();
            if (!k.equals(c.token) || !sc.equals(c.apiSecret)) {
                prefs.setToken(k);
                prefs.setApiSecret(sc);
                if (!silent) toast("🔑 کلید API ذخیره شد");
            }
        } catch (Throwable ignored) {
        }
    }

    /** run the API connection test (both classic and new API-key auth) */
    private void runConnTest() {
        final String key = tokenEdit.getText().toString().trim();
        final String secret = secretEdit.getText().toString().trim();
        if (key.isEmpty()) {
            toast("کلید یا توکن را وارد کنید");
            return;
        }
        prefs.setToken(key);
        prefs.setApiSecret(secret);
        connView.setText("⏳ در حال تست اتصال…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final NobitexApi.ConnResult r = new NobitexApi(key, secret).testConnection();
                postUi(new Runnable() {
                    @Override
                    public void run() {
                        connView.setText(r.detail);
                        connView.setTextColor(r.ok ? GREEN : RED);
                        toast(r.ok ? "اتصال برقرار شد ✅" : "اتصال ناموفق");
                    }
                });
            }
        }).start();
    }

    private void postUi(Runnable r) {
        runOnUiThread(r);
    }

    private void startThread(Runnable r) {
        new Thread(r).start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private double parse(String s, double def) {
        try {
            return Double.parseDouble(s.replace(",", "").trim());
        } catch (Exception e) {
            return def;
        }
    }

    private String fmtNum(double v) {
        if (v == Math.floor(v) && Math.abs(v) < 1e15) {
            return String.format(Locale.US, "%.0f", v);
        }
        return String.format(Locale.US, "%s", v);
    }

    private String tfName(String res) {
        if ("15".equals(res)) return "۱۵ دقیقه‌ای";
        if ("240".equals(res)) return "۴ ساعته";
        if ("D".equals(res)) return "روزانه";
        return "۱ ساعته";
    }
}
