/**
 * Where the guidance comes from, and when it was last checked.
 *
 * No application can know that a published framework changed after it was
 * built. What it can do is refuse to hide the possibility: every reference
 * here carries the date it was last verified against its source, and the app
 * says so on screen. Once an entry passes [REVIEW_INTERVAL_MONTHS] it is
 * marked as needing review rather than quietly presented as current.
 *
 * That makes updating a change to this file alone — no rule, threshold or
 * screen has to be touched to correct a reference.
 *
 * `origin` matters because most published budgeting guidance is written for
 * one jurisdiction. A US mortgage ratio is not a fact about money, it is a
 * rule of a particular market, and it is labelled as such rather than shown
 * as though it were universal.
 */
package az.spendly.domain.insights

enum class MethodOrigin {
    /** Published by a national body; applies to that country's context. */
    US,

    /** Not tied to a jurisdiction — arithmetic, or a statistical method. */
    INTERNATIONAL,

    /** A product decision of this application, not external guidance. */
    APP,
}

data class Methodology(
    val name: String,
    /** What it means, in one sentence. */
    val note: String,
    val source: String,
    val url: String?,
    val origin: MethodOrigin,
    /** ISO date the reference was last verified against its source. */
    val reviewedOn: String,
)

/** How long a reference is presented as current before it is flagged. */
const val REVIEW_INTERVAL_MONTHS = 12

/** The date every entry below was last checked against its source. */
private const val REVIEWED = "2026-08-19"

enum class MethodId {
    SPENDING_RATIO,
    RETAINED,
    VARIANCE,
    ANOMALY,
    TREND,
    CONCENTRATION,
    UNEXPECTED,
    RECURRING,
    ZERO_BASED,
    SINKING_FUND,
    LIFESTYLE,
    NEEDS_WANTS,
    FRAMEWORK_50_30_20,
    EMERGENCY_FUND,
    MONEY_PRINCIPLES,
}

