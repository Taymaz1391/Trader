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
    private TextView statusPill, priceView, changeView, symbolTitle;
    private TextView walletView, posView, statsView, logView, backtestView;
    private TextView stratDesc, amountHint, liveWarn, analysisView;
    private EditText tokenEdit, amountEdit, slEdit, tpEdit, trailEdit, tgTokenEdit, tgChatEdit;
    private Spinner symbolSpin, tfSpin, intervalSpin, stratSpin;
    private Switch liveSwitch, trailSwitch;
    private Button startBtn, connectBtn, backtestBtn, sellBtn, resetBtn, csvBtn, tgTestBtn;
    private LinearLayout sellResetRow;
    private ChartView chartView;

    private boolean resumed = false;
    private int shownVersion = -1;
    private int tick = 0;
    private volatile boolean priceBusy = false;
    private volatile boolean chartBusy = false;
    private volatile boolean btBusy = false;

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
        ui.postDelayed(poll, 2500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(poll);
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
        b.setBackground(gd);
        return b;
    }

    private View vline() {
        View v = new View(this);
        v.setBackgroundColor(0x33FFFFFF);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(14), dp(16), dp(14), dp(24));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------- header ----------
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
        root.addView(head);

        // ---------- market card ----------
        LinearLayout mc = card();
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
        pcol.addView(priceView);
        pcol.addView(changeView);
        prow.addView(pcol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mc.addView(prow);
        root.addView(mc);

        // ---------- chart card ----------
        LinearLayout chc = card();
        chc.addView(text("نمودار قیمت و تحلیل لحظه‌ای", 15f, GOLD, true));
        chartView = new ChartView(this);
        LinearLayout.LayoutParams cvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cvLp.topMargin = dp(8);
        chartView.setLayoutParams(cvLp);
        chc.addView(chartView);
        analysisView = text("خط طلایی: EMA21 — خط‌چین: قیمت ورود شما", 12f, TEXT2, false);
        chc.addView(margin(analysisView, 6));
        root.addView(chc);

        // ---------- control card ----------
        LinearLayout cc = card();
        startBtn = button("▶  شروع ربات", GREEN);
        startBtn.setTextSize(17f);
        startBtn.setPadding(dp(12), dp(14), dp(12), dp(14));
        cc.addView(startBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(cc);

        // ---------- position card ----------
        LinearLayout pc = card();
        pc.addView(text("وضعیت و پوزیشن", 15f, GOLD, true));
        posView = text("در انتظار سیگنال خرید…", 14f, TEXT, false);
        posView.setLineSpacing(dp(3), 1f);
        pc.addView(margin(posView, 8));
        statsView = text("", 13f, TEXT2, false);
        pc.addView(margin(statsView, 6));
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
        root.addView(pc);

        // ---------- settings card ----------
        LinearLayout sc = card();
        sc.addView(text("تنظیمات ربات", 15f, GOLD, true));

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
                new String[]{"۱ دقیقه", "۲ دقیقه", "۵ دقیقه", "۱۰ دقیقه", "۱۵ دقیقه"});
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
        root.addView(sc);

        // ---------- account card ----------
        LinearLayout ac = card();
        ac.addView(text("اتصال به حساب نوبیتکس", 15f, GOLD, true));
        ac.addView(margin(label("توکن API"), 10));
        tokenEdit = new EditText(this);
        tokenEdit.setHint("توکن را از پنل نوبیتکس (بخش API) بسازید");
        tokenEdit.setTextSize(14f);
        tokenEdit.setTextColor(TEXT);
        tokenEdit.setHintTextColor(TEXT2);
        tokenEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenEdit.setTransformationMethod(PasswordTransformationMethod.getInstance());
        tokenEdit.setTypeface(Typeface.DEFAULT);
        ac.addView(tokenEdit);
        connectBtn = button("بررسی اتصال و موجودی", 0xFF2A3752);
        connectBtn.setTextColor(TEXT);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cbLp.topMargin = dp(10);
        connectBtn.setLayoutParams(cbLp);
        ac.addView(connectBtn);
        walletView = text("برای معامله واقعی، توکن را وارد و بررسی کنید. توکن فقط روی همین گوشی ذخیره می‌شود.", 12f, TEXT2, false);
        walletView.setLineSpacing(dp(2), 1f);
        ac.addView(margin(walletView, 8));
        root.addView(ac);

        // ---------- backtest card ----------
        LinearLayout bc = card();
        bc.addView(text("بک‌تست استراتژی‌ها", 15f, GOLD, true));
        backtestBtn = button("اجرای بک‌تست روی داده تاریخی", 0xFF2A3752);
        backtestBtn.setTextColor(TEXT);
        LinearLayout.LayoutParams bbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bbLp.topMargin = dp(10);
        backtestBtn.setLayoutParams(bbLp);
        bc.addView(backtestBtn);
        backtestView = text("هر چهار استراتژی روی ۵۰۰ کندل اخیر بازار انتخابی شبیه‌سازی می‌شوند و نتیجه مقایسه داده می‌شود.", 12f, TEXT2, false);
        backtestView.setLineSpacing(dp(3), 1f);
        bc.addView(margin(backtestView, 8));
        root.addView(bc);

        // ---------- log card ----------
        LinearLayout lc = card();
        lc.addView(text("گزارش رویدادها و معاملات", 15f, GOLD, true));
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
        root.addView(lc);

        // ---------- footer ----------
        TextView foot = text("NobiTrader v1.2 — معامله در بازار رمزارز با ریسک همراه است؛ مسئولیت معاملات بر عهده کاربر است. همیشه اول با حالت شبیه‌سازی تست کنید.", 11f, TEXT2, false);
        foot.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fLp.topMargin = dp(14);
        foot.setLayoutParams(fLp);
        root.addView(foot);

        hookListeners();
        return scroll;
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
                prefs.setIntervalSec(new int[]{60, 120, 300, 600, 900}[position]);
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
        prefs.setTradeAmount(amount);
        prefs.setSlPct(sl);
        prefs.setTpPct(tp);
        prefs.setTrailingPct(trail);
        prefs.setLive(live);
        return true;
    }

    private void loadIntoUi() {
        Prefs.Cfg c = prefs.cfg();
        tokenEdit.setText(c.token);
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
        String[] tfs = {"15", "60", "240", "D"};
        for (int i = 0; i < tfs.length; i++) {
            if (tfs[i].equals(c.resolution)) tfSpin.setSelection(i, false);
        }
        int[] ivs = {60, 120, 300, 600, 900};
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
        walletView.setText("در حال بررسی…");
        startThread(new Runnable() {
            @Override
            public void run() {
                try {
                    NobitexApi api = new NobitexApi(token);
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
                    NobitexApi api = new NobitexApi(cfg.token);
                    Candle[] cs = api.udfHistory(m.symbol, cfg.resolution, 500);
                    StringBuilder sb = new StringBuilder();
                    sb.append("📊 نتیجه بک‌تست روی ").append(cs.length).append(" کندل ").append(tfName(cfg.resolution)).append(" (")
                            .append(m.title).append(")\n");
                    sb.append("حد ضرر ").append(Fmt.pct(-cfg.slPct)).append(" | حد سود ").append(Fmt.pct(cfg.tpPct));
                    if (cfg.trailingPct > 0) {
                        sb.append(" | حد ضرر متحرک ").append(fmtNum(cfg.trailingPct)).append("٪");
                    }
                    sb.append(" | کارمزد تقریبی ۰.۲۵٪\n\n");
                    Backtester.Result best = null;
                    for (int s = 0; s < Strategy.ALL.length; s++) {
                        Backtester.Result r = Backtester.run(Strategy.ALL[s], cs, cfg.slPct, cfg.tpPct,
                                cfg.trailingPct, Backtester.DEFAULT_FEE);
                        if (!r.ok) continue;
                        if (best == null || r.netPct > best.netPct) best = r;
                        sb.append("• ").append(Strategy.ALL[s].name()).append('\n');
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
                    int from = Math.max(0, n - 120);
                    final double[] closes = new double[n - from];
                    final double[] ema = new double[n - from];
                    for (int i = from; i < n; i++) {
                        closes[i - from] = f[i].c;
                        ema[i - from] = ctx.ema21[i];
                    }
                    final double lastP = f[n - 1].c;
                    boolean upTrend = ctx.ema9[n - 1] > ctx.ema21[n - 1];
                    int score = new Strategy.ComboScore().score(n - 1, ctx);
                    final String analysis = "RSI: " + String.format(Locale.US, "%.1f", ctx.rsi14[n - 1])
                            + "   |   روند: " + (upTrend ? "صعودی 📈" : "نزولی 📉")
                            + "   |   امتیاز ترکیبی: " + score + "/۷";
                    final double entry = prefs.posActive() ? prefs.posEntry() : 0;
                    final String label = Fmt.quote(lastP, m.isRls);
                    postUi(new Runnable() {
                        @Override
                        public void run() {
                            chartView.setData(closes, ema, lastP, entry, label);
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

        boolean run = engine.isRunning();
        statusPill.setText(run ? "● در حال اجرا" : (engine.lastError.isEmpty() ? "● متوقف" : "● خطا"));
        statusPill.setTextColor(run ? GREEN : TEXT2);
        startBtn.setText(run ? "■  توقف ربات" : "▶  شروع ربات");
        try {
            startBtn.getBackground().setTint(run ? RED : GREEN);
        } catch (Throwable ignored) {
        }

        double p = engine.lastPrice;
        if (p > 0) {
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
            sb.append("قیمت ورود: ").append(Fmt.quote(entry, m.isRls)).append('\n');
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

        statsView.setText("سود تحقق‌یافته: " + Fmt.quote(prefs.realizedPnl(), m.isRls) + " " + m.quoteUnit()
                + "   |   معاملات: " + prefs.tradeCount()
                + "   |   برد: " + prefs.winCount()
                + (engine.lastCheck > 0 ? "\nآخرین بررسی: " + Fmt.time(engine.lastCheck / 1000L) : ""));

        if (shownVersion != Store.version()) {
            shownVersion = Store.version();
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
