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
    public volatile Candle[] lastCandles = new Candle[0];
    public volatile long candlesAt = 0;

    private long lastSpreadWarn = 0;

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
        prefs.setBotWasRunning(true);
        worker = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "bot-engine");
        worker.start();
        Store.log("🤖 ربات روشن شد (" + (prefs.cfg().live ? "معامله واقعی ⚡" : "حالت شبیه‌سازی") + ")");
        Prefs.Cfg c = prefs.cfg();
        sendTg(c, "🤖 ربات تریدر روشن شد (" + (c.live ? "معامله واقعی ⚡" : "شبیه‌سازی") + ")\n"
                + Market.of(c.symbol).title + " | " + Strategy.byId(c.strategyId).name());
        WidgetProvider.push(ctx);
    }

    public synchronized void stop() {
        running = false;
        prefs.setBotWasRunning(false);
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        Store.log("⛔ ربات متوقف شد");
        sendTg(prefs.cfg(), "⛔ ربات تریدر متوقف شد");
        WidgetProvider.push(ctx);
    }

    /** async telegram message; no-op when not configured */
    private void sendTg(final Prefs.Cfg cfg, final String msg) {
        if (cfg.tgToken.isEmpty() || cfg.tgChat.isEmpty()) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                Telegram.send(cfg.tgToken, cfg.tgChat, msg);
            }
        }).start();
    }

    private void loop() {
        int errStreak = 0;
        while (running) {
            int waitSec = 300;
            try {
                Prefs.Cfg cfg = prefs.cfg();
                waitSec = cfg.intervalSec;
                cycle(cfg);
                errStreak = 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                errStreak++;
                lastError = String.valueOf(t.getMessage() != null ? t.getMessage() : t);
                Store.log("⚠️ خطا در چرخه بررسی (" + errStreak + "): " + lastError);
                if (errStreak >= 5) {
                    Store.log("⛔ توقف خودکار ربات پس از ۵ خطای متوالی");
                    sendTg(prefs.cfg(), "⛔ ربات پس از ۵ خطای متوالی متوقف شد: " + lastError);
                    BotService.notifyTrade(ctx, "⛔ توقف خودکار ربات", "۵ خطای متوالی: " + lastError);
                    final Context c = ctx;
                    new Thread(new Runnable() {
                        public void run() {
                            try {
                                Thread.sleep(800);
                                BotService.stop(c);
                            } catch (Exception ignored) {
                            }
                        }
                    }).start();
                    return;
                }
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
        lastCandles = cs;
        candlesAt = System.currentTimeMillis();

        double price, buyPrice, sellPrice;
        boolean spreadOk = true;
        try {
            NobitexApi.Book bk = api.book(m.symbol);
            price = bk.last > 0 ? bk.last : cs[cs.length - 1].c;
            buyPrice = bk.ask > 0 ? bk.ask : price;
            sellPrice = bk.bid > 0 ? bk.bid : price;
            spreadOk = bk.spreadPct() <= 1.5;
        } catch (Exception e) {
            price = cs[cs.length - 1].c;
            buyPrice = price;
            sellPrice = price;
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

        if (!spreadOk) {
            long now = System.currentTimeMillis();
            if (now - lastSpreadWarn > 1_800_000L) {
                lastSpreadWarn = now;
                Store.log("⚠️ اسپرد بازار زیاد است؛ تا نرمال شدن، معامله انجام نمی‌شود");
            }
        }

        if (prefs.posActive()) {
            managePosition(cfg, m, api, strat, cs, c, i, price, buyPrice, sellPrice, spreadOk);
        } else {
            long cooldown = Math.max(600_000L, cfg.intervalSec * 2000L);
            int consec = prefs.consecLosses();
            if (consec >= 2) cooldown *= 2; // cool down longer after a losing streak
            long since = System.currentTimeMillis() - prefs.lastTradeTime();
            if (since < cooldown) return; // just closed a trade, wait
            if (cfg.dca) {
                if (spreadOk) {
                    buy(cfg, m, api, buyPrice, effSlPct(cfg, c, i, buyPrice),
                            "خرید پله‌ای — پله ۱", cs[i].t);
                }
            } else {
                int sig = strat.signal(cs, i, c);
                if (sig == Strategy.BUY && spreadOk) {
                    buy(cfg, m, api, buyPrice, effSlPct(cfg, c, i, buyPrice),
                            strat.reason, cs[i].t);
                }
            }
        }
    }

    /** effective stop-loss percent at the moment of a new entry */
    private double effSlPct(Prefs.Cfg cfg, Strategy.Ctx c, int i, double refPrice) {
        if (cfg.atrStops && !Double.isNaN(c.atr14[i])) {
            return Backtester.atrStopsPct(c.atr14[i], refPrice, cfg.slPct, cfg.tpPct)[0];
        }
        return cfg.slPct;
    }

    private void managePosition(Prefs.Cfg cfg, Market m, NobitexApi api, Strategy strat,
                                Candle[] cs, Strategy.Ctx c, int i, double markPrice,
                                double buyPrice, double sellPrice, boolean spreadOk) throws Exception {
        double entry = prefs.posEntry();
        double amount = prefs.posAmount();
        double pnlPct = entry > 0 ? (sellPrice / entry - 1.0) * 100.0 : 0;

        // effective stops: ATR-adaptive when enabled, otherwise the fixed percents
        double slPct = cfg.slPct;
        double tpPct = cfg.tpPct;
        if (cfg.atrStops && !Double.isNaN(c.atr14[i])) {
            double[] eff = Backtester.atrStopsPct(c.atr14[i], entry, cfg.slPct, cfg.tpPct);
            slPct = eff[0];
            tpPct = eff[1];
        }

        // trailing stop: track the peak price since entry
        double peak = prefs.posPeak();
        if (cfg.trailingPct > 0) {
            if (sellPrice > peak) {
                peak = sellPrice;
                prefs.setPosPeak(peak);
            }
        }
        boolean trailStop = cfg.trailingPct > 0 && peak > 0
                && sellPrice <= peak * (1.0 - cfg.trailingPct / 100.0);

        // partial take-profit: bank half the position at half the TP distance
        if (cfg.tp1Enabled && !prefs.posTp1Taken()) {
            double tp1Pct = tpPct / 2.0;
            if (pnlPct >= tp1Pct) {
                sellFraction(cfg, m, api, sellPrice, 0.5,
                        "برداشت سود پله‌ای (TP1 در " + Fmt.pct(tp1Pct) + ")");
                prefs.setPosTp1Taken(true);
                return;
            }
        }

        // after TP1 the remainder is protected at breakeven
        boolean beStop = prefs.posTp1Taken() && pnlPct <= -0.25;

        boolean stop = pnlPct <= -slPct || trailStop || beStop;
        boolean take = pnlPct >= tpPct;
        int sig = strat.signal(cs, i, c);

        if (stop || take || sig == Strategy.SELL) {
            String reason;
            if (beStop && !trailStop) {
                reason = "توقف بی‌ضرر (حفظ سرمایه پس از برداشت سود)";
            } else if (trailStop && pnlPct > -slPct) {
                reason = "حد ضرر متحرک (افت " + Fmt.pct((sellPrice / peak - 1.0) * 100.0) + " از اوج)";
            } else if (stop) {
                reason = "فعال شدن حد ضرر " + (cfg.atrStops ? "تطبیقی ATR " : "")
                        + "(" + Fmt.pct(pnlPct) + "، حد: " + Fmt.pct(-slPct) + ")";
            } else if (take) {
                reason = "رسیدن به حد سود (" + Fmt.pct(pnlPct) + ")";
            } else {
                reason = "سیگنال فروش: " + strat.reason;
            }
            sell(cfg, m, api, sellPrice, reason);
            return;
        }

        // DCA: add another ladder on schedule
        if (cfg.dca && spreadOk) {
            boolean maxOk = cfg.dcaMaxLadders <= 0 || prefs.posLadders() < cfg.dcaMaxLadders;
            long tfSec = Market.tfSeconds(cfg.resolution);
            long lastT = prefs.posLastLadder();
            if (maxOk && lastT > 0 && cs[i].t - lastT >= cfg.dcaIntervalCandles * tfSec) {
                ladderBuy(cfg, m, api, buyPrice, slPct, cs[i].t);
            }
        }
    }

    // ------------------------------------------------------------------

    /** shared market buy; returns the filled base amount, or 0 when nothing was bought */
    private double executeBuy(Prefs.Cfg cfg, Market m, NobitexApi api, double price,
                              double slEffPct) throws Exception {
        double minQuote = m.isRls ? NobitexApi.MIN_RLS : NobitexApi.MIN_USDT;
        double baseSpend = m.isRls ? cfg.tradeAmount * 10.0 : cfg.tradeAmount; // toman -> rials
        double quoteAmount = baseSpend;
        if (cfg.riskSizing) {
            double equity = -1;
            if (cfg.live) {
                try {
                    equity = api.walletBalance(m.dst);
                } catch (Exception ignored) {
                }
            } else {
                equity = baseSpend + prefs.realizedPnl();
            }
            double sized = Backtester.sizeFor(equity, cfg.riskPct, slEffPct, baseSpend, minQuote * 1.05);
            if (sized != baseSpend) {
                Store.log("🎯 حجم پویا بر اساس ریسک: " + Fmt.quote(sized, m.isRls)
                        + " " + m.quoteUnit() + " (ریسک " + Fmt.pct(cfg.riskPct)
                        + " با حد ضرر " + String.format(java.util.Locale.US, "%.1f", slEffPct) + "٪)");
                quoteAmount = sized;
            }
        }
        if (quoteAmount < minQuote * 1.02) {
            Store.log("❌ مبلغ هر معامله کمتر از حداقل مجاز است ("
                    + Fmt.quote(minQuote, m.isRls) + " " + m.quoteUnit() + ")");
            return 0;
        }
        double amount = Fmt.amountFloor(quoteAmount / price);
        if (amount <= 0) {
            Store.log("❌ مبلغ خرید برای این قیمت بسیار کم است");
            return 0;
        }
        if (cfg.live) {
            double balance = api.walletBalance(m.dst);
            if (balance >= 0 && balance < quoteAmount * 1.01) {
                Store.log("❌ موجودی " + m.quoteUnit() + " کافی نیست (موجودی: "
                        + Fmt.quote(balance, m.isRls) + ")");
                return 0;
            }
            JSONObject order = api.addMarketOrder("buy", m.src, m.dst, amount);
            long id = order != null ? order.optLong("id", 0) : 0;
            Store.log("⚡ سفارش خرید واقعی ثبت شد (شناسه " + id + ")");
            double matched = waitAndMatch(api, id, amount);
            return matched > 0 ? Math.min(amount, matched) : 0;
        }
        return amount * (1.0 - FEE); // paper: pay fee in base
    }

    private void buy(Prefs.Cfg cfg, Market m, NobitexApi api, double price, double slEffPct,
                     String reason, long candleT) throws Exception {
        double amount = executeBuy(cfg, m, api, price, slEffPct);
        if (amount <= 0) return;

        prefs.setPos(true, amount, price, System.currentTimeMillis(), cfg.live);
        prefs.setPosLadders(1);
        prefs.setPosLastLadder(candleT);
        JSONObject t = new JSONObject();
        t.put("time", System.currentTimeMillis());
        t.put("side", "buy");
        t.put("price", price);
        t.put("amount", amount);
        t.put("live", cfg.live);
        Store.trade(t);
        Store.log("🟢 خرید " + Fmt.amount(amount) + " " + Market.coinName(m.src)
                + " در قیمت " + Fmt.quote(price, m.isRls) + " — " + reason);
        if (cfg.live) {
            BotService.notifyTrade(ctx, "🟢 خرید انجام شد",
                    Fmt.amount(amount) + " " + Market.coinName(m.src)
                            + " × " + Fmt.quote(price, m.isRls) + " " + m.quoteUnit());
        }
        sendTg(cfg, "🟢 خرید " + Fmt.amount(amount) + " " + Market.coinName(m.src)
                + " در " + Fmt.quote(price, m.isRls) + " " + m.quoteUnit()
                + (cfg.live ? " ⚡" : " (شبیه‌سازی)"));
        notifyStatus();
    }

    /** add a DCA ladder to the open position (weighted-average entry) */
    private void ladderBuy(Prefs.Cfg cfg, Market m, NobitexApi api, double price, double slEffPct,
                           long candleT) throws Exception {
        double amount = executeBuy(cfg, m, api, price, slEffPct);
        if (amount <= 0) return;

        double oldAmt = prefs.posAmount();
        double oldEntry = prefs.posEntry();
        double newAmt = oldAmt + amount;
        double avg = newAmt > 0 ? (oldEntry * oldAmt + price * amount) / newAmt : price;
        int ladder = prefs.posLadders() + 1;

        prefs.setPos(true, newAmt, avg, prefs.posTime(), prefs.posLive());
        prefs.setPosLadders(ladder);
        prefs.setPosLastLadder(candleT);
        if (price > prefs.posPeak()) prefs.setPosPeak(price);

        JSONObject t = new JSONObject();
        t.put("time", System.currentTimeMillis());
        t.put("side", "buy");
        t.put("price", price);
        t.put("amount", amount);
        t.put("ladder", ladder);
        t.put("live", cfg.live);
        Store.trade(t);
        Store.log("🟢 پله " + ladder + " خرید " + Fmt.amount(amount) + " " + Market.coinName(m.src)
                + " در قیمت " + Fmt.quote(price, m.isRls)
                + " — میانگین ورود جدید: " + Fmt.quote(avg, m.isRls));
        if (cfg.live) {
            BotService.notifyTrade(ctx, "🟢 پله " + ladder + " خرید",
                    Fmt.amount(amount) + " " + Market.coinName(m.src)
                            + " × " + Fmt.quote(price, m.isRls) + " " + m.quoteUnit());
        }
        sendTg(cfg, "🟢 پله " + ladder + ": خرید " + Fmt.amount(amount) + " " + Market.coinName(m.src)
                + " در " + Fmt.quote(price, m.isRls) + " " + m.quoteUnit()
                + " — میانگین: " + Fmt.quote(avg, m.isRls)
                + (cfg.live ? " ⚡" : " (شبیه‌سازی)"));
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
        prefs.setPosPeak(0);
        prefs.setPosLadders(0);
        prefs.setPosLastLadder(0);
        prefs.setPosTp1Taken(false);
        if (pnl < 0) {
            prefs.setConsecLosses(prefs.consecLosses() + 1);
        } else {
            prefs.setConsecLosses(0);
        }
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
        if (wasLive) {
            BotService.notifyTrade(ctx, pnl >= 0 ? "🔴 فروش با سود" : "🔴 فروش با ضرر",
                    Fmt.amount(amount) + " " + Market.coinName(m.src)
                            + " — نتیجه: " + Fmt.quote(pnl, m.isRls) + " " + m.quoteUnit()
                            + " (" + Fmt.pct(pnlPct) + ")");
        }
        sendTg(cfg, "🔴 فروش " + Fmt.amount(amount) + " " + Market.coinName(m.src)
                + " در " + Fmt.quote(price, m.isRls) + " " + m.quoteUnit()
                + " — نتیجه: " + Fmt.quote(pnl, m.isRls) + " " + m.quoteUnit()
                + " (" + Fmt.pct(pnlPct) + ")" + (wasLive ? " ⚡" : " (شبیه‌سازی)"));
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

    /** sell a fraction of the open position (partial take-profit) */
    private void sellFraction(Prefs.Cfg cfg, Market m, NobitexApi api, double price, double frac,
                              String reason) throws Exception {
        double posAmt = prefs.posAmount();
        double amount = Fmt.amountFloor(posAmt * frac);
        if (amount <= 0) return;
        boolean wasLive = prefs.posLive();
        double entry = prefs.posEntry();

        if (wasLive) {
            JSONObject order = api.addMarketOrder("sell", m.src, m.dst, amount);
            long id = order != null ? order.optLong("id", 0) : 0;
            Store.log("⚡ سفارش فروش پله‌ای واقعی ثبت شد (شناسه " + id + ")");
            double matched = waitAndMatch(api, id, amount);
            if (matched <= 0) return;
            amount = Math.min(amount, matched);
        }

        double pnl = (price - entry) * amount - price * amount * FEE;
        double remaining = posAmt - amount;
        prefs.setPos(remaining > 0, remaining, entry, prefs.posTime(), wasLive);
        prefs.setRealizedPnl(prefs.realizedPnl() + pnl);
        prefs.setTradeStats(prefs.tradeCount() + 1, prefs.winCount() + (pnl > 0 ? 1 : 0));

        JSONObject t = new JSONObject();
        t.put("time", System.currentTimeMillis());
        t.put("side", "sell");
        t.put("price", price);
        t.put("amount", amount);
        t.put("pnl", pnl);
        t.put("pnlPct", entry > 0 ? (price / entry - 1.0) * 100.0 : 0);
        t.put("partial", true);
        t.put("live", wasLive);
        Store.trade(t);
        Store.log("🟡 فروش پله‌ای " + Fmt.amount(amount) + " " + Market.coinName(m.src)
                + " در قیمت " + Fmt.quote(price, m.isRls)
                + " — سود قطعی: " + Fmt.quote(pnl, m.isRls) + " " + m.quoteUnit()
                + " — " + reason);
        if (wasLive) {
            BotService.notifyTrade(ctx, "🟡 برداشت سود پله‌ای",
                    Fmt.amount(amount) + " " + Market.coinName(m.src)
                            + " — سود: " + Fmt.quote(pnl, m.isRls) + " " + m.quoteUnit());
        }
        sendTg(cfg, "🟡 فروش پله‌ای " + Fmt.amount(amount) + " " + Market.coinName(m.src)
                + " در " + Fmt.quote(price, m.isRls) + " " + m.quoteUnit()
                + " — سود قطعی: " + Fmt.quote(pnl, m.isRls)
                + (wasLive ? " ⚡" : " (شبیه‌سازی)"));
        notifyStatus();
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
            WidgetProvider.push(ctx);
        } catch (Throwable ignored) {
        }
    }
}
