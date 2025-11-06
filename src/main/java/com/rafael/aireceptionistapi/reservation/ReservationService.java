package com.rafael.aireceptionistapi.reservation;

import org.springframework.stereotype.Service;
import java.time.LocalTime;
import java.util.Random;

@Service
public class ReservationService {
    private final Random random = new Random();

    public String checkAvailability(String date, int people, String time) {
        // Demo: zufällige Verfügbarkeit
        boolean available = random.nextBoolean();

        if (available) {
            return "Ja, um " + time + " am " + date + " haben wir noch einen Tisch für " + people + " Personen frei.";
        } else {
            LocalTime t = LocalTime.parse(time).plusHours(1);
            return "Leider ist um " + time + " kein Tisch mehr frei, aber um " + t + " wäre noch etwas verfügbar. Möchten Sie diesen Termin reservieren?";
        }
    }
}