val METHODS: Map<MethodId, Methodology> = mapOf(
    MethodId.SPENDING_RATIO to Methodology(
        name = "Xərc / gəlir nisbəti",
        note = "Büdcənin əsası: gəlir − xərc = qalan. Nisbət bunun faizlə ifadəsidir.",
        source = "FDIC Money Smart",
        url = "https://www.fdic.gov/consumer-resource-center/money-smart-adults",
        origin = MethodOrigin.US,
        reviewedOn = REVIEWED,
    ),
    MethodId.RETAINED to Methodology(
        name = "Qalan pul və onun faizi",
        note = "BEA-nın şəxsi yığım nisbətinin qarşılığı. Tətbiq pulun saxlanıb-saxlanmadığını " +
            "görmədiyi üçün \"yığım\" yox, \"qalan\" deyilir.",
        source = "U.S. Bureau of Economic Analysis",
        url = "https://www.bea.gov/data/income-saving/personal-saving-rate",
        origin = MethodOrigin.US,
        reviewedOn = REVIEWED,
    ),
    MethodId.VARIANCE to Methodology(
        name = "Plan və faktiki fərqi",
        note = "Xərc planını faktiki xərclə müqayisə etmək — büdcə idarəçiliyinin əsas addımı.",
        source = "FDIC Money Smart",
        url = "https://www.fdic.gov/consumer-resource-center/money-smart-adults",
        origin = MethodOrigin.US,
        reviewedOn = REVIEWED,
    ),
    MethodId.ANOMALY to Methodology(
        name = "Qeyri-adi xərcin aşkarlanması",
        note = "Median mütləq kənarlaşma (MAD) ilə. Orta və standart kənarlaşma axtarılan " +
            "kənar dəyərin özündən təsirlənir.",
        source = "Leys et al. (2013), Journal of Experimental Social Psychology",
        url = "https://dipot.ulb.ac.be/dspace/bitstream/2013/139499/1/Leys_MAD_final-libre.pdf",
        origin = MethodOrigin.INTERNATIONAL,
        reviewedOn = REVIEWED,
    ),
    MethodId.TREND to Methodology(
        name = "Trend (hərəkətli ortalama)",
        note = "Cari ay əvvəlki üç ayın ortalaması ilə müqayisə olunur.",
        source = "Təsviri statistika — tətbiqin qaydası",
        url = null,
        origin = MethodOrigin.APP,
        reviewedOn = REVIEWED,
    ),
    MethodId.CONCENTRATION to Methodology(
        name = "Kateqoriya cəmləşməsi",
        note = "Xərcin kateqoriyalar üzrə paylanması.",
        source = "CFPB — Your Money, Your Goals",
        url = "https://www.consumerfinance.gov/consumer-tools/educator-tools/your-money-your-goals/toolkit/",
        origin = MethodOrigin.US,
        reviewedOn = REVIEWED,
    ),
    MethodId.UNEXPECTED to Methodology(
        name = "Gözlənilməz xərc",
        note = "Kateqoriya üzrə min(faktiki, plan) gözlənilən, qalan hissə gözlənilməzdir.",
        source = "Tətbiqin öz tərifi",
        url = null,
        origin = MethodOrigin.APP,
        reviewedOn = REVIEWED,
    ),
    MethodId.RECURRING to Methodology(
        name = "Təkrarlanan öhdəliklər",
        note = "Əvvəlki ayda da planlaşdırılmış eyni sətirlər.",
        source = "Tətbiqin öz tərifi",
        url = null,
        origin = MethodOrigin.APP,
        reviewedOn = REVIEWED,
    ),
    MethodId.ZERO_BASED to Methodology(
        name = "Sıfır-baza büdcəsi",
        note = "Hər manatın təyinatı olur: planlaşdırılan gəlir − planlaşdırılan xərc = 0. " +
            "Korporativ metod kimi yaranıb, sonra ev büdcəsinə uyğunlaşdırılıb.",
        source = "Pyhrr, P. A. (1970). \"Zero-base budgeting\", Harvard Business Review, " +
            "48(6), 111–121",
        url = null,
        origin = MethodOrigin.INTERNATIONAL,
        reviewedOn = REVIEWED,
    ),
    MethodId.SINKING_FUND to Methodology(
        name = "Gələcək xərc üçün aylıq ayırma",
        note = "Gələcək aya planlaşdırılmış xərc, qalan ay sayına bölünür.",
        source = "Bölmə əməliyyatı — tətbiqin qaydası",
        url = null,
        origin = MethodOrigin.APP,
        reviewedOn = REVIEWED,
    ),
    MethodId.NEEDS_WANTS to Methodology(
        name = "Ehtiyac və istək",
        note = "Xərcin zəruri və istəyə bağlı hissələrə ayrılması. Bölgü mühakimə deyil — " +
            "hansı kateqoriyanın hansı olduğunu siz təyin edirsiniz.",
        source = "CFPB — Budgeting for needs and wants",
        url = "https://www.consumerfinance.gov/consumer-tools/educator-tools/" +
            "youth-financial-education/teach/activities/budgeting-needs-and-wants/",
        origin = MethodOrigin.US,
        reviewedOn = REVIEWED,
    ),
    MethodId.FRAMEWORK_50_30_20 to Methodology(
        name = "50/30/20 çərçivəsi",
        note = "Gəlirin 50%-i zəruri, 30%-i istəyə bağlı, 20%-i yığım. CFPB bunu bir neçə " +
            "qaydadan biri kimi öyrədir — hamıya uyğun gəlmir.",
        source = "Warren & Tyagi, All Your Worth (2005); CFPB — Analyzing budgets",
        url = "https://www.consumerfinance.gov/consumer-tools/educator-tools/" +
            "youth-financial-education/teach/activities/analyzing-budgets/",
        origin = MethodOrigin.US,
        reviewedOn = REVIEWED,
    ),
    MethodId.EMERGENCY_FUND to Methodology(
        name = "Təcili ehtiyat fondu",
        note = "Zəruri aylıq xərcin medianı × sizin seçdiyiniz ay sayı. CFPB vahid rəqəm " +
            "vermir: \"lazım olan məbləğ vəziyyətinizdən asılıdır\".",
        source = "CFPB — An essential guide to building an emergency fund",
        url = "https://www.consumerfinance.gov/an-essential-guide-to-building-an-emergency-fund/",
        origin = MethodOrigin.US,
        reviewedOn = REVIEWED,
    ),
    MethodId.MONEY_PRINCIPLES to Methodology(
        name = "Beş maliyyə prinsipi",
        note = "Qazan, yığ və investisiya et, qoru, xərclə, borc al — ABŞ Maliyyə Savadlılığı " +
            "Komissiyasının çərçivəsi. Bu səhifədəki bölmələrin arxasındakı ümumi məntiq.",
        source = "MyMoney.gov — MyMoney Five",
        url = "https://www.mymoney.gov/mymoneyfive",
        origin = MethodOrigin.US,
        reviewedOn = REVIEWED,
    ),
    MethodId.LIFESTYLE to Methodology(
        name = "Həyat tərzi inflyasiyası",
        note = "Son 3 ayın xərc artımı ilə gəlir artımının müqayisəsi.",
        source = "Təsviri statistika — tətbiqin qaydası",
        url = null,
        origin = MethodOrigin.APP,
        reviewedOn = REVIEWED,
    ),
)

/** Months between two `YYYY-MM-DD` dates, by calendar month. */
private fun monthsSince(iso: String, asOf: String): Int {
    fun key(value: String): Int {
        val (year, month) = value.split("-").map { it.toInt() }
        return year * 12 + (month - 1)
    }
    return key(asOf) - key(iso)
}

/**
 * True when a reference has gone longer than the review interval without
 * being checked. The app shows this rather than assuming the guidance it was
 * built with is still what the source says.
 */
fun needsReview(method: Methodology, asOf: String): Boolean =
    monthsSince(method.reviewedOn, asOf) >= REVIEW_INTERVAL_MONTHS

/** Every reference that is due a check, so the screen can say so once. */
fun methodsNeedingReview(asOf: String): List<Methodology> =
    METHODS.values.filter { needsReview(it, asOf) }

/** How an origin should be described where the reference is shown. */
val ORIGIN_LABEL: Map<MethodOrigin, String> = mapOf(
    MethodOrigin.US to "ABŞ mənbəyi",
    MethodOrigin.INTERNATIONAL to "beynəlxalq",
    MethodOrigin.APP to "tətbiqin qaydası",
)
