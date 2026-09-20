package ir.nobitrader.bot;

import android.content.Context;

import org.json.JSONObject;

/**
 * The trading engine. Runs a loop inside the foreground service:
 * fetch candles -> evaluate strategy -> risk checks (SL/TP) -> trade (real or paper).
 */
public class BotEngine {

    private static BotEngine instance;

    public static synchronized BotEngine get(Context ctx) {
        if (instance == null) instance = new BotEngine(ctx.getApplicationContext());
        return instance;
    }

    public static final double FEE = 0.0025; // ~0.25% per side (approximation for paper mode)

    private final Context ctx;
    private final Prefs prefs;

    private volatile boolean running = false;
    private Thread worker;

    // live status for the UI
    public volatile double lastPrice = 0;
    public volatile double dayChangePct = 0;
    public volatile long lastCheck = 0;
    public volatile String lastError = "";
    public volatile String strategyName = "";

    private BotEngine(Context ctx) {
        this.ctx = ctx;
        this.prefs = new Prefs(ctx);
        Store.init(prefs);
    }

    public boolean isRunning() {
        return running;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        worker = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "bot-engine");
        worker.start();
        Store.log("🤖 ربات روشن شد (" + (prefs.cfg().live ? "معامله واقعی ⚡" : "حالت شبیه‌سازی") + ")");
    }

    public synchronized void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        Store.log("⛔ ربات متوقف شد");
    }

    private void loop() {
        while (running) {
            int waitSec = 300;
            try {
                Prefs.Cfg cfg = prefs.cfg();
                waitSec = cfg.intervalSec;
                cycle(cfg);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                lastError = String.valueOf(t.getMessage() != null ? t.getMessage() : t);
                Store.log("⚠️ خطا در چرخه بررسی: " + lastError);
            }
            try {
                Thread.sleep(Math.max(30, waitSec) * 1000L);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------

    private void cycle(Prefs.Cfg cfg) throws Exception {
        lastError = "";
        Market m = Market.of(cfg.symbol);
        NobitexApi api = new NobitexApi(cfg.token);
        Strategy strat = Strategy.byId(cfg.strategyId);
        strategyName = strat.name();

        Candle[] cs = api.udfHistory(m.symbol, cfg.resolution, 300);
        if (cs.length < Strategy.WARMUP + 5) {
            Store.log("⚠️ داده کافی برای تحلیل موجود نیست (" + cs.length + " کندل)");
            return;
        }

        double price;
        try {
            price = api.lastPrice(m.symbol);
        } catch (Exception e) {
            price = cs[cs.length - 1].c;
        }
        lastPrice = price;
        try {
            NobitexApi.DayStats st = api.stats(m.src, m.dst);
            dayChangePct = st.changePct();
        } catch (Exception ignored) {
        }
        lastCheck = System.currentTimeMillis();

        Strategy.Ctx c = Strategy.Ctx.compute(cs);
        int i = cs.length - 2; // last CLOSED candle
        if (i < Strategy.WARMUP) return;

        if (prefs.posActive()) {
            managePosition(cfg, m, api, strat, cs, c, i, price);
        } else {
            long cooldown = Math.max(600_000L, cfg.intervalSec * 2000L);
            long since = System.currentTimeMillis() - prefs.lastTradeTime();
            if (since < cooldown) return; // just closed a trade, wait
            int sig = strat.signal(cs, i, c);
            if (sig == Strategy.BUY) {
                buy(cfg, m, api, price, strat.reason);
            }
        }
    }

    private void managePosition(Prefs.Cfg cfg, Market m, NobitexApi api, Strategy strat,
                                Candle[] cs, Strategy.Ctx c, int i, double price) throws Exception {
        double entry = prefs.posEntry();
        double amount = prefs.posAmount();
        double pnlPct = entry > 0 ? (price / entry - 1.0) * 100.0 : 0;
        boolean stop = pnlPct <= -cfg.slPct;
        boolean take = pnlPct >= cfg.tpPct;
        int sig = strat.signal(cs, i, c);

        if (stop || take || sig == Strategy.SELL) {
            String reason = stop ? "فعال شدن حد ضرر (" + Fmt.pct(pnlPct) + ")"
                    : take ? "رسیدن به حد سود (" + Fmt.pct(pnlPct) + ")"
                    : "سیگنال فروش: " + strat.reason;
            sell(cfg, m, api, price, reason);
        }
    }

    // ------------------------------------------------------------------

    private void buy(Prefs.Cfg cfg, Market m, NobitexApi api, double price, String reason) throws Exception {
        double quoteAmount = m.isRls ? cfg.tradeAmount * 10.0 : cfg.tradeAmount; // toman -> rials
        double minQuote = m.isRls ? NobitexApi.MIN_RLS : NobitexApi.MIN_USDT;
        if (quoteAmount < minQuote * 1.02) {
            Store.log("❌ مبلغ هر معامله کمتر از حداقل مجاز است ("
                    + Fmt.quote(minQuote, m.isRls) + " " + m.quoteUnit() + ")");
            return;
        }
        double amount = Fmt.amountFloor(quoteAmount / price);
        if (amount <= 0) {
            Store.log("❌ مبلغ خرید برای این قیمت بسیار کم است");
            return;
        }

        if (cfg.live) {
            double balance = api.walletBalance(m.dst);
            if (balance >= 0 && balance < quoteAmount * 1.01) {
                Store.log("❌ موجودی " + m.quoteUnit() + " کافی نیست (موجودی: "
                        + Fmt.quote(balance, m.isRls) + ")");
                return;
            }
            JSONObject order = api.addMarketOrder("buy", m.src, m.dst, amount);
            long id = order != null ? order.optLong("id", 0) : 0;
            Store.log("⚡ سفارش خرید واقعی ثبت شد (شناسه " + id + ")");
            double matched = waitAndMatch(api, id, amount);
            amount = Math.min(amount, matched > 0 ? matched : amount);
        } else {
            amount = amount * (1.0 - FEE); // paper: pay fee in base
        }

        prefs.setPos(true, amount, price, System.currentTimeMillis(), cfg.live);
        JSONObject t = new JSONObject();
        t.put("time", System.currentTimeMillis());
        t.put("side", "buy");
        t.put("price", price);
        t.put("amount", amount);
        t.put("live", cfg.live);
        Store.trade(t);
        Store.log("🟢 خرید " + Fmt.amount(amount) + " " + Market.coinName(m.src)
                + " در قیمت " + Fmt.quote(price, m.isRls) + " — " + reason);
        notifyStatus();
    }

    private void sell(Prefs.Cfg cfg, Market m, NobitexApi api, double price, String reason) throws Exception {
        double entry = prefs.posEntry();
        double amount = prefs.posAmount();
        boolean wasLive = prefs.posLive();

        if (wasLive) {
            JSONObject order = api.addMarketOrder("sell", m.src, m.dst, amount);
            long id = order != null ? order.optLong("id", 0) : 0;
            Store.log("⚡ سفارش فروش واقعی ثبت شد (شناسه " + id + ")");
            double matched = waitAndMatch(api, id, amount);
            amount = matched > 0 ? Math.min(amount, matched) : amount;
        }

        double invested = entry * amount;
        double pnl = (price - entry) * amount - price * amount * FEE;
        double pnlPct = entry > 0 ? (price / entry - 1.0) * 100.0 : 0;
        double realized = prefs.realizedPnl() + pnl;
        prefs.setRealizedPnl(realized);
        prefs.setTradeStats(prefs.tradeCount() + 1, prefs.winCount() + (pnl > 0 ? 1 : 0));
        prefs.setPos(false, 0, 0, 0, false);
        prefs.setLastTradeTime(System.currentTimeMillis());

        JSONObject t = new JSONObject();
        t.put("time", System.currentTimeMillis());
        t.put("side", "sell");
        t.put("price", price);
        t.put("amount", amount);
        t.put("pnl", pnl);
        t.put("pnlPct", pnlPct);
        t.put("live", wasLive);
        Store.trade(t);
        Store.log("🔴 فروش " + Fmt.amount(amount) + " " + Market.coinName(m.src)
                + " در قیمت " + Fmt.quote(price, m.isRls)
                + " — نتیجه: " + Fmt.quote(pnl, m.isRls) + " " + m.quoteUnit()
                + " (" + Fmt.pct(pnlPct) + ") — " + reason);
        notifyStatus();
    }

    /** manual "sell now" from the UI (runs on a worker thread) */
    public void forceSell() {
        Prefs.Cfg cfg = prefs.cfg();
        Market m = Market.of(cfg.symbol);
        NobitexApi api = new NobitexApi(cfg.token);
        try {
            if (!prefs.posActive()) return;
            double price = lastPrice > 0 ? lastPrice : api.lastPrice(m.symbol);
            sell(cfg, m, api, price, "فروش دستی");
        } catch (Exception e) {
            Store.log("❌ فروش دستی ناموفق: " + (e.getMessage() != null ? e.getMessage() : e));
        }
    }

    /** poll the order status once after a short delay to learn the matched amount */
    private double waitAndMatch(NobitexApi api, long orderId, double requested) {
        if (orderId <= 0) return requested;
        try {
            Thread.sleep(6000);
            JSONObject o = api.orderStatus(orderId);
            if (o == null) return requested;
            String status = o.optString("status", "");
            double matched = Prefs.parse(o.optString("matchedAmount", "0"));
            if (matched <= 0 && ("Filled".equalsIgnoreCase(status) || "Done".equalsIgnoreCase(status))) {
                matched = requested;
            }
            if (matched > 0) {
                Store.log("✅ مقدار اجراشده: " + Fmt.amount(matched) + " (وضعیت: " + status + ")");
            } else if (!"Active".equalsIgnoreCase(status)) {
                Store.log("⚠️ وضعیت سفارش: " + status + " — مقدار اجراشده صفر است");
            }
            return matched;
        } catch (Exception e) {
            return requested;
        }
    }

    // ------------------------------------------------------------------

    /** update the ongoing notification (safe to call from any thread) */
    private void notifyStatus() {
        try {
            BotService.postStatus(ctx);
        } catch (Throwable ignored) {
        }
    }
}
