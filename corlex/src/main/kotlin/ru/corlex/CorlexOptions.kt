package ru.corlex

/** Where the person's consent stands. Without [GRANTED] the SDK sends nothing and stores nothing. */
public enum class Consent { GRANTED, PENDING, DENIED }

/** Options of [Corlex.start]; every one has a default. */
public class CorlexOptions @JvmOverloads constructor(
    /** Ingest base URL. */
    public val endpoint: String = "https://ingest.corlex.ru",
    /** Remote Config base URL (edge-config). */
    public val configEndpoint: String = "https://config.corlex.ru",
    /**
     * [Consent.PENDING] holds events in memory until [Corlex.optIn]; nothing is sent or written.
     * The default is [Consent.GRANTED] for apps that collect consent themselves.
     */
    public val consent: Consent = Consent.GRANTED,
    /** Events per request, 1..500. */
    public val batchSize: Int = 20,
    /** Milliseconds before a partial batch is sent. */
    public val flushIntervalMs: Long = 5000,
    /** `app_open`, `app_background` and `app_foreground`. */
    public val trackAppLifecycle: Boolean = true,
    /**
     * `screen_view` for every Activity that comes to the foreground. Turn it off in a single-Activity
     * Compose app that reports its screens with `rememberCorlexScreens`.
     */
    public val trackActivityScreens: Boolean = true,
    /** Uncaught exceptions are written to a file and sent as `corlex.crash` at the next launch. */
    public val crashReporting: Boolean = true,
    /** Download Remote Config and A/B tests (needs consent). */
    public val remoteConfig: Boolean = true,
    /** Apply a downloaded config at once. Default: it takes effect at the next launch. */
    public val liveConfig: Boolean = false,
    /** How long [CorlexConfig.awaitReady] waits for the download by default. */
    public val configTimeoutMs: Long = 1500,
    /** Log what the SDK drops or retries to Logcat (tag `Corlex`). */
    public val debug: Boolean = false,
    /** Explicit development override for non-loopback HTTP. Prefer a host-app debug condition. */
    public val allowInsecureHttp: Boolean = false,
    /** Explicitly include exception/cause text, which can contain application or personal data. */
    public val captureErrorMessages: Boolean = false,
)
