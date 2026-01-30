package com.rafael.aireceptionistapi.realtime;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

public final class DateResolver {

    private static final ZoneId VIENNA = ZoneId.of("Europe/Vienna");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("EEEE, dd.MM.yyyy", Locale.GERMAN);

    private DateResolver() {}

    public static String resolveGermanRelativeDate(String phraseRaw) {
        String phrase = phraseRaw == null ? "" : phraseRaw.toLowerCase(Locale.GERMAN).trim();
        LocalDate today = LocalDate.now(VIENNA);

        if (phrase.contains("heute")) return payload(today);
        if (phrase.contains("morgen")) return payload(today.plusDays(1));
        if (phrase.contains("übermorgen") || phrase.contains("uebermorgen")) return payload(today.plusDays(2));

        DayOfWeek dow = parseDow(phrase);
        if (dow != null) {
            boolean forceNextWeek =
                    phrase.contains("nächsten") || phrase.contains("naechsten") || phrase.contains("darauf") || phrase.contains("darauffolgenden");

            LocalDate next = today.with(TemporalAdjusters.nextOrSame(dow));

            // Wenn heute derselbe Wochentag ist und "nächsten" gesagt wurde -> nächste Woche
            if (forceNextWeek && next.equals(today)) next = next.plusWeeks(1);

            // Wenn heute derselbe Wochentag ist und NICHT "heute" gesagt wurde -> eher nächste Woche
            if (next.equals(today) && !phrase.contains("heute")) next = next.plusWeeks(1);

            return payload(next);
        }

        // fallback: "unresolved"
        return """
        {"ok":false,"isoDate":null,"human":null,"note":"Konnte das Datum nicht sicher auflösen."}
        """.trim();
    }

    private static DayOfWeek parseDow(String phrase) {
        if (phrase.contains("montag")) return DayOfWeek.MONDAY;
        if (phrase.contains("dienstag")) return DayOfWeek.TUESDAY;
        if (phrase.contains("mittwoch")) return DayOfWeek.WEDNESDAY;
        if (phrase.contains("donnerstag")) return DayOfWeek.THURSDAY;
        if (phrase.contains("freitag")) return DayOfWeek.FRIDAY;
        if (phrase.contains("samstag")) return DayOfWeek.SATURDAY;
        if (phrase.contains("sonntag")) return DayOfWeek.SUNDAY;
        return null;
    }

    private static String payload(LocalDate date) {
        return ("{\"ok\":true,\"isoDate\":\"" + ISO.format(date) + "\",\"human\":\"" + HUMAN.format(date) + "\"}");
    }
}
