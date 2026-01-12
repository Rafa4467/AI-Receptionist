package com.rafael.aireceptionistapi.knowledge;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
public class WebsiteIngestionService {

    private final RestaurantKnowledgeRepository repo;

    // Für den Start: fix
    private static final String PHONE = "+431234567"; // <-- DEINE Twilio-Nummer
    private static final String URL = "https://www.vivalamamma.at";

    public WebsiteIngestionService(RestaurantKnowledgeRepository repo) {
        this.repo = repo;
    }

    // 1x pro Tag
    @Scheduled(cron = "0 0 3 * * *")
    public void ingestWebsite() {
        try {
            Document doc = Jsoup.connect(URL)
                    .userAgent("AI-Receptionist-Bot")
                    .timeout(15_000)
                    .get();

            // Grobe Bereinigung
            doc.select("script, style, nav, footer").remove();
            String text = doc.body().text();

            RestaurantKnowledge rk = repo.findByPhoneNumber(PHONE)
                    .orElseGet(RestaurantKnowledge::new);

            rk.setPhoneNumber(PHONE);
            rk.setSourceUrl(URL);
            rk.setRawText(text);
            rk.setLastUpdated(OffsetDateTime.now());

            repo.save(rk);

            System.out.println("Website-Ingestion erfolgreich");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
