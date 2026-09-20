package ir.nobitrader.bot;

/** Nobitex market symbol metadata (no Android dependencies). */
public class Market {

    public final String symbol;   // e.g. BTCIRT
    public final String src;      // e.g. btc
    public final String dst;      // rls or usdt
    public final boolean isRls;   // true => quote values are in rials (10x toman)
    public final String title;    // Persian display name

    private Market(String symbol, String src, String dst, boolean isRls, String title) {
        this.symbol = symbol;
        this.src = src;
        this.dst = dst;
        this.isRls = isRls;
        this.title = title;
    }

    public String quoteUnit() {
        return isRls ? "تومان" : "تتر";
    }

    private static final String[] NAMES = {
            "btc", "بیت‌کوین", "eth", "اتریوم", "usdt", "تتر", "sol", "سولانا",
            "xrp", "ریپل", "doge", "دوج‌کوین", "trx", "ترون", "ada", "کاردانو",
            "bch", "بیت‌کوین کش", "ltc", "لایت‌کوین", "bnb", "بایننس کوین",
            "shib", "شیبا اینو", "ton", "تون‌کوین", "matic", "پالیگان",
            "dot", "پولکادات", "link", "چین‌لینک", "avax", "آوالانچ"
    };

    public static String coinName(String src) {
        for (int i = 0; i < NAMES.length; i += 2) {
            if (NAMES[i].equals(src)) return NAMES[i + 1];
        }
        return src.toUpperCase();
    }

    public static Market of(String symbol) {
        String s = symbol == null ? "BTCIRT" : symbol.toUpperCase();
        String src;
        boolean isRls;
        if (s.endsWith("IRT")) {
            src = s.substring(0, s.length() - 3).toLowerCase();
            isRls = true;
        } else if (s.endsWith("USDT")) {
            src = s.substring(0, s.length() - 4).toLowerCase();
            isRls = false;
        } else {
            src = "btc";
            isRls = true;
        }
        String title = coinName(src) + (isRls ? " / تومان" : " / تتر");
        return new Market(s, src, isRls ? "rls" : "usdt", isRls, title);
    }

    /** markets offered in the UI */
    public static final String[] SYMBOLS = {
            "BTCIRT", "ETHIRT", "USDTIRT", "SOLIRT", "XRPIRT", "DOGEIRT",
            "TRXIRT", "ADAIRT", "BCHIRT", "LTCIRT", "BNBIRT", "SHIBIRT",
            "BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT", "DOGEUSDT", "TRXUSDT"
    };

    public static String[] titles() {
        String[] out = new String[SYMBOLS.length];
        for (int i = 0; i < SYMBOLS.length; i++) out[i] = of(SYMBOLS[i]).title;
        return out;
    }
}
